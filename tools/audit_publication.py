#!/usr/bin/env python3
"""Local pre-publication Git history audit. Never prints credential material.

Run on a full local clone with all branches/tags/PR refs fetched. This scanner
is not an exhaustive security guarantee; it deliberately errs toward warnings.
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

SECRET_PATTERNS = {
    "private_key": re.compile(rb"-----BEGIN (?:RSA |DSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
    "github_legacy_token": re.compile(rb"\bgh[pousr]_[a-zA-Z0-9_]{30,}\b"),
    "github_fine_grained_token": re.compile(rb"\bgithub_pat_[a-zA-Z0-9_]{20,}\b"),
    "openai_style_token": re.compile(rb"\bsk-(?:proj-|or-)?[A-Za-z0-9_-]{25,}\b"),
    "aws_access_id": re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "google_api_key": re.compile(rb"\bAIza[0-9A-Za-z_-]{35}\b"),
    "slack_token": re.compile(rb"\bxox[baprs]-[0-9A-Za-z-]{15,}\b"),
    "gitlab_token": re.compile(rb"\bglpat-[A-Za-z0-9_-]{20,}\b"),
}
SENSITIVE_PATH = re.compile(
    r"(?:^|/)(?:\.env(?:\..*)?|local\.properties|keystore\.properties|"
    r"signing\.properties|google-services\.json|id_rsa|id_ed25519|"
    r"[^/]+\.(?:jks|keystore|p12|pfx|kdbx|pem|b64))$", re.I
)
MAX_EXAMPLES = 30

def git(repo, *args, input_data=None):
    cp = subprocess.run(["git", "-C", str(repo), *args], input=input_data,
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if cp.returncode:
        raise RuntimeError(f"git {args[0]} failed (code {cp.returncode}): "
                           f"{cp.stderr.decode(errors='replace')[:250]}")
    return cp.stdout

def audit(repo, max_blob_bytes):
    bare = git(repo, "rev-parse", "--is-bare-repository").decode().strip() == "true"
    root = str(repo.resolve()) if bare else git(repo, "rev-parse", "--show-toplevel").decode().strip()
    refs = git(root, "for-each-ref", "--format=%(refname)").decode().splitlines()
    commits = git(root, "rev-list", "--all").decode().splitlines()
    email_lines = git(root, "log", "--all", "--format=%ae%n%ce").decode().splitlines()
    emails = {s for s in email_lines if s and not s.lower().endswith("@users.noreply.github.com")
              and not s.lower().endswith("@noreply.github.com")}
    entries = git(root, "rev-list", "--objects", "--all").decode(errors="replace").splitlines()
    paths_by_oid = {}
    for entry in entries:
        oid, _, path = entry.partition(" ")
        paths_by_oid.setdefault(oid, path)
    ids = list(paths_by_oid)
    meta = git(root, "cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)",
               input_data=("\n".join(ids) + "\n").encode()).decode().splitlines()
    blob_objects = []
    skipped_large = 0
    for entry in meta:
        tokens = entry.split()
        if len(tokens) != 3 or tokens[1] != "blob":
            continue
        oid, _, size_string = tokens
        size = int(size_string)
        if size > max_blob_bytes:
            skipped_large += 1
        else:
            blob_objects.append(oid)
    secret_matches = []
    if blob_objects:
        proc = subprocess.Popen(["git", "-C", root, "cat-file", "--batch"],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE)
        assert proc.stdin and proc.stdout
        for oid in blob_objects:
            proc.stdin.write((oid + "\n").encode())
            proc.stdin.flush()
            header = proc.stdout.readline().decode(errors="replace").split()
            if len(header) != 3 or header[0] != oid or header[1] != "blob":
                proc.kill()
                raise RuntimeError("unexpected git cat-file response")
            length = int(header[2])
            content = proc.stdout.read(length)
            proc.stdout.read(1)
            for kind, pattern in SECRET_PATTERNS.items():
                if pattern.search(content):
                    secret_matches.append({"kind": kind, "object": oid[:12],
                                           "file": paths_by_oid.get(oid, "")[:150]})
        proc.stdin.close()
        if proc.wait(timeout=30) != 0:
            raise RuntimeError("git cat-file failed")
    sensitive_paths = sorted({p[:150] for p in paths_by_oid.values()
                              if p and SENSITIVE_PATH.search(p)})
    head_paths = git(root, "ls-tree", "-r", "--name-only", "HEAD").decode(errors="replace").splitlines()
    result = {
        "repository": root,
        "refs_scanned": len(refs),
        "commits_scanned": len(commits),
        "historical_git_objects": len(ids),
        "text_size_bounded_blobs_scanned": len(blob_objects),
        "large_blobs_unscanned": skipped_large,
        "non_noreply_email_count": len(emails),
        "non_noreply_email_occurrences": sum(1 for e in email_lines if e in emails),
        "matches": secret_matches[:MAX_EXAMPLES],
        "secret_match_count": len(secret_matches),
        "sensitive_paths": sensitive_paths[:MAX_EXAMPLES],
        "sensitive_path_count": len(sensitive_paths),
        "has_license_in_head": any(p.lower() in ("license", "license.md", "license.txt", "copying") for p in head_paths),
        "has_gitignore_in_head": ".gitignore" in head_paths,
        "limitations": [
            "Unreachable/deleted GitHub objects, fork objects, issue/PR text, action logs/artifacts and secret settings are not scanned.",
            "GitHub pull request refs must be fetched explicitly, not assumed to be covered by --all.",
            "Pattern matches can be synthetic test strings; every match needs human review.",
            "Binary/large blobs above max-blob-bytes were not content-scanned.",
        ],
    }
    blockers = bool(emails or secret_matches or sensitive_paths or skipped_large)
    result["publication_gate"] = "REVIEW_REQUIRED" if blockers else "INCOMPLETE_EXTERNAL_REVIEW"
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path, nargs="?", default=Path("."))
    parser.add_argument("--out", type=Path, help="Write local report as UTF-8 JSON")
    parser.add_argument("--max-blob-mib", type=int, default=8)
    args = parser.parse_args()
    try:
        report = audit(args.repository, args.max_blob_mib * 1024 * 1024)
    except (RuntimeError, OSError, ValueError) as e:
        print(f"Audit incomplete: {e}", file=sys.stderr)
        return 3
    body = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(body, encoding="utf-8")
    print(body)
    return 2 if report["publication_gate"] == "REVIEW_REQUIRED" else 0

if __name__ == "__main__":
    sys.exit(main())

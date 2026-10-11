#!/usr/bin/env python3
"""Prune obsolete, known Android build artifacts from this GitHub repository.

Never deletes workflow runs, logs, Gradle caches, or artifacts with unknown names.
Uses the ephemeral GITHUB_TOKEN only in a trusted default-branch workflow.
"""
import argparse
from collections import defaultdict
from datetime import datetime, timedelta, timezone
import json
import os
import sys
import urllib.error
import urllib.request

EXPECTED_REPO = "luoecrt-pixel/000Chat"
TARGET_NAMES = frozenset(("OpenAIApiChat-Astra-debug", "OpenAIApiChat-Astra-release"))


def timestamp(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def plan(artifacts, now, keep_latest=4, min_age_hours=24):
    """Compute an immutable deletion plan; protect latest N per artifact name."""
    if keep_latest < 2 or min_age_hours < 24:
        raise ValueError("Refuse deletion without safety window")
    grouped = defaultdict(list)
    for a in artifacts:
        if a.get("name") not in TARGET_NAMES or a.get("expired") is True:
            continue
        if not isinstance(a.get("id"), int) or a["id"] <= 0:
            raise ValueError("Artifact is missing a valid numeric id")
        when = timestamp(a["created_at"])
        if when > now + timedelta(minutes=5):
            raise ValueError("Artifact timestamp is in the future")
        grouped[a["name"]].append(a)
    candidates = []
    retained = {}
    for name in sorted(grouped):
        ordered = sorted(grouped[name], key=lambda a: (timestamp(a["created_at"]), a["id"]), reverse=True)
        retained[name] = [x["id"] for x in ordered[:keep_latest]]
        for a in ordered[keep_latest:]:
            if now - timestamp(a["created_at"]) >= timedelta(hours=min_age_hours):
                candidates.append(a)
    candidates.sort(key=lambda a: (timestamp(a["created_at"]), a["id"]))
    return candidates, retained


class Client:
    def __init__(self, token, repo=EXPECTED_REPO):
        if repo != EXPECTED_REPO or not token:
            raise ValueError("Missing ephemeral token or unapproved repository")
        self.repo = repo
        self.token = token
        self.base = f"https://api.github.com/repos/{repo}/actions/artifacts"

    def request(self, url, method="GET"):
        request = urllib.request.Request(
            url, method=method,
            headers={"Authorization": f"Bearer {self.token}",
                     "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28",
                     "User-Agent": "000Chat-safe-artifact-cleanup"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                if method == "DELETE":
                    if response.status != 204:
                        raise RuntimeError(f"Unexpected delete status: {response.status}")
                    return None
                return json.load(response)
        except urllib.error.HTTPError as exc:
            raise RuntimeError(f"GitHub API {method} failed, HTTP {exc.code}") from None

    def list_artifacts(self):
        result = []
        total = None
        for page in range(1, 51):
            batch = self.request(f"{self.base}?per_page=100&page={page}")
            if not isinstance(batch, dict) or not isinstance(batch.get("artifacts"), list):
                raise RuntimeError("Malformed artifact listing")
            if total is None:
                total = int(batch.get("total_count", 0))
                if total > 5000:
                    raise RuntimeError("Listing exceeds 5000 artifacts; refuse incomplete cleanup")
            result.extend(batch["artifacts"])
            if len(batch["artifacts"]) < 100:
                if total is not None and len(result) < total:
                    raise RuntimeError("Artifact list changed while paging; retry later")
                return result
        raise RuntimeError("Too many artifact listing pages")

    def delete_artifact(self, artifact_id):
        return self.request(f"{self.base}/{artifact_id}", method="DELETE")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Delete matching obsolete artifacts (default: dry run)")
    parser.add_argument("--keep-latest", type=int, default=4)
    parser.add_argument("--min-age-hours", type=int, default=24)
    parser.add_argument("--max-deletions", type=int, default=300)
    args = parser.parse_args(argv)
    if os.environ.get("GITHUB_REPOSITORY") != EXPECTED_REPO:
        parser.error("This cleanup is restricted to the official 000Chat repository")
    if args.max_deletions <= 0 or args.max_deletions > 300:
        parser.error("max-deletions must be 1..300")
    client = Client(os.environ.get("GH_TOKEN", ""))
    artifacts = client.list_artifacts()
    now = datetime.now(timezone.utc)
    deletions, protected = plan(artifacts, now, args.keep_latest, args.min_age_hours)
    deletions = deletions[:args.max_deletions]
    reclaim = sum(max(0, int(a.get("size_in_bytes", 0))) for a in deletions)
    print(f"Found {len(artifacts)} artifacts (all names), keep-latest={args.keep_latest} per Android type")
    print(f"Protected IDs: {json.dumps(protected, sort_keys=True)}")
    print(f"Eligible old Android artifacts: {len(deletions)}, estimated reclaim={reclaim / 1024 / 1024:.2f} MiB")
    for a in deletions:
        print(f"{'Deleting' if args.apply else 'Would delete'}: id={a['id']} "
              f"name={a['name']} created={a['created_at']} size={a.get('size_in_bytes', 0)}")
        if args.apply:
            client.delete_artifact(a["id"])
    print(f"Result: {'deleted' if args.apply else 'dry-run'}={len(deletions)}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, RuntimeError, KeyError, TypeError) as exc:
        print(f"Safe cleanup aborted: {exc}", file=sys.stderr)
        sys.exit(1)

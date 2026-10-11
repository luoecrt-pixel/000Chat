"""Synthetic positive/negative controls, not execution of the real KaTeX/Mermaid engines."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def check(directory):
    return subprocess.run([sys.executable, str(ROOT / "tools/verify_response_renderer_assets.py"), str(directory)],
                          capture_output=True, text=True)


with tempfile.TemporaryDirectory(prefix="renderer-fixtures-", dir=ROOT.parent) as name:
    base = Path(name) / "valid"
    values = {
        "distribution-sha256.txt": "katex 0.17.0 " + "0" * 64 + "\nmermaid 11.14.0 " + "1" * 64 + "\n",
        "katex/LICENSE": "synthetic fixture", "mermaid/LICENSE": "synthetic fixture",
        "katex/katex.min.js": "void 0;", "katex/katex.min.css": "@font-face {src:url(fonts/test.woff2)}",
        "katex/fonts/test.woff2": "synthetic fixture",
        "mermaid/mermaid.esm.min.mjs": "const css='@import'; const prose='from fake'; /* import 'fake'; */ import './chunks/mermaid.esm.min/graph.mjs';",
        "mermaid/chunks/mermaid.esm.min/graph.mjs": "export const graph = 1;",
    }
    for path, text in values.items():
        target = base / path; target.parent.mkdir(parents=True, exist_ok=True); target.write_text(text)
    result = check(base)
    assert result.returncode == 0, result.stderr
    print("PASS asset inventory fixture (synthetic checksums are NOT approvals)")
    cases = [
        ("missing_chunk", "mermaid/chunks/mermaid.esm.min/graph.mjs", None),
        ("missing_font", "katex/fonts/test.woff2", None),
        ("extra_nonminified_distribution", "mermaid/chunks/mermaid.esm/duplicate.mjs", "export const other = 1;"),
        ("external_import", "mermaid/mermaid.esm.min.mjs", "import 'https://tracker.invalid/engine.mjs';"),
        ("import_outside_assets", "mermaid/mermaid.esm.min.mjs", "import '../../outside.mjs';"),
    ]
    for label, path, replacement in cases:
        copy = Path(name) / label; shutil.copytree(base, copy)
        target = copy / path
        if replacement is None: target.unlink()
        else: target.parent.mkdir(parents=True, exist_ok=True); target.write_text(replacement)
        result = check(copy)
        assert result.returncode != 0 and "AssertionError" in result.stderr, f"Asset verifier accepted {label}"
        print(f"PASS {label}: rejected")
print("Asset verifier fixtures: PASS (synthetic positive + 5 negative controls; no engines or APK)")

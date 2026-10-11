"""Check generated offline assets after :app:prepareResponseRendererAssets, no network."""
import argparse
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("asset_root", nargs="?", type=Path,
                    default=Path(__file__).resolve().parents[1] / "app/build/generated/responseRendererAssets/response-renderer")
args = parser.parse_args()
root = args.asset_root.resolve()
assert root.is_dir(), "Generate the pinned distributions before checking offline assets"
manifest = (root / "distribution-sha256.txt").read_text().splitlines()
assert len(manifest) == 2 and all(re.fullmatch(r"(?:katex 0\.17\.0|mermaid 11\.14\.0) [0-9a-f]{64}", line) for line in manifest)
assert (root / "katex/katex.min.js").is_file()
assert (root / "katex/katex.min.css").is_file()
assert (root / "mermaid/mermaid.esm.min.mjs").is_file()
for name in ("katex", "mermaid"):
    assert any(file.is_file() and file.name.upper().startswith("LICENSE") for file in (root / name).iterdir()), f"{name}: missing root license"


def local_reference(parent, reference):
    if reference.startswith("data:"):
        return
    assert not re.match(r"[a-zA-Z][a-zA-Z0-9+.-]*:", reference) and not reference.startswith("//"), f"External runtime resource: {reference}"
    target = (parent / reference.split("?", 1)[0].split("#", 1)[0]).resolve()
    assert target.is_relative_to(root), f"Runtime import leaves asset root: {reference}"
    assert target.is_file(), f"Missing offline runtime resource: {target}"


def module_references(source):
    # Tokenize strings/comments first: CSS text such as "@import" is not a JS import.
    # This checks literal module references, not computed imports or template expressions;
    # the WebView fixture remains mandatory for execution of the complete graph.
    token = re.compile(r'''(?P<space>\s+)|(?P<comment>//[^\n]*|/\*.*?\*/)|(?P<string>"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(?P<template>`(?:[^`\\]|\\.)*`)|(?P<word>[$\w]+)|(?P<punct>.)''', re.S)
    items = [(m.lastgroup, m.group()) for m in token.finditer(source)
             if m.lastgroup not in ("space", "comment", "template")]
    def literal(index):
        value = items[index][1][1:-1]
        assert "\\" not in value, "Escaped module reference requires explicit review"
        return value
    for index, (kind, value) in enumerate(items):
        if kind != "word" or value not in ("import", "export"):
            continue
        if index and items[index - 1][1] == ".":
            continue
        next_index = index + 1
        if next_index >= len(items) or items[next_index][1] == ".":
            continue
        if value == "import" and items[next_index][0] == "string":
            yield literal(next_index)
        elif value == "import" and items[next_index][1] == "(":
            if next_index + 1 < len(items) and items[next_index + 1][0] == "string":
                yield literal(next_index + 1)
        elif items[next_index][1] in ("{", "*") or items[next_index][0] == "word":
            while next_index < len(items) and items[next_index][1] != ";":
                if items[next_index] == ("word", "from"):
                    if next_index + 1 < len(items) and items[next_index + 1][0] == "string":
                        yield literal(next_index + 1)
                    break
                next_index += 1
checked = 0
for module in (root / "mermaid").rglob("*.mjs"):
    relative = module.relative_to(root / "mermaid").as_posix()
    assert relative == "mermaid.esm.min.mjs" or relative.startswith("chunks/mermaid.esm.min/"), \
        f"Unexpected alternative Mermaid distribution in APK assets: {relative}"
    source = module.read_text()
    for reference in module_references(source):
        assert reference.startswith(("./", "../")), f"Unbundled runtime dependency in {module.name}: {reference}"
        local_reference(module.parent, reference)
    checked += 1
css = root / "katex/katex.min.css"
for reference in re.findall(r"url\(\s*['\"]?([^)'\"\s]+)", css.read_text()):
    local_reference(css.parent, reference)
print(f"Offline entry points, licenses, fonts and detected references in {checked} modules: PASS")
print(f"Renderer asset bytes: KaTeX={sum(p.stat().st_size for p in (root / 'katex').rglob('*') if p.is_file())}, Mermaid={sum(p.stat().st_size for p in (root / 'mermaid').rglob('*') if p.is_file())}")
print("Import scanning is conservative; the Android WebView test must execute the real module graph.")
print("Recorded jar SHA values are traceability metadata; the Release guard independently compares exact bytes against config/response-renderer-sha256.properties.")

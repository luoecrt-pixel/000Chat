#!/usr/bin/env python3
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERIFY = ROOT / "tools/verify_v4_17_0.py"
MAIN = ROOT / "app/src/main/java/com/example/openaichat/MainActivity.kt"

METHOD_START = "private fun renderVectorStoreList(vsId:String)"
NEXT_METHOD = "    private fun pickFileForVectorStore()"
INITIAL_STATUS = '            vsStatusLine?.text="Загрузка списка файлов…"'
OUTER_GATE = "        runOnUiThread{"

MUTATIONS = [
    "remove_outer_ui_gate",
    "move_initial_ui_mutation_before_gate",
    "inject_post_gate_ui_mutation",
]


def render_method(source: str):
    start = source.find(METHOD_START)
    assert start >= 0, "Vector Store render method not found"
    end = source.find(NEXT_METHOD, start)
    assert end > start, "Vector Store render method boundary not found"
    return start, end


def mutate(source: str, name: str) -> str:
    start, end = render_method(source)
    block = source[start:end]

    if name == "remove_outer_ui_gate":
        mutated = block.replace(OUTER_GATE, "        executor.execute{", 1)
        assert mutated != block
        return source[:start] + mutated + source[end:]

    if name == "move_initial_ui_mutation_before_gate":
        old = OUTER_GATE + "\n" + INITIAL_STATUS
        new = INITIAL_STATUS.replace("            ", "        ", 1) + "\n" + OUTER_GATE
        mutated = block.replace(old, new, 1)
        assert mutated != block
        return source[:start] + mutated + source[end:]

    if name == "inject_post_gate_ui_mutation":
        close = block.rfind("\n    }")
        assert close > 0, "Vector Store method closing brace not found"
        injected = block[:close] + '\n        vsStatusLine?.text="MUTATION_OUTSIDE_UI_GATE"' + block[close:]
        return source[:start] + injected + source[end:]

    raise AssertionError(f"unknown mutation: {name}")


def run_verifier(temp_root: Path) -> subprocess.CompletedProcess[str]:
    env = dict(os.environ)
    env["ASTRA_VERIFY_ROOT"] = str(temp_root)
    return subprocess.run(
        [sys.executable, str(VERIFY)],
        cwd=ROOT,
        env=env,
        text=True,
        capture_output=True,
        check=False,
    )


def main() -> int:
    source = MAIN.read_text()
    cases = [("positive_baseline", None, True)] + [
        (name, name, False) for name in MUTATIONS
    ]

    with tempfile.TemporaryDirectory(prefix="astra-verify-mutations-") as tmp:
        temp_root = Path(tmp) / "repo"
        shutil.copytree(
            ROOT,
            temp_root,
            ignore=shutil.ignore_patterns(".git", ".gradle", "build"),
        )
        temp_main = temp_root / "app/src/main/java/com/example/openaichat/MainActivity.kt"

        for case_name, mutation, expected_pass in cases:
            mutated_source = source if mutation is None else mutate(source, mutation)
            temp_main.write_text(mutated_source)
            result = run_verifier(temp_root)
            passed = result.returncode == 0

            if passed != expected_pass:
                print(f"FAIL {case_name}: expected_pass={expected_pass}, returncode={result.returncode}")
                print(result.stdout)
                print(result.stderr)
                return 1

            if expected_pass:
                print(f"PASS {case_name}: verifier accepts known-good source")
            else:
                output = (result.stderr or result.stdout).strip()
                reason = output.splitlines()[-1] if output else "no diagnostic"
                print(f"PASS {case_name}: verifier rejects mutation ({reason})")

    print("Verifier mutation matrix: PASS (3 targeted negative mutations + positive control)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

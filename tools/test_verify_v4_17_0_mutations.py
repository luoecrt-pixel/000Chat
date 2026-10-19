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
DB = ROOT / "app/src/main/java/com/example/openaichat/ChatDatabase.kt"
RESUME = ROOT / "app/src/main/java/com/example/openaichat/ResumableResponseStreamClient.kt"
STREAM = ROOT / "app/src/main/java/com/example/openaichat/BoundedResponseDecoder.kt"

METHOD_START = "private fun renderVectorStoreList(vsId:String)"
NEXT_METHOD = "    private fun pickFileForVectorStore()"
INITIAL_STATUS = '            vsStatusLine?.text="Загрузка списка файлов…"'
OUTER_GATE = "        runOnUiThread{"

MUTATIONS = [
    "remove_outer_ui_gate",
    "move_initial_ui_mutation_before_gate",
    "inject_post_gate_ui_mutation",
    "remove_v18_table_creation_before_upgrade",
    "remove_v18_cost_column_guard",
    "remove_foreground_stream_reader",
    "remove_resume_stream_reader",
    "disable_stream_terminal_break",
    "drop_foreground_received_typed_output",
    "replace_local_assistant_request_id_with_http_id",
    "download_db_remove_source_cas",
    "download_db_read_outside_transaction",
    "download_db_use_cursorwindow_for_source",
    "download_remove_posted_ui_source_guard",
    "download_remove_preview_source_guard",
    "download_remove_failed_cas_cleanup",
    "download_restore_unsafe_message_helper",
    "download_remove_queued_credential_guard",
]

STREAM_MUTATIONS = {
    "remove_foreground_stream_reader",
    "remove_resume_stream_reader",
    "disable_stream_terminal_break",
}


def render_method(source: str):
    start = source.find(METHOD_START)
    assert start >= 0, "Vector Store render method not found"
    end = source.find(NEXT_METHOD, start)
    assert end > start, "Vector Store render method boundary not found"
    return start, end


def mutate(source: str, name: str) -> str:
    if name == "download_remove_queued_credential_guard":
        old = 'if(credential!=readCredential())throw IOException('
        assert source.count(old) == 2
        return source.replace(old, 'if(false)throw IOException(', 1)
    if name == "download_remove_posted_ui_source_guard":
        old = ' || !chatDb.isHistoricalAttachmentDownloadCurrent(target,finalPath)'
        assert source.count(old) == 2
        return source.replace(old, '', 1)
    if name == "download_remove_preview_source_guard":
        old = ' && chatDb.isHistoricalAttachmentDownloadCurrent(target,finalPath)'
        assert source.count(old) == 2
        return source.replace(old, '', 1)
    if name == "download_remove_failed_cas_cleanup":
        old = 'if(!retained)destination?.delete()'
        assert source.count(old) == 2
        return source.replace(old, '', 1)
    if name == "download_restore_unsafe_message_helper":
        return source + '\n// updateMessageFileLocalState: unsafe captured message helper\n'
    if name == "drop_foreground_received_typed_output":
        old = "OpenAiEffectiveResponse.assemble(completedResponse, responseState)"
        assert old in source
        return source.replace(old, "completedResponse ?: JSONObject()")
    if name == "replace_local_assistant_request_id_with_http_id":
        old = "effectiveResponse,\n                        finalText,\n                        localRequestId,"
        assert old in source
        return source.replace(old, "effectiveResponse,\n                        finalText,\n                        requestId,", 1)
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


def mutate_db(source: str, name: str) -> str:
    if name == "download_db_remove_source_cas":
        old = 'if (!target.matches(current)) return@transaction'
        assert source.count(old) == 2
        return source.replace(old, 'if (false) return@transaction', 1)
    if name == "download_db_read_outside_transaction":
        old = 'val current = historicalDownloadMessage(db, target) ?: return@transaction'
        assert source.count(old) == 2
        return source.replace(old, 'val current = historicalDownloadMessage(readableDatabase, target) ?: return@transaction', 1)
    if name == "download_db_use_cursorwindow_for_source":
        old = 'return SqliteLargeTextQuery.query(db, MESSAGE_TABLE,'
        assert old in source
        return source.replace(old, 'return db.query(MESSAGE_TABLE,', 1)
    if name == "remove_v18_table_creation_before_upgrade":
        old = "            ensureRequestEventsTable(db)\n            val columns"
        assert old in source
        return source.replace(old, "            val columns", 1)
    if name == "remove_v18_cost_column_guard":
        old = '            if ("cost_usd" !in columns) {'
        assert old in source
        return source.replace(old, "            if (false) {", 1)
    return source


def mutate_stream(source: str, name: str, target: str) -> str:
    if name == "remove_foreground_stream_reader" and target == "main":
        old = "OpenAiReceivedSseReader.read(reader, rawSseArchive)"
        assert old in source
        return source.replace(old, "BrokenSseReader.read(reader)", 1)
    if name == "remove_resume_stream_reader" and target == "resume":
        old = "OpenAiReceivedSseReader.read(reader, source as? OpenAiRawSseArchive)"
        assert old in source
        return source.replace(old, "BrokenSseReader.read(reader)", 1)
    if name == "disable_stream_terminal_break" and target == "stream":
        old = "if (frame != null && consume(frame)) { stopped = true; break }"
        assert old in source
        return source.replace(old, "if (false) break", 1)
    return source


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
    db_source = DB.read_text()
    resume_source = RESUME.read_text()
    stream_source = STREAM.read_text()
    cases = [("positive_baseline", None, True)] + [
        (name, name, False) for name in MUTATIONS
    ]

    with tempfile.TemporaryDirectory(prefix="astra-verify-mutations-", dir=ROOT.parent) as tmp:
        temp_root = Path(tmp) / "repo"
        shutil.copytree(
            ROOT,
            temp_root,
            ignore=shutil.ignore_patterns(".git", ".gradle", "build"),
        )
        temp_main = temp_root / "app/src/main/java/com/example/openaichat/MainActivity.kt"
        temp_db = temp_root / "app/src/main/java/com/example/openaichat/ChatDatabase.kt"
        temp_resume = temp_root / "app/src/main/java/com/example/openaichat/ResumableResponseStreamClient.kt"
        temp_stream = temp_root / "app/src/main/java/com/example/openaichat/BoundedResponseDecoder.kt"

        for case_name, mutation, expected_pass in cases:
            db_case = mutation and mutation.startswith(("remove_v18_", "download_db_"))
            stream_case = mutation in STREAM_MUTATIONS
            mutated_source = source if mutation is None or db_case or stream_case else mutate(source, mutation)
            temp_main.write_text(mutate_stream(mutated_source, mutation, "main") if stream_case else mutated_source)
            temp_db.write_text(db_source if mutation is None else mutate_db(db_source, mutation))
            temp_resume.write_text(mutate_stream(resume_source, mutation, "resume") if stream_case else resume_source)
            temp_stream.write_text(mutate_stream(stream_source, mutation, "stream") if stream_case else stream_source)
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

    print(f"Verifier mutation matrix: PASS ({len(MUTATIONS)} targeted negative mutations + positive control)")
    # Keep the new offline checks in the existing CI step instead of adding a workflow.
    subprocess.run([sys.executable, str(ROOT / "tools/test_verify_implementation_mutations.py")], check=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

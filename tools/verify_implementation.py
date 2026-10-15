"""Offline lexical/schema checks. This is not Kotlin compilation or an Android test."""
import os
import codecs
import re
import sqlite3
from pathlib import Path

ROOT = Path(os.environ.get("ASTRA_VERIFY_ROOT", Path(__file__).resolve().parents[1]))


def kotlin_delimiters(source):
    def comment(i):
        depth = 1
        while i < len(source):
            if source.startswith("/*", i): depth += 1; i += 2
            elif source.startswith("*/", i):
                depth -= 1; i += 2
                if depth == 0: return i
            else: i += 1
        raise AssertionError("Unclosed block comment")

    def string(i, quote, raw=False):
        while i < len(source):
            if raw and source.startswith('"""', i): return i + 3
            if not raw and source[i] == quote: return i + 1
            if not raw and source[i] == "\\": i += 2; continue
            if quote == '"' and source.startswith("${", i): i = code(i + 2, True); continue
            i += 1
        raise AssertionError("Unclosed string")

    def code(i, template=False):
        stack = []
        while i < len(source):
            if source.startswith("//", i):
                end = source.find("\n", i); i = len(source) if end < 0 else end + 1; continue
            if source.startswith("/*", i): i = comment(i + 2); continue
            if source.startswith('"""', i): i = string(i + 3, '"', True); continue
            c = source[i]
            if c in "\"'`": i = string(i + 1, c); continue
            if c in "([{": stack.append(c)
            elif c in ")]}":
                if c == "}" and template and not stack: return i + 1
                assert stack and stack.pop() == {")": "(", "]": "[", "}": "{"}[c], f"Delimiter mismatch near line {source.count(chr(10), 0, i)+1}"
            i += 1
        assert not stack and not template, "Unclosed delimiter/template"
        return i
    code(0)


files = list((ROOT / "app/src").rglob("*.kt"))
for path in files:
    try: kotlin_delimiters(path.read_text())
    except AssertionError as error: raise AssertionError(f"{path.relative_to(ROOT)}: {error}") from error

nullable_wire_fields = r'(?:text|path|image_path|image_name|image_file_id|finish_reason|content|arguments|signature)'
for path in (ROOT / 'app/src/main/java/com/example/openaichat').glob('*.kt'):
    assert not re.search(r'\.optString\s*\(\s*"' + nullable_wire_fields + r'"', path.read_text()), \
        f'{path.name}: nullable protocol/user strings must use JsonProtocol, not Android optString'

db = (ROOT / "app/src/main/java/com/example/openaichat/ChatDatabase.kt").read_text()
constants = dict(re.findall(r'private const val ([A-Z_]+) = "([^"\n]+)"', db))


def expand(statement):
    for key, value in constants.items(): statement = statement.replace("$" + key, value)
    return statement


schemas = [expand(text) for text in re.findall(r'db.execSQL\("""\s*(CREATE TABLE.*?)"""', db, re.S)]
con = sqlite3.connect(":memory:")
con.execute("PRAGMA foreign_keys=ON")
for sql in schemas:
    # Definitions in onCreate/onOpen share the same table. Verify each statement's syntax.
    con.execute(re.sub(r'CREATE TABLE (?!IF NOT EXISTS)', 'CREATE TABLE IF NOT EXISTS ', sql, count=1))
columns = {row[1] for row in con.execute("PRAGMA table_info(messages)")}
assert {"finish_reason", "openrouter_protocol", "protocol_context_state"} <= columns
con.execute("INSERT INTO chats(id,title,updated_at) VALUES('c','title',0)")
con.execute("INSERT INTO local_artifacts(path,chat_id,request_id,created_at,metadata) VALUES('p','c','r',0,'{}')")
con.execute("INSERT INTO openai_tool_checkpoints(chat_id,request_id,request_checkpoint) VALUES('c','r','{}')")
con.execute("INSERT INTO openrouter_checkpoints(chat_id,request_id,protocol) VALUES('c','r','{}')")
con.execute("INSERT INTO answer_notifications(chat_id,request_id,completed,created_at) VALUES('c','r',1,0)")
con.execute("DELETE FROM chats WHERE id='c'")
assert con.execute("SELECT COUNT(*) FROM local_artifacts").fetchone()[0] == 0
assert con.execute("SELECT COUNT(*) FROM openai_tool_checkpoints").fetchone()[0] == 0
assert con.execute("SELECT COUNT(*) FROM openrouter_checkpoints").fetchone()[0] == 0
assert con.execute("SELECT COUNT(*) FROM answer_notifications").fetchone()[0] == 0

# Execute the actual additive v20->v21 column statements on a legacy row.
legacy = sqlite3.connect(":memory:")
legacy.execute("CREATE TABLE messages(id INTEGER PRIMARY KEY, text TEXT, attachments TEXT)")
legacy.execute("INSERT INTO messages VALUES(1,'answer','[{}]')")
upgrade = db.split("if (oldVersion < 21) {", 1)[1].split("\n        }", 1)[0]
for sql in re.findall(r'db.execSQL\("(ALTER TABLE[^"\n]+)"\)', upgrade): legacy.execute(expand(sql))
assert legacy.execute("SELECT text,attachments,finish_reason,openrouter_protocol FROM messages").fetchone() == ("answer", "[{}]", None, None)

# Execute the actual v21->v22 additive migration without replacing old protocol/text.
upgrade22 = db.split("if (oldVersion < 22) {", 1)[1].split("\n        }", 1)[0]
for sql in re.findall(r'db.execSQL\("(ALTER TABLE[^"\n]+)"\)', upgrade22): legacy.execute(expand(sql))
assert legacy.execute("SELECT text,attachments,protocol_context_state FROM messages").fetchone() == ("answer", "[{}]", None)
assert '"protocol_context_state"' in db.split('MessageMetadataCodec.allKeys.filter', 1)[1].split('}.forEach', 1)[0]

# Execute the reader's actual SQL templates on host SQLite. This checks byte slicing,
# Unicode/NUL integrity, composite-key isolation and bounded projection size; it does
# not instantiate Android CursorWindow or compile the Kotlin cursor wrapper.
reader = (ROOT / "app/src/main/java/com/example/openaichat/SqliteLargeTextQuery.kt").read_text()
row_budget = int(re.search(r'ROW_BYTES = (\d+) \* 1024', reader).group(1)) * 1024
chunk_budget = int(re.search(r'CHUNK_BYTES = (\d+) \* 1024', reader).group(1)) * 1024
templates = re.findall(r'projection.add\("(CASE WHEN[^"\n]+)"\)', reader)
assert len(templates) == 2
chunk_template = re.search(r'db.rawQuery\("(SELECT substr[^"\n]+)"', reader).group(1)
large = "x" * (chunk_budget - 1) + "😀\0Философия科学\n" * 120000
other = "Подпись😀\n" * 180000
text_db = sqlite3.connect(":memory:")
text_db.execute("CREATE TABLE data(chat TEXT, request TEXT, payload TEXT, protocol TEXT, note TEXT, number INTEGER, PRIMARY KEY(chat,request))")
text_db.execute("INSERT INTO data VALUES(?,?,?,?,?,?)", ("c", "r", large, other, None, 7))
text_db.execute("INSERT INTO data VALUES(?,?,?,?,?,?)", ("c", "other", "wrong row", "wrong protocol", "next", 8))
fields = ("payload", "protocol", "note", "number")
keys = ("chat", "request")
inline = min(4096, row_budget // (len(fields) + len(keys) + 1))
projection = []
for template in templates:
    for index, field in enumerate(fields):
        projection.append(template.replace("$name", '"' + field + '"').replace("$inlineBytes", str(inline)).replace("$index", str(index)))
projection += ['"' + key + '" AS _astra_key_' + str(i) for i, key in enumerate(keys)]
rows = text_db.execute("SELECT " + ",".join(projection) + " FROM data ORDER BY request").fetchall()
row = rows[1]
assert row[:4] == (None, None, None, 7)
assert row[4:8] == (len(large.encode()), len(other.encode()), None, None)
assert rows[0][:4] == ("wrong row", "wrong protocol", "next", 8)
assert sum(len(v.encode()) if isinstance(v, str) else 8 for v in row) < row_budget
assert text_db.execute("SELECT length(payload) FROM data WHERE request='r'").fetchone()[0] < len(large)
for index, expected in enumerate((large, other)):
    sql = chunk_template.replace("$field", '"' + fields[index] + '"').replace("$table", '"data"')
    sql = sql.replace("$where", '"chat"=? AND "request"=?')
    length = row[len(fields) + index]
    offset = 0
    decoder = codecs.getincrementaldecoder("utf-8")("strict")
    parts = []
    while offset < length:
        count = min(chunk_budget, length - offset)
        chunk = text_db.execute(sql, (str(offset + 1), str(count), *row[len(fields) * 2:])).fetchone()[0]
        assert isinstance(chunk, bytes) and len(chunk) == count <= chunk_budget
        parts.append(decoder.decode(chunk)); offset += len(chunk)
    parts.append(decoder.decode(b"", final=True))
    assert "".join(parts) == expected
# Regression guards for the previously reproduced Android foreground loss:
# provider HTTP x-request-id must not become the SQLite ownership request_id.
main_activity = (ROOT / "app/src/main/java/com/example/openaichat/MainActivity.kt").read_text()
parsed = re.search(r'val parsedModel = AstraResponseModel\.fromResponse\(\s*effectiveResponse,\s*finalText,\s*(\w+)\s*,', main_activity)
assert parsed and parsed.group(1) == "localRequestId", "Foreground assistant must use its local active request ID"
assert 'val effectiveResponse = OpenAiEffectiveResponse.assemble(completedResponse, responseState)' in main_activity, "Foreground model must include all received typed output before extraction"
assert 'JsonProtocol.string(messages[0], "request_id") != expectedRequestId) return false' in db, "Mismatched terminal assistant must not consume active ownership"
assert '"Готово. Ответ не содержит текста."' not in main_activity, "UI status must not enter canonical response"
assert '"Ответ завершился без полученного текста: "' not in main_activity, "Exception text must stay in metadata"
worker = (ROOT / "app/src/main/java/com/example/openaichat/BackgroundResponseWorker.kt").read_text()
# The old lexical guard required naive full.append(delta), which silently
# scrambled interleaved output after WorkManager resume. Require the shared,
# durable indexed reducer instead of reintroducing the known P1 regression.
assert 'ResponseEventReducer.reduce(responseState, event).state' in worker
assert 'ResponseIndexedCheckpoint.restore(current)' in worker
assert 'indexedCheckpoint = ResponseIndexedCheckpoint.serialize(responseState)' in worker
assert 'full.append' not in worker
reducer = (ROOT / "app/src/main/java/com/example/openaichat/ResponseEventReducer.kt").read_text()
assert 'JsonProtocol.string(event, "delta")' in reducer and 'JsonProtocol.nonBlank(it, "message")' in reducer
xlsx = (ROOT / "app/src/main/java/com/example/openaichat/StructuredDocumentExtractor.kt").read_text()
assert 'cell?.put("formula", "")' in xlsx and '"formula_si"' in xlsx

print(f"Implementation lexical/schema checks: PASS ({len(files)} Kotlin files; SQLite definitions, v20->v21->v22 additive columns, FK cleanup, long UTF-8/NUL byte slices). Android compilation/tests remain separate.")

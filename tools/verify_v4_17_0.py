from pathlib import Path
import sqlite3
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/example/openaichat/MainActivity.kt"
DB = ROOT / "app/src/main/java/com/example/openaichat/ChatDatabase.kt"
MODEL = ROOT / "app/src/main/java/com/example/openaichat/AstraResponseModel.kt"
RENDERER = ROOT / "app/src/main/java/com/example/openaichat/RichTextRenderer.kt"
GRADLE = ROOT / "app/build.gradle.kts"
WF = ROOT / ".github/workflows/build.yml"

main = MAIN.read_text()
db = DB.read_text()
model = MODEL.read_text()
renderer = RENDERER.read_text()
gradle = GRADLE.read_text()
workflow = WF.read_text()

assert 'versionName = "4.17.0"' in gradle
assert 'implementation("androidx.recyclerview:recyclerview:1.4.0")' in gradle
assert 'implementation("androidx.core:core-ktx:1.15.0")' in gradle
assert 'implementation("androidx.work:work-runtime-ktx:2.11.2")' in gradle
assert 'implementation("androidx.activity:activity-ktx:1.10.1")' in gradle
assert 'class MainActivity : ComponentActivity()' in main
assert 'registerForActivityResult(ActivityResultContracts.StartActivityForResult())' in main
assert 'startActivityForResult(' not in main
assert 'override fun onActivityResult' not in main
assert 'versionCode = 64' in gradle
assert 'buildConfig = true' in gradle
assert 'container_file_citation' in main
assert 'file_citation' in main
assert 'url_citation' in main
assert 'put("type","web_search")' in main
assert 'configuration_update' in main
assert 'if (configUpdateEffort == null)' in main
assert 'background' in main
assert 'pollBackgroundResponse' in main
assert 'response.reasoning_summary_text.delta' in main
assert 'reasoningSummary' in model
assert 'AstraResponseModel.fromResponse' in main
assert 'AstraResponseModel.fromChatJson' in main
assert 'URLSpan' in renderer
assert 'LinkMovementMethod' in main
assert 'file_paths' in main and 'file_mimes' in main
assert 'image_name' in main and 'image_mime' in main
assert 'showTextPreview' in main and 'showPdfPreview' in main
assert 'downloadUserImageCopy' in main
assert 'showPendingLocalPreview' in main
assert 'addCompactTextPreview' in main
assert 'addCompactImagePreview' in main
assert 'addCompactPdfPreview' in main
assert 'updateMessageFileLocalState' in main
assert 'updateUserFileLocalPath' in db
assert 'updateUserImageLocalPath' in db
assert 'import okio.buffer' in main
assert 'while(message.optJSONArray("file_paths")' not in main
assert 'ProgressRequestBody' in main
assert 'preparePendingZipsThenSend' in main
assert 'extractZipForSend' in main
assert 'handleZipAttachment' not in main
assert '25_000_000L' in main
assert '200L*1024L*1024L' in main
assert 'maxEntries=100' in main
assert 'web_search_preview' not in main
assert 'listChatSummaries()' in main
assert 'BuildConfig.APPLICATION_ID' in main
assert 'listLocalFiles' in db
assert 'generated_images' in db
assert 'DB_VERSION = 15' in db
assert 'idx_messages_chat_request' in db
assert 'ALTER TABLE $MESSAGE_TABLE ADD COLUMN image_name TEXT' in db
assert 'ALTER TABLE $MESSAGE_TABLE ADD COLUMN image_mime TEXT' in db
assert 'ALTER TABLE $MESSAGE_TABLE ADD COLUMN image_size INTEGER NOT NULL DEFAULT 0' in db

for p in [ROOT/'app/src/main/AndroidManifest.xml', *ROOT.glob('app/src/main/res/**/*.xml')]:
    ET.parse(p)

# Representative SQLite schema sanity check.
con = sqlite3.connect(':memory:')
con.execute('PRAGMA foreign_keys=ON')
con.execute('''CREATE TABLE chats (
    id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, response_id TEXT,
    baseline_effort TEXT, updated_at INTEGER NOT NULL,
    active_response_id TEXT, active_request_id TEXT, active_text TEXT,
    active_status TEXT, active_started_at INTEGER, active_effort_pref TEXT,
    active_effective_effort TEXT, active_auto_selected INTEGER NOT NULL DEFAULT 0,
    active_prompt_chars INTEGER NOT NULL DEFAULT 0, active_attachments INTEGER NOT NULL DEFAULT 0,
    active_background_enabled INTEGER NOT NULL DEFAULT 1,
    active_last_sequence_number INTEGER NOT NULL DEFAULT -1
)''')
con.execute('''CREATE TABLE messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id TEXT NOT NULL, position INTEGER NOT NULL,
    role TEXT NOT NULL, text TEXT NOT NULL, response_id TEXT, request_id TEXT,
    file_ids TEXT, file_names TEXT, file_sizes TEXT, file_paths TEXT, file_mimes TEXT,
    image_path TEXT, image_file_id TEXT, image_name TEXT, image_mime TEXT,
    image_size INTEGER NOT NULL DEFAULT 0, annotations TEXT, generated_images TEXT,
    tools TEXT, status TEXT, feedback INTEGER,
    FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE
)''')
con.execute("INSERT INTO chats(id,title,updated_at) VALUES('chat-1','x',0)")
con.execute("INSERT INTO messages(chat_id,position,role,text) VALUES('chat-1',0,'user','hello')")
con.execute("DELETE FROM chats WHERE id='chat-1'")
assert con.execute('SELECT COUNT(*) FROM messages').fetchone()[0] == 0

cols = {r[1] for r in con.execute('PRAGMA table_info(messages)')}
assert {'file_paths','file_mimes','image_name','image_mime','image_size','generated_images'} <= cols

# v14 -> v15 migration must preserve partial response state and initialize the cursor to -1.
legacy = sqlite3.connect(':memory:')
legacy.execute('CREATE TABLE chats (id TEXT PRIMARY KEY, active_text TEXT, active_last_sequence_number INTEGER)')
legacy.execute("INSERT INTO chats(id, active_text, active_last_sequence_number) VALUES('c1','partial',NULL)")
legacy.execute('ALTER TABLE chats ADD COLUMN migrated_cursor INTEGER NOT NULL DEFAULT -1')
text_value, cursor_value = legacy.execute("SELECT active_text, migrated_cursor FROM chats WHERE id='c1'").fetchone()
assert text_value == 'partial'
assert cursor_value == -1

# Kotlin delimiter sanity (strings/comments skipped).
def delimiter_balance(src: str):
    stack=[]; i=0; state='code'
    while i < len(src):
        c=src[i]; n=src[i+1] if i+1 < len(src) else ''
        if state=='line':
            if c=='\n': state='code'
            i+=1; continue
        if state=='block':
            if c=='*' and n=='/': state='code'; i+=2
            else: i+=1
            continue
        if state=='string':
            if c=='\\': i+=2; continue
            if c=='"': state='code'
            i+=1; continue
        if state=='char':
            if c=='\\': i+=2; continue
            if c=="'": state='code'
            i+=1; continue
        if c=='/' and n=='/': state='line'; i+=2; continue
        if c=='/' and n=='*': state='block'; i+=2; continue
        if c=='"': state='string'; i+=1; continue
        if c=="'": state='char'; i+=1; continue
        if c in '({[': stack.append(c)
        elif c in ')}]':
            assert stack, 'unexpected delimiter'
            expected={')':'(', '}':'{', ']':'['}[c]
            assert stack[-1]==expected, f'delimiter mismatch: expected {expected}, got {c}'
            stack.pop()
        i+=1
    assert state=='code', f'unclosed lexer state: {state}'
    assert not stack, f'unclosed delimiters: {stack[-10:]}'

for p in [MAIN,DB,MODEL,RENDERER]:
    delimiter_balance(p.read_text())

for action in ['actions/checkout@v7','actions/setup-java@v6','android-actions/setup-android@v4','gradle/actions/setup-gradle@v6','actions/upload-artifact@v7']:
    assert action in workflow
assert 'gradle-version: \'8.9\'' in workflow
assert 'gradle --no-daemon --stacktrace test lintDebug assembleDebug' in workflow
assert 'permissions:' in workflow and 'contents: read' in workflow
assert 'expectedRequestId' in main
assert 'upsertCurrentChatIfActiveRequest' in db
assert 'backgroundEnabled = backgroundEnabled' in main
assert 'active_background_enabled' in db
assert 'background_enabled' in main
assert 'isNearLatest' in main
assert 'ChatScrollPolicy.shouldScrollToLatest' in main
assert (ROOT / 'app/src/main/java/com/example/openaichat/ChatScrollPolicy.kt').exists()
assert (ROOT / 'app/src/test/java/com/example/openaichat/ChatScrollPolicyTest.kt').exists()
assert (ROOT / 'app/src/main/java/com/example/openaichat/ResumableResponseRequest.kt').exists()
assert (ROOT / 'app/src/test/java/com/example/openaichat/ResumableResponseRequestTest.kt').exists()
assert 'jitteredDelayMs' in (ROOT / 'app/src/main/java/com/example/openaichat/BackgroundRecoveryPolicy.kt').read_text()
assert 'jitteredDelayMs' in (ROOT / 'app/src/main/java/com/example/openaichat/BackgroundResponseWorker.kt').read_text()
assert 'concurrency:' in workflow
assert 'activityVisible' in main
assert 'isFinishing)' in main
assert '!finishing' in (ROOT / 'app/src/main/java/com/example/openaichat/ActiveResponseState.kt').read_text()
assert 'ViewCompat.setOnApplyWindowInsetsListener' in main
assert 'WindowInsetsCompat.Type.systemBars()' in main
assert 'ResumableResponseStreamClient' in main
adapter = (ROOT / 'app/src/main/java/com/example/openaichat/ChatMessageAdapter.kt').read_text()
assert 'AsyncListDiffer' in adapter
assert 'itemById' in adapter
assert 'itemIndexById' in adapter
assert 'displayPositionById' in adapter
assert 'items.firstOrNull' not in adapter
assert 'differ.currentList.indexOfFirst' not in adapter
assert 'startingAfter = cursor' in main
assert 'last_sequence_number' in main
assert (ROOT / 'app/src/main/java/com/example/openaichat/ResumableResponseStreamClient.kt').exists()
assert (ROOT / 'app/src/main/java/com/example/openaichat/ActiveResponseState.kt').exists()
assert (ROOT / 'app/src/test/java/com/example/openaichat/ActiveResponseStateTest.kt').exists()
assert 'ActiveResponseStateStore' in main
assert 'activeResponseState.state.isCurrent' in main
assert 'activeResponseState.markUserStop()' in main
assert 'streamClient.stream' in (ROOT / 'app/src/main/java/com/example/openaichat/BackgroundResponseWorker.kt').read_text()
assert 'starting_after' in (ROOT / 'app/src/main/java/com/example/openaichat/ResumableResponseStreamClient.kt').read_text()
assert 'ResponseStreamSequenceGapException' in (ROOT / 'app/src/main/java/com/example/openaichat/ResumableResponseStreamClient.kt').read_text()
assert 'background_resume_sequence_gap' in main
assert 'worker_sequence_gap' in (ROOT / 'app/src/main/java/com/example/openaichat/BackgroundResponseWorker.kt').read_text()
assert 'systemWindowInsetLeft' not in main
assert 'consumeSystemWindowInsets()' not in main
assert 'pollingClient' in main
assert 'SseEventParser' in main
assert 'pollingClient.newCall(req)' in main
assert 'ApiHttpException' in main or (ROOT / 'app/src/main/java/com/example/openaichat/ApiHttpException.kt').exists()
assert 'sse_event_sequence_gap' in main
assert 'background_resume_rate_limited' in main
assert 'listLocalFilePaths' in db
assert 'deleteOwnedLocalFile' in main
assert (ROOT / 'app/src/test/java/com/example/openaichat/SseEventParserTest.kt').exists()
assert 'testImplementation("junit:junit:4.13.2")' in gradle
assert 'androidx.work:work-runtime-ktx:2.11.2' in gradle
assert 'BackgroundResponseWorkScheduler' in main
assert 'BackgroundResponseWorkScheduler.cancelAll(applicationContext)' in main
assert 'BackgroundResponseWorkScheduler.rescheduleAll(applicationContext, active)' in main
assert 'BackgroundResponseWorker' in workflow or (ROOT / 'app/src/main/java/com/example/openaichat/BackgroundResponseWorker.kt').exists()
assert 'BackgroundRecoveryPolicy' in (ROOT / 'app/src/main/java/com/example/openaichat/BackgroundRecoveryPolicy.kt').read_text()
assert 'FOREGROUND_SERVICE_DATA_SYNC' in (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
assert 'androidx.work.impl.foreground.SystemForegroundService' in (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
assert 'foregroundServiceType="dataSync"' in (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
assert (ROOT / 'app/src/test/java/com/example/openaichat/BackgroundRecoveryPolicyTest.kt').exists()
assert 'val success=state=="completed"' in main
assert 'idx_effort_metrics_chat_request' in db
assert 'CREATE UNIQUE INDEX' in db
assert 'DELETE FROM $METRICS_TABLE' in db
assert 'GROUP BY chat_id, request_id' in db
assert 'activityVisible' in main
assert 'activeResumeChats.clear()' in main
assert 'setForeignKeyConstraintsEnabled(true)' in db
assert not list(ROOT.glob('*-source.zip')), 'source archive must not remain in the repository'
assert 'tools/verify_v4_17_0.py' in workflow
file_paths_xml = (ROOT / 'app/src/main/res/xml/file_paths.xml').read_text()
assert 'name="attachments" path="attachments/"' in file_paths_xml
assert 'cache-path name="cache" path="."' not in file_paths_xml

print('Astra v4.17.0 static verification: PASS')

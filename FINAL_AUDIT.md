# FINAL AUDIT — Astra v4.15.1

## 1. Build failure from the supplied GitHub Actions log

The failed run reached `:app:compileDebugKotlin` and stopped on six source errors:
- `ChatDatabase.kt:105`: unresolved `db` in the migration transaction.
- `ChatDatabase.kt:177-178`: unresolved `delete` receiver in the delete transaction.
- `MainActivity.kt:97, 1225, 1248`: unresolved `BuildConfig`.

The Node 20 deprecation messages are warnings from GitHub Actions, not the cause of the failed build.

## 2. Fixes in v4.14.1

### Build / CI
- Enabled `buildFeatures.buildConfig = true` for modern Android Gradle Plugin.
- Fixed both SQLite transaction lambda receiver mistakes.
- Replaced timestamp-only chat IDs with timestamp + nanoTime IDs to reduce collisions.
- Canonical CI file is `.github/workflows/build.yml` because the supplied log shows that exact workflow is executed.
- Workflow uses current action majors: checkout v7, setup-java v6, setup-android v4, setup-gradle v6, upload-artifact v7.
- Workflow installs Android SDK 35, build-tools 35.0.0 and Gradle 8.9, then runs static verification before compiling.
- Source archive detection refuses to guess when multiple `*-source.zip` files are present.

### SQLite history
- Chat history is stored in SQLite instead of one large SharedPreferences JSON blob.
- Added a lightweight `listChatSummaries()` query so the chat picker does not load every message just to show chat titles.
- DB schema is version 7 and stores file sizes and generated-image metadata.
- Fixed the old migration path that could attempt to add `request_id` twice.
- Legacy `chat_store` data is still migrated once on first start.

### Responses / rendering
- Final Responses are represented by `AstraResponseModel` and `RichTextRenderer`.
- `url_citation`, `container_file_citation` and `file_citation` are preserved as structured annotations.
- Normal Markdown links and bare HTTP(S) links are clickable.
- File citations are not treated as fake `sandbox:/` URLs.

### Generated files
- `container_file_citation` uses the container file content endpoint and streams bytes to local storage.
- File Search `file_citation` can be downloaded explicitly through the Files API content endpoint.
- Generated-file cards persist state: DOWNLOADING / READY / FAILED / EXPIRED.
- READY files support Open / Save / Share through `FileProvider`/Storage Access Framework.
- Assistant messages are persisted before file download/rendering begins, so asynchronous file-state updates always have a DB row to update.

### Generated images
- `image_generation_call.result` is extracted as a transient Base64 value.
- The result is size-limited, decoded once, stored as a local PNG, and the Base64 payload is not written to chat history.
- Local images are restored with sampled thumbnails and Open / Save / Share actions.

### ZIP safety
- Existing v4.14 already had a 25 MB per-entry limit; this was not treated as missing.
- Entry extraction uses a 64 KiB streaming buffer.
- Archive budget: 200 MB uncompressed.
- Maximum entries: 100.
- Unsupported entries are also drained through streaming so they count against the archive budget.

### Input file safety
- Each normal Responses `input_file` is checked against a 50 MB client-side limit while copying.
- Uploaded file sizes are remembered and a 50 MB total input-file budget is checked before a request when sizes are known.
- ZIP uploads remember the sizes of successfully extracted/uploaded files too.

### Async reliability
- Requests are tied to both chat ID and generation token.
- Stale callbacks are ignored after switching, deleting or creating chats.
- Deleting the active chat no longer routes through the persistence path that could resurrect it.
- Partial incomplete responses are persisted distinctly from successful completions.

### Reasoning / Astra configuration
- `gpt-6-astra` and supported reasoning levels remain intact.
- Standard-mode reasoning changes use `configuration_update` while keeping request-level effort stable.
- Automatic compaction is deliberately omitted on the same request that sends `configuration_update`, because the current Astra guidance disallows combining those mechanisms.
- After a successful response, the current effective effort becomes the new conversation baseline, avoiding redundant configuration updates.

## 3. Previously implemented and re-checked

The supplied v4.14 source already contained these features; they were verified and retained rather than duplicated:
- real `container_file_citation` download;
- clickable `url_citation` sources;
- current-chat deletion fix;
- export success check;
- generation-token/chat-ID race protection;
- `web_search` instead of `web_search_preview`;
- ZIP per-entry protection and streaming extraction;
- diagnostics with request/response/container/file IDs;
- Auto-effort telemetry and explicit user feedback calibration;
- persistent filename handling in normal multipart uploads;
- clickable ordinary Markdown/bare HTTP(S) links.

## 4. Intentionally not over-engineered in this patch

The full event-by-event state-machine renderer for every possible Responses event remains a larger refactor. The final response path already uses a structured model, but the live token stream still keeps a simple text buffer for low-latency display. This is a deliberate separation so the build/reliability fixes do not turn into an uncontrolled rewrite.

Computer Use, MCP, async tool executors, Realtime voice and a full Structured Outputs editor remain separate features because they require more than just parsing a tool event.

## 5. Verification

`tools/verify_v4_14.py` performs:
- required-source marker checks;
- version/buildConfig checks;
- file citation/generated image checks;
- ordering check that persistence occurs before file/image rendering;
- SQLite schema creation check with the real sqlite3 engine;
- XML parsing;
- basic Kotlin delimiter/string sanity;
- workflow action/version checks.

Result in this environment: `Astra v4.14.1 static verification: PASS`.

A full Android APK build was not executed in this environment because the project has no Gradle wrapper and no usable Android SDK/Gradle installation was available locally. The next GitHub Actions run is therefore the authoritative APK compile test.


## 6. v4.15.1 reliability / UX changes
- Tool activity events are guarded by chat ID + generation so an old request cannot update the current chat's activity strip after switching.
- Background Responses are created with `background=true` when the setting is enabled; local stream cancellation on Activity destruction does not call the cancel endpoint. The app stores the response ID and automatically polls/reconciles it after restart or when returning to the chat.
- If the live stream disconnects after `response.created`, the app leaves the active response durable and continues it through background polling instead of treating the disconnect as a failed request.
- Active response chats are treated as busy after restoration so the user cannot start a second request into the same chat accidentally.
- Composer drafts are saved per chat; attachment upload callbacks are scoped to the chat that initiated them.
- `send` is no longer re-enabled by an upload finishing if a response is still running.
- Added a non-blocking warning for unusually long existing chat history.

## 7. Verification status
`tools/verify_v4_14.py` (compatibility filename) passes the v4.15.1 static checks. A full Android build still requires the GitHub Actions environment because this container has no usable Android SDK/Gradle installation.

## 8. v4.15.1
- Fixed the two compile-time issues reported by the next GitHub Actions run (mutable list and Long token counters).
- Added API reasoning-summary capture and live streaming display.

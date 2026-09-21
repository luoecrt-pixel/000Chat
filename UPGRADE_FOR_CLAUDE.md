# Astra v4.16.2 handoff for Claude

This source is the GPT-maintained increment from v4.14 onward. v4.16.2 is a corrective/hardening release after CI feedback on v4.16.0.

## New since v4.15.1 / v4.16.0

### Build fixes
- `import okio.buffer` added for Okio 3.x.
- Fixed Kotlin nested `it` shadowing in pending image preview click handling.
- Usage token values consistently use `Long`, including cost calculation and DB telemetry.

### Composer / attachment UX
- Selected attachments appear immediately after picker selection.
- Visible progress covers local preparation and network upload.
- Images show thumbnails even when selected through the generic file picker.
- Image thumbnails can be opened full size.
- Text-like files (txt/json/csv/code/markdown/etc.) show a compact readable preview in the composer and message cards.
- PDFs show a first-page preview in the composer/message cards.
- Generic image files selected via the paperclip path also get an immediate thumbnail.
- `pending_uploads` remain chat-scoped and survive Activity recreation/restart when the persisted URI grant remains available.
- ZIPs stay as ZIPs in the composer and are unpacked only after Send.
- Cancelled uploads remove progress state and temporary local copies.

### Sent user files
- User message file download now persists the new local path back into the correct SQLite message row using `chat_id + request_id + index`.
- Fixed a real infinite-loop bug in the old user-file download path array handling.
- `Скачать и открыть` now downloads a remote-only sent file and immediately opens an in-app preview where supported.
- User image download likewise persists its local copy and can immediately preview it.
- Sent attachments keep local preview/open metadata across restart.

### Generated Astra files
- Existing valid local copies are preferred even if a stale annotation says EXPIRED/PENDING.
- Added a legacy filesystem fallback for generated files that existed on-device before their file metadata was persisted by the newer DB model.
- Generated assets remain embedded directly under the assistant message that produced them.
- Generated text/JSON/PDF/image files have in-app preview actions.

### Reliability / safety
- Added a `chat_id + request_id` DB index for fast local-path repair.
- Full PDF preview bitmap size is capped to reduce oversized allocations.
- Local Astra file library no longer shows a duplicate generic `Открыть` action.

## Existing v4.14-v4.15.1 functionality to preserve
- container_file_citation and file_citation real downloads
- url_citation and normal HTTP(S) links
- web_search
- SQLite migration from legacy SharedPreferences chat history
- background Responses with automatic polling/recovery after restart when a response_id is known
- per-chat draft and pending attachment persistence
- live tool activity
- usage/cost dashboard
- Auto-effort metrics/feedback calibration
- reasoning summary UI (API-provided summary, not raw private chain-of-thought)
- gpt-6-astra with configuration_update reasoning handling
- race protection and delete-current-chat fix
- safe ZIP limits: 25 MB entry / 200 MB total uncompressed / 100 entries

## CI
Canonical workflow: `.github/workflows/build.yml`.
It invokes `tools/verify_v4_16_2.py`.
Expected environment:
- JDK 17
- Android SDK 35
- build-tools 35.0.0
- Gradle 8.9

## Verification
`tools/verify_v4_16_2.py` currently passes static/XML/SQLite checks.
Final proof remains GitHub Actions `assembleDebug` plus runtime testing on Android.

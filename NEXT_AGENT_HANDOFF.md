# NEXT_AGENT_HANDOFF — 2026-09-22

## Current state

Canonical repository: `luoecrt-pixel/OpenAIApiChat`

Default branch: `main`

Known-good HEAD after this engineering pass:
`433d27ac74e0e20405facc1014e2d152440dbaef`

App:
- versionName: `4.17.0`
- versionCode: `64`
- compileSdk / targetSdk: `35`
- minSdk: `26`
- DB schema: `v15`
- MainActivity: ~4.5k lines

Current dependencies:
- AGP 8.7.3
- Kotlin Gradle Plugin 2.0.21
- Activity KTX 1.10.1
- WorkManager 2.11.2
- RecyclerView 1.4.0
- Core KTX 1.15.0
- OkHttp 4.12.0
- JUnit 4.13.2
- JVM test dependency: org.json:json:20250517

Important compatibility finding:
- Activity KTX 1.12.x/1.13.x was tested and rejected by CI because its AAR metadata requires compileSdk 36+.
- Core KTX 1.17.x/1.18.x/1.19.x was tested and rejected by CI because its AAR metadata requires compileSdk 36+ / 37.
- RecyclerView 1.4.0 passed full CI on compileSdk 35.
- Do not upgrade AGP/Kotlin/compileSdk as part of small fixes; make that a separate migration project.

## Merged engineering work

### PR #10 — resumable background Responses
Merged and green.

Implemented:
- persisted `active_last_sequence_number`;
- `ResumableResponseStreamClient`;
- `ResumableResponseRequest` with `starting_after`;
- Activity and Worker share the same resumable transport;
- sequence gaps fail fast and retry from the last durable cursor;
- bounded backoff + jitter;
- reconnecting calls are cancelled on Stop/Delete/Destroy;
- partial text and cursor checkpoint together;
- legacy Activity-side recovery polling path removed;
- request URL and v14 -> v15 migration contracts tested.

### PR #11 — AsyncListDiffer
Merged and green.

`ChatMessageAdapter`:
- uses `AsyncListDiffer` for structural reconciliation;
- bulk history diff runs off the UI thread;
- streaming text/reasoning updates remain payload-style and do not start async diff per token;
- authoritative mutable model is separated from the differ snapshot.

### PR #12 — modern Activity Result API
Merged and green.

- `MainActivity : ComponentActivity()`;
- `ActivityResultLauncher`;
- removed `startActivityForResult()`;
- removed `onActivityResult()`;
- existing request-code routing preserved.

### PR #13 — RecyclerView 1.4.0
Merged and green.

- RecyclerView upgraded `1.3.2 -> 1.4.0`;
- Core KTX and WorkManager intentionally unchanged.

### PR #15 — ActiveResponseState
Already merged before the last pass.

`ActiveResponseState.kt` / `ActiveResponseStateStore` centralize:
- generation;
- response ID;
- assistant item ID;
- user-stop generation.

### PR #17 — release/AAB CI
Merged and green.

Added `.github/workflows/release.yml`:
- push to main;
- pull request to main;
- manual workflow dispatch;
- `test lintRelease assembleRelease bundleRelease`;
- uploads release APK and AAB artifacts.

This is verification only. Production signing/publishing is intentionally separate.

### PR #18 — O(1) ChatMessageAdapter lookups
Merged and green.

Added:
- `itemById`;
- `itemIndexById`;
- `displayPositionById`;
- `AsyncListDiffer.addListListener` to maintain display positions.

Hot paths no longer use:
- `items.firstOrNull { ... }`;
- `differ.currentList.indexOfFirst { ... }`.

Post-merge debug and release CI both passed.

## Current CI

### Debug
File: `.github/workflows/build.yml`

Environment:
- ubuntu-24.04
- JDK 17
- Android SDK 35 / build-tools 35.0.0
- Gradle 8.9

Command:
`gradle --no-daemon --stacktrace test lintDebug assembleDebug`

Artifact:
`app/build/outputs/apk/debug/app-debug.apk`

### Release
File: `.github/workflows/release.yml`

Command:
`gradle --no-daemon --stacktrace test lintRelease assembleRelease bundleRelease`

Artifacts:
- `app/build/outputs/apk/release/*.apk`
- `app/build/outputs/bundle/release/*.aab`

The latest main post-merge runs after PR #18 are both green.

## Persistence / database

DB version: `15`

Active response fields:
- `active_request_id`
- `active_response_id`
- `active_text`
- `active_status`
- `active_background_enabled`
- `active_last_sequence_number`

Message persistence:
- `messages.content_hash` from `ChatMessageFingerprint`;
- request ID matching before position;
- unchanged content avoids unnecessary row updates;
- stale rows from middle deletion are removed.

Critical transaction:
`upsertCurrentChatIfActiveRequest()` checks persisted `active_request_id` and persists completed state atomically.

## Current response/recovery architecture

Already hardened:
- generation-based stale callback protection;
- request-aware DB writes;
- WorkManager for process-death recovery;
- persisted partial response text;
- resumable SSE with durable cursor;
- gap detection + retry;
- bounded retry/jitter;
- HTTP policy for auth/gone/rate-limit/server/network cases.

Caution:
- streaming text may differ from message-backed `contentSignature` until finalization;
- do not assume fingerprint state is recalculated per streaming token.

## Current RecyclerView architecture

`ChatMessageAdapter` has:
1. authoritative mutable model for streaming;
2. `AsyncListDiffer.currentList` for structure.

Streaming:
- text => payload;
- reasoning summary => targeted notify;
- no async diff per token.

Indexes:
- ID -> item;
- ID -> model index;
- ID -> display position.

Potential future edge:
- add duplicate-ID assertions in dev/test paths;
- add model-level tests for streaming -> final item replacement and pending async diff behavior.

## Existing tests

JVM tests include:
- `ActiveResponseStateTest`
- `BackgroundRecoveryPolicyTest`
- `ChatScrollPolicyTest`
- `ChatMessageFingerprintTest`
- `ResumableResponseRequestTest`
- `SseEventParserTest`

Static verifier:
- `tools/verify_v4_17_0.py`

## Highest-value remaining work

### 1. Race-condition tests
Add deterministic tests for:
- stop before `response.created`;
- late `response.created` after stop/delete;
- stale Worker finalization after stop/delete/new request;
- chat switch while callbacks arrive;
- orphan active row without response ID;
- stale request ID seen by Worker;
- Worker partial-text preservation;
- DB v13 -> v14 and v14 -> v15 migrations;
- deletion cleanup of local files;
- incremental upsert request-ID-vs-position behavior.

### 2. Response controller extraction
Next architecture step:
- extract stream/recovery lifecycle around generation, response ID, completion state, retry and DB checkpoints;
- keep Activity as lifecycle/composition glue;
- preserve existing request-aware transaction boundaries.

### 3. Attachment/upload controller
Activity still owns:
- URI/file/image upload;
- ZIP extraction;
- upload progress;
- composer persistence.

Extract this after response lifecycle.

### 4. Diagnostics/security review
Review:
- diagnostics retention/clear behavior;
- no API keys/message bodies in diagnostics;
- orphan local file cleanup;
- FileProvider least-privilege paths.

### 5. Release hardening
Current release CI validates packaging but does not intentionally enable shrinker/minification.

Future separate PR:
- explicit release build type;
- review and enable minification;
- verify renderer/reflection/file handling under shrinker;
- keep signing/publishing separate.

### 6. AGP / Kotlin / compileSdk migration
Separate project. Current baseline is:
- AGP 8.7.3;
- Kotlin 2.0.21;
- compileSdk 35.

The CI evidence above shows that newer AndroidX Core/Activity versions require a newer compileSdk. Upgrade these together, not piecemeal.

## Workflow rules for the next agent

1. Start from actual `main` HEAD.
2. Inspect current source before editing.
3. After mechanical edits, search for stale symbols/imports/helpers.
4. Treat static verifier and real Gradle CI as separate checks.
5. Never call a run green while it is `in_progress`.
6. After every merge, verify post-merge debug and release workflows.
7. Keep architecture changes in small PRs.

## Known-good state

- main HEAD: `433d27ac74e0e20405facc1014e2d152440dbaef`
- version: `4.17.0`
- DB: `v15`
- debug CI: success
- release CI: success
- release APK/AAB: successfully produced
- PRs #10, #11, #12, #13, #17, #18 merged.

Next work should start from this exact `main`.

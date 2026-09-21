# NEXT_AGENT_HANDOFF — 2026-09-21

## 0. Repository / continuity

Canonical GitHub repository name is now:

`luoecrt-pixel/OpenAIApiChat`

The historical URL/repository name `luoecrt-pixel/Mychatgpt` redirects to the canonical repository. Use `OpenAIApiChat` in future GitHub connector calls.

Current default branch: `main`.

Current app:
- namespace/applicationId: `com.example.openaichat`
- versionName: `4.16.3`
- versionCode: `63`
- compileSdk / targetSdk: `35`
- minSdk: `26`

Current main before PR #9:
- main head: `4fedade217a3c2888d315c44bdfd9724f27770b8`
- GitHub Actions Build Android APK run #112: **success**
- PR #9 (`fix/recovery-state-and-chat-scroll`) contains the current 4.16.3 reliability patch; its latest checks are still running.

Do not assume an earlier PR branch is still the correct base. Start new work from `main`.

---

## 1. What was accomplished in this engineering session

The session continued the deep reliability/performance audit of the Android client and merged two PRs.

### PR #6 — incremental persistence + async thumbnails

PR: `perf: make chat persistence incremental and thumbnails async`

Merged:
- PR #6 merged at 2026-09-21 15:40 UTC.
- Final branch head: `dfd13b85d92d1889e1d006536a1bf797c5e97e69`.
- Main CI run #106: **success**.

Implemented:
1. `ChatMessageFingerprint.kt`
   - SHA-256 fingerprint over persisted message fields.
   - Used to skip unnecessary SQLite writes.
2. `ChatMessageFingerprintTest.kt`
   - same content => same fingerprint;
   - changed persisted content => different fingerprint;
   - omitted optional array vs empty array => different fingerprint.
3. `ChatDatabase`
   - schema version `12 -> 13`;
   - added `messages.content_hash TEXT`;
   - `upsertCurrentChat()` became incremental;
   - matches existing rows by request ID first, then position;
   - skips SQL UPDATE when content/position are unchanged;
   - deletes all rows not reused, including stale rows left by deletion from the middle of history;
   - historical rows with null hash get updated once.
4. MainActivity image performance
   - 12 MB `LruCache` for thumbnails;
   - image decoding moved off UI thread;
   - full image preview decoding moved off UI thread;
   - generated images, user attachments and local image cards use async thumbnail loading;
   - removed unnecessary synchronous thumbnail decoding in send/restore paths.
5. CI verifier updated for DB v13 and fingerprint/thumbnail invariants.

### PR #6 real CI failure and fix

The first failing CI run was #104.

Failure was not Android compilation. All three new fingerprint unit tests threw `RuntimeException` under JVM tests because Android's `org.json` stub implementation was being used.

Fix:
`app/build.gradle.kts` now has:

`testImplementation("org.json:json:20250517")`

After the fix, CI run #105 passed and PR #6 was merged. Keep this dependency: fingerprint tests are JVM tests and need a real JSON implementation.

---

## 2. PR #7 — RecyclerView chat history

PR: `perf: migrate chat history to RecyclerView`

Merged:
- PR #7 merged at 2026-09-21 15:59 UTC.
- Final fix commit: `9a188351b740832aec36c2c1ef7d24955de110b0`.
- Merge/main result is `3ad779c2e88aa4cb424dd0ca3725a3dbb12b12e6`.
- Main CI run #109: **success**.

Implemented:
1. Added `ChatMessageAdapter.kt`.
2. Replaced main chat history container:
   - old: `ScrollView + LinearLayout`;
   - new: `RecyclerView + LinearLayoutManager`.
3. Adapter uses `DiffUtil` for bulk history changes.
4. Streaming text updates use a `"text"` payload instead of rebuilding the full row.
5. Final response replaces the temporary streaming row with the full structured message item.
6. Restore/edit/stop/delete operations now mutate adapter items rather than manually manipulating child Views.
7. Async thumbnail loading was hardened for ViewHolder recycling.

### PR #7 real CI failure and fix

The first PR #7 workflow (#107) failed compilation with:

`Unresolved reference 'scrollToLatest'`

Four call sites had already been migrated to `scrollToLatest()`, but the helper itself had not been included.

Fix commit:
`9a188351b740832aec36c2c1ef7d24955de110b0`

Then run #108 passed, PR #7 merged, and main run #109 passed.

---

## 3. Current relevant architecture

Important files:
- `app/src/main/java/com/example/openaichat/MainActivity.kt`
- `app/src/main/java/com/example/openaichat/ChatDatabase.kt`
- `app/src/main/java/com/example/openaichat/ChatMessageAdapter.kt`
- `app/src/main/java/com/example/openaichat/ChatMessageFingerprint.kt`
- `app/src/main/java/com/example/openaichat/BackgroundRecoveryPolicy.kt`
- `app/src/main/java/com/example/openaichat/BackgroundResponseWorkScheduler.kt`
- `app/src/main/java/com/example/openaichat/BackgroundResponseWorker.kt`
- `app/src/main/java/com/example/openaichat/ApiHttpException.kt`
- `app/src/main/java/com/example/openaichat/SseEventParser.kt`
- `app/src/main/java/com/example/openaichat/SecureStore.kt`
- `.github/workflows/build.yml`
- `tools/verify_v4_16_3.py`
- `app/src/main/java/com/example/openaichat/ChatScrollPolicy.kt`

Current `MainActivity.kt` is still very large:
- about 4,305 lines;
- about 224 KB.

It still owns UI creation, chat state, streaming, polling, uploads, persistence calls, metrics and recovery coordination.

---

### v4.16.3 reliability patch (PR #9)

Additional code review found three issues that were not fully captured by the previous handoff:
- active background-response state now persists `background_enabled` per request (DB v14), so WorkManager does not resurrect a request that was created with background recovery disabled;
- background polling/finalization re-checks the persisted `request_id`, and final persistence uses an atomic `upsertCurrentChatIfActiveRequest()` transaction, preventing a stale worker from writing an old response after stop/delete/new-request races;
- chat streaming uses a sticky-bottom policy so deliberate upward scrolling is preserved while new outgoing messages still jump to the latest item.

The recovery retry path now applies bounded ±20% jitter unless the server supplies `Retry-After`. `ChatScrollPolicy` and its boundary tests are pure JVM-testable logic.

Additional hardening in the same patch:
- when `background=true`, `onStop()` keeps the initial SSE alive until `response.created` has delivered a response ID; the official resume flow depends on that ID;
- remote cancellation is attempted only for background responses;
- `FileProvider` now explicitly exposes the `attachments/` app-private directory and no longer exposes the entire cache directory via `cache-path="."`;
- CI now cancels superseded builds for the same ref, reducing stale-build noise.

## 4. Existing reliability work already completed before this session

Earlier merged PRs:
- PR #1 — moved Android source out of ZIP archive.
- PR #3 — response lifecycle and CI hardening.
- PR #4 — SSE parser and background recovery hardening.
- PR #5 — process-death-resilient background Responses via WorkManager.
- PR #6 — incremental persistence and async thumbnails.
- PR #7 — RecyclerView/DiffUtil history rendering.

PR #2 was closed without merge.

Important behavior from PRs #3–#5:
- active response writes are request-aware;
- stale callbacks are guarded by chat ID + generation;
- polling has finite timeouts;
- background response recovery handles 401/403, 404/410, 429 Retry-After and 5xx/network backoff;
- WorkManager owns long-lived background recovery when Activity is not visible;
- active responses persist partial text;
- deletion cancels matching background work and remote response;
- SQLite foreign keys are explicitly enabled;
- success metrics only count `completionState == "completed"`.

---

## 5. Current CI contract

File: `.github/workflows/build.yml`

Environment:
- ubuntu-24.04
- JDK 17
- Android SDK 35
- build-tools 35.0.0
- Gradle 8.9

Before Gradle:
- `tools/verify_v4_16_3.py`

Gradle command:
```
gradle --no-daemon --stacktrace test lintDebug assembleDebug
```

The workflow uploads:
`app/build/outputs/apk/debug/app-debug.apk`

There is no dedicated release/AAB build in CI yet.

---

## 6. Current dependency versions

`app/build.gradle.kts` currently uses:
- AGP 8.7.3
- Kotlin Gradle Plugin 2.0.21
- WorkManager 2.11.2
- RecyclerView 1.3.2
- Core KTX 1.15.0
- OkHttp 4.12.0
- JUnit 4.13.2
- test-only `org.json:json:20250517`

As of 2026-09-21, official AndroidX docs list:
- RecyclerView **1.4.0** as stable;
- Core/Core-KTX **1.19.0** as stable;
- WorkManager **2.11.2** as stable.

Do not mix these upgrades into unrelated reliability PRs. Make a dedicated dependency/compatibility PR and run the full CI suite.

AGP is much older than current 9.x releases. Current AGP 9.x introduces built-in Kotlin integration and other compatibility changes. Treat AGP/Kotlin/Gradle upgrade as a separate migration project, not a quick version bump.

---

## 7. Known remaining technical risks / improvements

### Highest-value next work: tests

There are still not enough tests around the most failure-prone state transitions.

Add JVM/unit coverage for:
- stop before `response.created`;
- stale background finalization after stop/delete/new request;
- DB v13 -> v14 migration and persisted background flag;
- late `response.created` after stop/delete;
- switch chat while stream callbacks are still arriving;
- orphan active row with no response ID;
- WorkManager worker sees stale request ID;
- WorkManager preserves partial text;
- HTTP policy:
  - 401/403 terminal auth failure;
  - 404/410 terminal gone;
  - 429 Retry-After;
  - 5xx exponential backoff + jitter;
  - network failure + session deadline;
- DB migration v12 -> v13;
- incremental upsert:
  - unchanged message => no update;
  - changed text => update;
  - middle deletion => stale row removed;
  - request ID match wins over position match;
- deletion cleanup of local files.

The goal should be to make the remaining race-sensitive logic executable in JVM tests instead of relying only on static inspection and manual CI. v4.16.3 already adds pure tests for the sticky-scroll and retry-jitter rules.

### RecyclerView follow-up

Current `ChatMessageAdapter.submitItems()` calls synchronous `DiffUtil.calculateDiff()`.

For huge histories, consider `ListAdapter` / `AsyncListDiffer` so diff computation is off the main thread.

Also review `scrollToLatest()`:
- current behavior always forces the list to the latest position;
- fixed in v4.16.3 via `ChatScrollPolicy` + sticky-bottom behavior.

Remaining RecyclerView work is moving `DiffUtil` off the main thread with `AsyncListDiffer`/`ListAdapter` for very large histories.

Also add adapter tests or small model tests around stable item IDs and streaming/final-message transitions.

### Deprecated Android APIs

Current MainActivity still contains:
- `systemWindowInsetLeft/Top/Right/Bottom`;
- `consumeSystemWindowInsets()`;
- `startActivityForResult()`;
- `onActivityResult()`.

CI/lint has tolerated these as warnings, but they should be migrated in a dedicated cleanup PR.

### Architecture

Do not rewrite everything at once.

Recommended extraction sequence:
1. `ChatMessageAdapter` and message UI helpers — already started by PR #7.
2. keep the new response/recovery request-aware boundaries while extracting streaming/polling into a dedicated controller/service;
3. attachment/upload controller;
4. background recovery coordinator;
5. persistence facade;
6. leave Activity as lifecycle/composition glue.

The goal is small reviewable PRs.

### CI/release

Add a dedicated release verification path:
- `assembleRelease` or preferably AAB build;
- release lint;
- release minification/shrinker validation;
- artifact upload.

The existing workflow proves only the debug APK path.

### Security / diagnostics

Review:
- FileProvider path declarations;
- local file cleanup/orphan retention after crashes;
- diagnostics retention/clear behavior;
- ensure diagnostic logs never include the API key or message body;
- consider a user-visible diagnostics privacy switch only if future diagnostics become more verbose.

### Responses renderer

The project intentionally does not yet implement every Responses API event as a complete event-driven UI state machine.

Current design:
- live stream uses a lightweight text buffer;
- final response uses `AstraResponseModel` and `RichTextRenderer`;
- background recovery uses polling.

Do not turn this into an uncontrolled rewrite. Make it a separate design/implementation phase.

---

## 8. Concrete next PR plan

Suggested next PR: **test: cover response recovery and incremental persistence**

Scope:
- add deterministic unit tests around `BackgroundRecoveryPolicy`;
- add tests around DB v12 -> v13 migration and incremental upsert;
- add tests for response stop/delete races at the pure state/helper level;
- avoid UI refactoring in the same PR.

After that:
1. RecyclerView `AsyncListDiffer` + sticky-bottom behavior.
2. Deprecated Activity APIs cleanup.
3. AndroidX dependency update:
   - RecyclerView 1.4.0;
   - Core KTX 1.19.0;
   - verify WorkManager remains at 2.11.2.
4. Release/AAB CI.
5. Gradual MainActivity extraction.

---

## 9. How to start the next task

1. Use canonical repo `luoecrt-pixel/OpenAIApiChat`.
2. Confirm `main` is green.
3. Create a fresh branch from `main`.
4. Inspect existing unit tests and the exact current implementations before changing code.
5. Keep each PR focused.
6. Wait for the GitHub Actions result; do not report a PR as green while the run is still `in_progress`.
7. When CI fails, fetch the job log and fix the concrete failure before doing more architecture work.

---

## 10. Important historical CI lessons

Two CI failures in this session are worth preserving because they reveal common failure modes:

### JVM vs Android classes

A class can compile in Android source but fail in local/JVM tests because framework classes such as `org.json` are stubs under Android unit-test execution.

When adding JVM tests for Android-adjacent logic:
- identify whether the class is backed by framework stubs;
- provide a JVM implementation/dependency where necessary;
- run the real `testDebugUnitTest` task.

### Large mechanical refactors need a post-patch symbol scan

PR #7 temporarily had calls to a helper that had not yet been committed.

After large replacements, always search for:
- unresolved helper calls;
- old field names;
- old View-tree references;
- duplicate implementations;
- imports that no longer match.

---

## 11. Current source-quality observations

The codebase now has substantially better persistence and lifecycle safety, but the Activity remains the main architectural hotspot.

Current message rendering has one more subtle trade-off to watch:
- `ChatBubbleItem` uses `ChatMessageFingerprint` as a content signature for message-backed items;
- streaming `updateText()` changes the visible text through payload without recomputing the signature;
- finalization replaces the item with a message-backed object and therefore recalculates the signature.

That is currently intentional, but any future code that relies on `contentSignature` during streaming must account for this distinction.

---

## 12. GitHub URLs / history

Useful PRs:
- #1: source extraction
- #3: lifecycle + CI
- #4: SSE/background hardening
- #5: WorkManager
- #6: incremental persistence + thumbnails
- #7: RecyclerView/DiffUtil

Main is currently green after PR #7.


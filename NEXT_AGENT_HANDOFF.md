# NEXT_AGENT_HANDOFF — 2026-09-22

## Current state

Canonical repository: `luoecrt-pixel/OpenAIApiChat`

Default branch: `main`

Latest code-changing `main` commit before this documentation reconciliation:
`ce251af52ef899907e17f11dff237fb9cf3798c6` — PR #20.

The current code baseline is:

- versionName: `4.17.0`
- versionCode: `64`
- DB schema: `v15`
- compileSdk / targetSdk: `35`
- minSdk: `26`
- MainActivity: ~4.5k lines

This documentation pass changes README/handoff only. Treat `ce251af5...` as the code baseline; later documentation commits must not be mistaken for new implementation work.

## Current dependencies

- AGP 8.7.3
- Kotlin Gradle Plugin 2.0.21
- Activity KTX 1.10.1
- WorkManager 2.11.2
- RecyclerView 1.4.0
- Core KTX 1.15.0
- OkHttp 4.12.0
- JUnit 4.13.2
- JVM test dependency: org.json:json:20250517

Compatibility finding:

- newer Activity KTX branches tested against this project require a newer compileSdk;
- newer Core KTX branches tested against this project also require a newer compileSdk;
- RecyclerView 1.4.0 passes the current compileSdk 35 baseline.

Do not mix AGP/Kotlin/compileSdk migration into small reliability fixes.

## Merged engineering history

### PR #10 — resumable background Responses

Implemented:

- persisted `active_last_sequence_number`;
- `ResumableResponseStreamClient`;
- `starting_after` resume;
- shared Activity/Worker transport;
- strict sequence-gap handling with retry/backoff/jitter;
- process-death recovery through WorkManager;
- partial text + cursor checkpointing;
- removal of the old Activity polling recovery path.

### PR #11 — AsyncListDiffer

`ChatMessageAdapter` now uses `AsyncListDiffer` for structural reconciliation.

Streaming text/reasoning updates remain payload-style and do not launch an async diff per token.

### PR #12 — modern Activity Result API

- `MainActivity : ComponentActivity()`;
- `ActivityResultLauncher`;
- old `startActivityForResult()` / `onActivityResult()` paths removed.

### PR #13 — RecyclerView 1.4.0

Dependency upgrade completed and validated on compileSdk 35.

### PR #15 — ActiveResponseState

`ActiveResponseStateStore` centralizes:

- generation;
- response ID;
- assistant item ID;
- user-stop generation.

### PR #17 — release/AAB CI

Authoritative workflow: `.github/workflows/release.yml`

Checks:

```
gradle --no-daemon --stacktrace test lintRelease assembleRelease bundleRelease
```

Artifacts:

- release APK;
- release AAB.

This is packaging verification only; production signing/publishing stays separate.

### PR #18 — O(1) adapter indexing

Added:

- `itemById`;
- `itemIndexById`;
- `displayPositionById`.

Removed hot-path linear searches from the adapter.

### PR #19 — strict initial SSE sequence gaps

Foreground SSE previously logged a gap and continued. Resumable recovery already failed fast.

PR #19 introduced a shared `ResponseStreamSequenceCursor` contract and made foreground parsing fail on a real sequence gap instead of persisting a silently incomplete stream.

Merged commit:
`f9b4d2e4b1504d43378ca43f9ecc745c00bc6fe6`

### PR #20 — Stop generation race

The synchronous Stop path already restores composer/chat state. The async executor cancellation handler could otherwise replay those mutations after Stop.

PR #20 ties the cancellation decision to the captured request generation and exits early when the request was already user-stopped.

Merged commit:
`ce251af52ef899907e17f11dff237fb9cf3798c6`

Post-merge Debug and Release CI were green.

## Current architecture

Already hardened:

- generation-based stale callback protection;
- request-aware DB writes;
- WorkManager recovery;
- resumable SSE with durable cursor;
- sequence-gap detection and retry;
- bounded retry/backoff/jitter;
- HTTP handling for auth/gone/rate-limit/server/network failures;
- sticky chat scroll policy;
- AsyncListDiffer + O(1) adapter indexes;
- modern Activity Result API.

Important persistence fields:

- `active_request_id`
- `active_response_id`
- `active_text`
- `active_status`
- `active_background_enabled`
- `active_last_sequence_number`

Critical transaction:

`upsertCurrentChatIfActiveRequest()` verifies the persisted `active_request_id` before finalizing persisted chat state.

## Existing tests / verifier

JVM tests include:

- `ActiveResponseStateTest`
- `BackgroundRecoveryPolicyTest`
- `ChatScrollPolicyTest`
- `ChatMessageFingerprintTest`
- `ResumableResponseRequestTest`
- `SseEventParserTest`
- `ResponseStreamSequenceCursorTest`

Static verifier:

`tools/verify_v4_17_0.py`

Debug workflow:

`.github/workflows/build.yml`

Release workflow:

`.github/workflows/release.yml`

Treat static verifier and real Gradle CI as separate checks.

## Current thread-safety note

`ActiveResponseStateStore.state` is read from executor callbacks while its immutable snapshots are updated on the Activity/main thread. PR #23 adds `@Volatile` to the snapshot and a structural JVM test for that visibility contract. This addresses one confirmed cross-thread visibility boundary; the broader Activity/Executor/WorkManager thread-safety audit remains open.

## What is still worth doing

Priority order:

1. Deterministic lifecycle/recovery tests:
   - Stop before `response.created`;
   - late callback after Stop/Delete;
   - stale Worker finalization after a new request;
   - chat switch while callbacks arrive;
   - orphan active-response state;
   - DB v13→v14→v15 migrations;
   - partial-response preservation.

2. Response/recovery controller extraction from `MainActivity.kt`:
   - generation;
   - response lifecycle;
   - retry/backoff;
   - DB checkpoints;
   - Activity ↔ WorkManager coordination.

3. Attachment/upload/composer controller:
   - URI/file/image upload;
   - ZIP extraction;
   - upload progress;
   - composer persistence.

4. Thread-safety audit:
   - verify remaining Activity/Executor/WorkManager boundaries;
   - look for shared mutable state beyond `ActiveResponseStateStore`;
   - only change synchronization after reproducing or proving a race.

   PR #23 covers the confirmed `ActiveResponseStateStore.state` visibility boundary.

5. Diagnostics/security review:
   - diagnostics retention/clear;
   - no API keys or message bodies in diagnostics;
   - orphan local file behavior;
   - FileProvider least-privilege paths.

6. Release hardening:
   - deliberate shrinker/minification policy;
   - verify renderer/reflection/file handling under shrinker;
   - keep signing/publishing separate.

7. Compose migration:
   - controller/state extraction first;
   - explicit immutable screen state/events;
   - one bounded Compose feature via interop;
   - incremental chat migration.

8. AGP/Kotlin/compileSdk migration as a separate compatibility project.

9. Add Gradle Wrapper for local/CI toolchain parity.

Do not re-plan these as unfinished work:

- AsyncListDiffer — done (#11);
- modern Activity Result API — done (#12);
- RecyclerView 1.4.0 — done (#13);
- release APK/AAB CI — done (#17);
- O(1) ChatMessageAdapter indexing — done (#18);
- foreground sequence-gap consistency — done (#19);
- Stop-generation race fix — done (#20);
- cursor-based `starting_after` resume — done (#10).

## Compose decision

Do not start a big-bang Compose rewrite.

The current architectural order is:

`response controller → attachment/composer state → immutable screen state/events → one bounded Compose feature → incremental chat migration`

RecyclerView is not considered an architectural mistake. The current AsyncListDiffer/O(1) work is a valid intermediate solution.

## Documentation rules

README is the human/project snapshot:

- current capabilities;
- current build configuration;
- recent engineering history;
- only unfinished plan.

`NEXT_AGENT_HANDOFF.md` is continuity for another agent:

- exact code baseline;
- important architectural contracts;
- completed PRs;
- known constraints;
- next investigation order.

GitHub Actions is the executable source of truth for build status.

When a planned item is completed, remove it from the plan and record it in the relevant history section. Do not keep completed tasks in both places.

## Rules for the next agent

1. Start from actual `main`.
2. Inspect current source before editing.
3. Search for stale symbols/helpers after mechanical edits.
4. Keep changes in small PRs.
5. Never call an in-progress workflow green.
6. After a merge, verify post-merge Debug and Release.
7. Do not resurrect superseded release branches or duplicate PRs.

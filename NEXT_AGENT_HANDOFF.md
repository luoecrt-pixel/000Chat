# NEXT_AGENT_HANDOFF — 2026-09-24

## Current state

Canonical repository: `luoecrt-pixel/OpenAIApiChat`

Default branch: `main`

Latest code-changing `main` commit before this documentation reconciliation:
`5f744c180fb359ab49657632cbce8cead31e0e7d` — PR #27.

The current code baseline is:

- versionName: `4.17.0`
- versionCode: `64`
- DB schema: `v15`
- compileSdk / targetSdk: `35`
- minSdk: `26`
- MainActivity: ~4.5k lines

This documentation pass changes README/handoff only. Treat `5f744c180f...` as the current code baseline; later documentation commits must not be mistaken for new implementation work.

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

### PR #23 — ActiveResponseState visibility

Added `@Volatile` to the immutable `ActiveResponseStateStore.state` snapshot and a JVM test for the cross-thread visibility contract.

### PR #25 — monotonic user-stop invalidation

User-stop invalidation now remains effective for every generation up to the latest user-stop generation, including after state clearing and subsequent requests. Added deterministic JVM coverage for repeated Stop operations.

### PR #27 — background resume UI snapshot guard

Confirmed lifecycle race: `streamBackgroundResponse()` previously read the current Activity generation at callback time instead of the generation belonging to the resumed response. A late callback could therefore pass the UI guard after a newer request had started.

Fix:

- immutable `BackgroundResponseUiSnapshot(chatId, generation, assistantItemId)`;
- snapshot captured once when a background resume starts;
- foreground→background continuation preserves the original snapshot;
- text, reasoning and tool UI updates require matching chat, generation, assistant item, visible/non-finishing Activity;
- JVM regression coverage verifies generation/chat/assistant mismatch rejection.

CI for PR #27: Debug and Release both passed, including static verifier and full Gradle test/lint/build/bundle steps.

Merged commit:
`5f744c180fb359ab49657632cbce8cead31e0e7d`.

The post-merge Debug and Release workflows for that merge are the source of truth for the current main result.

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

Two concrete visibility/ownership boundaries are now explicit:

- PR #23: `ActiveResponseStateStore.state` is `@Volatile`, so executor readers observe the immutable state snapshot published by the Activity/main thread.
- PR #27: background-resume UI callbacks carry an immutable `chatId + generation + assistantItemId` snapshot instead of reading the current generation at callback time.

PR #25 separately made user-stop invalidation monotonic across generations.

These are confirmed fixes for specific races, not completion of the broader Activity/Executor/WorkManager thread-safety audit.

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

   PR #23 covers state visibility; PR #27 covers background-resume UI ownership. The broader audit remains open.

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
- cursor-based `starting_after` resume — done (#10);
- monotonic user-stop invalidation — done (#25);
- background resume UI snapshot guard — done (#27).

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



## Operating instructions for the next model

Работай как продолжение инженерного аудита, а не как генератор задач.

### Перед каждой новой работой

1. Сначала проверь фактический main HEAD и открытые PR.
2. Сверь текущий код с README.md и NEXT_AGENT_HANDOFF.md; документация может отставать.
3. Определи, действительно ли проблема наблюдается в коде/CI/архитектуре. Не создавай PR только ради «улучшения» без измеримого эффекта или понятного устранения риска.
4. Посмотри не только локальный участок, но и границу между Activity, Executor, Worker и DB, когда изменение касается lifecycle/recovery.
5. Перед рефакторингом найди существующие тесты и текущие invariants.

### Рабочее окно

Один рабочий заход должен длиться ориентировочно **10–25 минут**. Цель — успеть разобраться, изменить, проверить и оставить понятный след в документации. Не подгоняй архитектуру под фиксированное число PR: за один заход может выйти один PR, несколько очень маленьких связанных изменений или только подтверждённый вывод.

### Размер изменений

Количество PR не является целью. Обычно делай небольшое изменение за один логический шаг.
- Допустимы 2 очень маленьких тесно связанных PR, если разделение реально улучшает проверяемость.
- Не объединяй в один PR архитектурный рефакторинг, upgrade зависимостей и косметический cleanup.
- После механических переименований обязательно ищи старые символы, импорты, helpers и устаревшие проверки.

### Как вести README и handoff

Документация должна следовать реальному состоянию:

план → реализация → CI/проверка → merge → история

- Новую незавершённую работу записывай в план до или вместе с началом PR.
- После merge убирай её из плана и добавляй в историю инженерных изменений.
- Если по ходу аудита выяснилось, что идея не нужна, ошибочна или заменена другим решением — удали её из плана, не сохраняй ради истории.
- Не оставляй в README старые формулировки вроде «нужно сделать X», если X уже реализован.
- README должен быть полезен человеку, который открывает репозиторий впервые.
- NEXT_AGENT_HANDOFF.md должен быть полезен следующей модели: текущий baseline, уже закрытое, ограничения, доказанные риски и следующий порядок проверки.
- Не дублируй один и тот же незавершённый пункт в нескольких разделах без причины.

### CI и доверие к результату

- Static verifier и Gradle CI — разные уровни проверки; учитывай оба.
- Никогда не называй workflow зелёным, пока он in_progress или queued.
- Если CI падает из-за устаревшей проверки/инфраструктуры, чини сам контракт проверки, а не ослабляй её ради зелёного статуса.
- После merge проверяй post-merge Debug и Release.
- Документационные коммиты не считать новым code baseline.
- Реальные CI failures фиксируй в отчёте честно, включая промежуточную причину.

### Архитектурный и продуктовый взгляд

Периодически делай остановку и задавай два вопроса:

1. «Мы сейчас устраняем реальную проблему или просто уменьшаем счётчик TODO?»
2. «Есть ли здесь возможность сделать приложение заметно полезнее или необычнее без усложнения ради усложнения?»

Для второго вопроса разрешено **придумывать новые идеи**, даже если их не было в исходном плане. Но сначала сформулируй конкретную пользовательскую ценность и минимальный проверяемый шаг. Хорошая идея должна вписываться в существующие данные/API и иметь путь к постепенной реализации.

Пример текущего продуктового радара: **ветвление/форк чата от выбранного сообщения**, чтобы безопасно исследовать альтернативный запрос или настройки, сохраняя исходную ветку. Это пока кандидат для проверки, а не обещание реализации.

Периодически делай остановку и задавай вопрос:

«Мы сейчас устраняем реальную проблему или просто уменьшаем счётчик TODO?»

Особенно следи за ответственностями MainActivity.kt, Background Worker, network streaming и persistence. Если несколько PR подряд исправляют один и тот же класс проблем в разных местах, это сигнал пересмотреть границу архитектуры.

При таком сигнале сначала сформулируй общий контракт и добавь тесты, затем выноси слой небольшим PR. Не делай big-bang rewrite только потому, что текущий файл большой.

### Отчёт пользователю

После каждого законченного шага сообщай:

- что именно изменено;
- почему изменение было нужно;
- что подтвердил CI/тесты;
- что осталось в плане;
- есть ли новый фундаментальный архитектурный вывод.

Не выдавай предположение за найденный баг и не выдавай косметический cleanup за архитектурный прогресс.

## Rules for the next agent

1. Start from actual `main`.
2. Inspect current source before editing.
3. Search for stale symbols/helpers after mechanical edits.
4. Keep changes in small PRs.
5. Never call an in-progress workflow green.
6. After a merge, verify post-merge Debug and Release.
7. Do not resurrect superseded release branches or duplicate PRs.

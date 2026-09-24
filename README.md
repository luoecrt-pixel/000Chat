# OpenAI API Chat — Astra v4.17.0

Нативный Android-клиент для собственного OpenAI API key.

## Текущее состояние

- **main code baseline:** `98ca902bd2474129fbb6e95ebfaa23cef8b1cb01` (PR #37 squash)
- **latest handoff docs:** `12eb9788951ff46ccbe3c84c2bfd35deae8c00c3`
- **versionName / code:** `4.17.0 / 64`
- **DB schema:** `v15`
- **compile/target SDK:** `35`
- **min SDK:** `26`
- **MainActivity:** ~4.5k строк

#37 добавил `ActiveResponsePersistence` между response orchestration и `ChatDatabase`, не меняя DB schema или request-aware semantics. PR-head Debug/Release CI зелёные; Debug прошёл реальный Activity lifecycle instrumentation harness.

GitHub Actions — источник факта о CI. Документальные коммиты после merge не считаются новым code baseline.

## Что приложение умеет

### Chat / Responses

- несколько локальных чатов и SQLite-история;
- Responses API streaming + `previous_response_id`;
- reasoning effort и `configuration_update`;
- generation/request-aware защита от stale callbacks и DB writes;
- Stop/cancellation;
- resumable SSE с durable sequence cursor и `starting_after`;
- sequence-gap detection + retry/backoff/jitter;
- background Responses через WorkManager, включая process-death recovery;
- сохранение и восстановление partial response.

### Tools / files / UI

- web/File Search, Code Interpreter, Image Generation, Computer Use events;
- изображения и Files API;
- ZIP import с лимитами;
- local Astra file library;
- persistent drafts и attachments;
- RecyclerView + AsyncListDiffer + O(1) adapter indexes;
- async thumbnails/LRU cache, sticky scroll, diagnostics и usage/effort metrics.

## Архитектурная карта

Главная lifecycle-цепочка сейчас:

`generation → request/response IDs → immutable UI ownership snapshot → response event semantics → persisted active-response state → WorkManager recovery`

Критические DB-поля:

`active_request_id`, `active_response_id`, `active_text`, `active_status`, `active_background_enabled`, `active_last_sequence_number`.

`upsertCurrentChatIfActiveRequest()` не должен финализировать устаревший request.

Текущие seams:
- `ResponseEventReducer` — pure response event semantics;
- `ResumableResponseStreamClient` — transport + recovery sequence cursor;
- `ActiveResponsePersistence` — persisted active-response adapter.

`MainActivity.kt` остаётся большим orchestration layer. Следующий шаг — вынести timing/checkpoint/ownership decisions из event loops, не превращая reducer в Android/DB/WorkManager объект.

RecyclerView/AsyncListDiffer не считаются архитектурной ошибкой. Compose — отложенный следующий слой после state/controller extraction, а не текущий rewrite.

## Архитектурный обзор — после #37

Следующий полезный срез — **pure checkpoint/ownership policy** поверх уже общих event semantics и persistence boundary.

Цель:
- reducer state + timing + sequence → deterministic persistence decision;
- immutable ownership snapshot остаётся на UI boundary;
- adapter выполняет запись, но не решает бизнес-семантику;
- один foreground/recovery runtime path переносится первым.

Tool/UI effects пока не нужно затаскивать в reducer: сначала стабилизировать state + persistence semantics.

## Что сейчас в плане

Только незавершённое:

1. Response/recovery controller: pure checkpoint/ownership policy вокруг `ResponseEventReducer` + `ActiveResponsePersistence`.
2. Attachment/upload/composer controller.
3. Более широкий thread-safety audit после уже подтверждённых границ #23/#27.
4. Diagnostics/security review.
5. Release hardening.
6. Compose migration после controller/state extraction, поэтапно.
7. Отдельно: AGP/Kotlin/compileSdk compatibility pass.
8. Добавить Gradle Wrapper.

## Что уже закрыто

- #34: late metric/telemetry callback после Delete больше не может заново создать telemetry для удалённого chat.
- #35: реальная Android Activity lifecycle boundary после Stop/Delete закрыта instrumentation coverage.
- #36: общий response-event reducer для foreground/background paths; JVM tests; exact-head Debug/Release зелёные.
- #37: active-response persistence adapter вокруг существующих request-aware DB primitives; exact-head Debug/Release зелёные.

### Product radar

**Fork/ветвление чата от выбранного сообщения** — кандидат, не обещание реализации. Цель: независимый snapshot истории и безопасное альтернативное продолжение. Сначала проверить семантику Responses API и `previous_response_id`, затем делать маленький DB/domain шаг.

## Сборка

Debug:

```
gradle --no-daemon --stacktrace test lintDebug assembleDebug
```

Release:

```
gradle --no-daemon --stacktrace test lintRelease assembleRelease bundleRelease
```

Static verifier: `tools/verify_v4_17_0.py`.

Основные версии: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, JDK 17, WorkManager 2.11.2, RecyclerView 1.4.0, Core KTX 1.15.0, OkHttp 4.12.0.

## Документация

`README.md` — **human/project snapshot**: текущие возможности, архитектурная карта, план и сборка.

`NEXT_AGENT_HANDOFF.md` — **операционный контракт для следующего агента**. Именно его нужно читать первым; полный README при старте не нужен.

Подробная continuity-информация и правила работы находятся в [NEXT_AGENT_HANDOFF.md](NEXT_AGENT_HANDOFF.md).

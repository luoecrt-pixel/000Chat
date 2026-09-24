# OpenAI API Chat — Astra v4.17.0

Нативный Android-клиент для собственного OpenAI API key.

## Текущее состояние

- **main code baseline:** `6c975658698d24cf40da3874f3c96eaf17da634f` (PR #35 squash)
- **docs follow-up:** `4f6d5bd72724963b262899df0c2d69184ebdea9f` (handoff only)
- **versionName / code:** `4.17.0 / 64`
- **DB schema:** `v15`
- **compile/target SDK:** `35`
- **min SDK:** `26`
- **MainActivity:** ~4.5k строк

Последние изменения закрыли реальную Android lifecycle boundary: #35 добавил ActivityScenario coverage для позднего callback после Stop/Delete и подтвердил тот же callback mutation path в RESUMED и rejection после lifecycle boundary. PR-head Debug/Release CI были зелёными.

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

`generation → response/request IDs → immutable UI ownership snapshot → persisted active-response state → WorkManager recovery`

Критические DB-поля:

`active_request_id`, `active_response_id`, `active_text`, `active_status`, `active_background_enabled`, `active_last_sequence_number`.

`upsertCurrentChatIfActiveRequest()` не должен финализировать устаревший request.

`MainActivity.kt` остаётся большим orchestration layer. Следующая архитектурная стадия — не big-bang rewrite, а общий response-event/controller seam. Важно не создавать второй owner для sequence cursor: `ResumableResponseStreamClient` уже централизует duplicate/gap handling на recovery path.

**Важно:** RecyclerView/AsyncListDiffer не считаются архитектурной ошибкой. Compose — отложенный следующий слой после state/controller extraction, а не текущий rewrite.

## Архитектурный обзор — 2026-09-24

Главное улучшение перед controller extraction — унифицировать семантику response events.

Сейчас foreground SSE и background recovery частично дублируют:
- event dispatch;
- text/reasoning accumulation;
- terminal-state handling;
- DB checkpoints;
- UI effects.

Первый controller PR должен поэтому начинаться с **pure response-event contract/reducer + JVM tests**, а не с переноса всего networking/Android-кода.

Минимальный контракт стоит проверить на:
- accepted / duplicate / gap sequence;
- text delta и reasoning summary;
- completed / failed / incomplete / cancelled;
- checkpoint decision;
- stale request / invalidated ownership no-op;
- requestId mismatch.

После этого переносить только один execution path и сравнить его поведение с существующими tests/contracts.

## Что сейчас в плане

Только незавершённое:

1. Response/recovery controller extraction: общий response-event contract + tests, затем маленькие переносы из `MainActivity.kt`.
2. Attachment/upload/composer controller.
3. Более широкий thread-safety audit после уже подтверждённых границ #23/#27.
4. Diagnostics/security review.
5. Release hardening.
6. Compose migration после controller/state extraction, поэтапно.
7. Отдельно: AGP/Kotlin/compileSdk compatibility pass.
8. Добавить Gradle Wrapper.

## Что уже закрыто

- #34: late metric/telemetry callback после Delete больше не может заново создать telemetry для удалённого chat.
- #35: реальный Android Activity lifecycle boundary после Stop/Delete закрыта instrumentation coverage.

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

# OpenAI API Chat — Astra v4.17.0

Нативный Android-клиент для собственного OpenAI API key.

## Текущее состояние

- **main:** `6ef5545c8526afe8355bba737f795bd17451dab3` (PR #32)
- **versionName / code:** `4.17.0 / 64`
- **DB schema:** `v15`
- **compile/target SDK:** `35`
- **min SDK:** `26`
- **MainActivity:** ~4.5k строк

Последние изменения усилили lifecycle/recovery и тестируемость: #23 visibility, #25 monotonic Stop invalidation, #27 immutable background UI snapshot, #29 lifecycle ownership contracts, #31 recovery eligibility policy, #32 Robolectric SQLite/migration coverage.

GitHub Actions — источник факта о CI. Документальные коммиты после merge не являются новым code baseline.

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

**Важно:** RecyclerView/AsyncListDiffer не считаются архитектурной ошибкой. Compose — отложенный следующий слой, а не текущий rewrite.

## Что сейчас в плане

Только незавершённое:

1. Android lifecycle test: поздний callback после Stop/Delete.
2. Response/recovery controller extraction из `MainActivity.kt`.
3. Attachment/upload/composer controller.
4. Более широкий thread-safety audit после уже подтверждённых границ #23/#27.
5. Diagnostics/security review.
6. Release hardening.
7. Compose migration после controller/state extraction, поэтапно.
8. Отдельно: AGP/Kotlin/compileSdk compatibility pass.
9. Добавить Gradle Wrapper.

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

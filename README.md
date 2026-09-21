# OpenAI API Chat — Astra v4.16.2

Нативный Android-клиент для собственного OpenAI API key, ориентированный на GPT-6 Astra.

## Реализовано

- Чат-интерфейс и несколько локальных чатов
- SQLite история с миграцией старого `chat_store`
- лёгкий список чатов без загрузки всей истории
- Responses API streaming + `previous_response_id`
- `gpt-6-astra`, reasoning low/medium/high/xhigh/max + Auto
- `configuration_update` для смены reasoning effort в standard mode
- `web_search` + кликабельные `url_citation`
- File Search + `file_citation` с явным скачиванием
- Code Interpreter + реальные `container_file_citation` и локальный cache
- Image Generation + локальное сохранение результата PNG и preview
- Images input через Files API `file_id`
- Markdown/bare HTTP(S) links
- ZIP import с потоковой распаковкой и лимитами
- диагностика, usage/effort metrics, 👍/👎 feedback
- live tool activity statuses
- local Astra file library (Open/Save/Share/Delete)
- background Responses with automatic resume after app restart
- persistent composer drafts and uploaded attachments per chat
- отмена генерации и защита от устаревших callback
- экспорт/сохранение/поделиться через Android Storage Access Framework
- GitHub Actions build workflow

## v4.16.2 — инженерные изменения 2026-09-21

За одну сессию были последовательно закрыты несколько классов рисков:

### Надёжность Responses / фонового режима

- request-aware очистка `active_response`, чтобы старый callback не мог стереть новое состояние;
- bounded polling client с connect/read/call timeout;
- обработка HTTP 401/403/404/410/429/5xx и сетевых ошибок с backoff/jitter;
- SSE parser с CRLF, multiline `data:`, комментариями, EOF flush и проверкой `sequence_number`;
- process-death recovery через WorkManager;
- unique work на пару `chatId + requestId`;
- cancel/resume логика синхронизирована между Activity и WorkManager;
- частичный текст фонового ответа сохраняется и безопасно восстанавливается;
- метрики считают успешным только `completionState == "completed"`.

### SQLite и сохранение истории

- включено явное SQLite foreign-key enforcement;
- DB schema сейчас **v13**;
- добавлен `messages.content_hash`;
- `ChatMessageFingerprint` использует SHA-256 persisted message fields;
- `upsertCurrentChat()` стал incremental: неизменённые сообщения не переписываются;
- удаление устаревших строк учитывает и удаления из середины истории;
- создан JVM-тест fingerprint;
- удаление чата чистит также принадлежащие ему локальные файлы.

### UI / производительность

- локальные изображения декодируются асинхронно;
- добавлен 12 MB LRU thumbnail cache;
- preview generated/user images не блокирует UI;
- история чата переведена с `ScrollView + LinearLayout` на `RecyclerView`;
- добавлен `ChatMessageAdapter` с `DiffUtil`;
- восстановление длинной истории идёт через diff вместо ручного создания всех View;
- streaming-дельты обновляют только текст активного item через payload;
- async thumbnail callbacks защищены от race при recycling ViewHolder.

### CI / верификация

Canonical workflow: `.github/workflows/build.yml`.

CI запускает:

```
gradle --no-daemon --stacktrace test lintDebug assembleDebug
```

Проверка исходников выполняется через `tools/verify_v4_16_2.py`.

В этой сессии два реальных CI-дефекта были найдены по логам и исправлены:

1. PR #6: JVM unit-тесты fingerprint падали из-за Android stub `org.json`. Исправление — `testImplementation("org.json:json:20250517")`.
2. PR #7: после миграции на RecyclerView четыре места вызывали `scrollToLatest()`, но helper не попал в первый коммит. Добавлен отдельным fix-коммитом.

После исправлений:
- PR #6 merged, CI main run #106 — **success**;
- PR #7 merged, CI main run #109 — **success**.

## История инженерных PR

| PR | Назначение | Статус |
|---|---|---|
| #1 | source moved out of ZIP archive | merged |
| #3 | response lifecycle + CI hardening | merged |
| #4 | SSE parsing + background recovery hardening | merged |
| #5 | process-death resilient background responses / WorkManager | merged |
| #6 | incremental chat persistence + async thumbnails | merged |
| #7 | RecyclerView + DiffUtil chat history | merged |

PR #2 был закрыт без merge.

## Сборка

Репозиторий сейчас канонически доступен как **`luoecrt-pixel/OpenAIApiChat`**. Старый URL `luoecrt-pixel/Mychatgpt` редиректит на каноническое имя.

Текущий app config:

- versionName: **4.16.2**
- versionCode: **62**
- compileSdk / targetSdk: **35**
- minSdk: **26**
- JDK: **17**
- AGP: **8.7.3**
- Kotlin Gradle Plugin: **2.0.21**
- Gradle CI: **8.9**
- WorkManager: **2.11.2**
- RecyclerView: **1.3.2**
- Core KTX: **1.15.0**
- OkHttp: **4.12.0**

CI устанавливает Android SDK 35 и build-tools 35.0.0.

## Что осталось проверить / улучшить

Подробный handoff для следующей модели: [NEXT_AGENT_HANDOFF.md](NEXT_AGENT_HANDOFF.md).

Наиболее полезный следующий порядок:

1. Добавить тесты для lifecycle/DB/background recovery: stale request, stop/delete race, WorkManager recovery, HTTP retry policy, миграции DB v12→v13.
2. Улучшить `RecyclerView`: перейти с синхронного `DiffUtil.calculateDiff()` на `AsyncListDiffer/ListAdapter` и не принудительно прокручивать вниз, если пользователь уже читает старые сообщения.
3. Обновить стабильные AndroidX зависимости. На 2026-09-21 официальные AndroidX release pages указывают RecyclerView 1.4.0, Core/KTX 1.19.0 и WorkManager 2.11.2.
4. Отдельно планировать миграцию AGP/Kotlin/Gradle: текущий AGP 8.7.3 существенно старше актуального AGP 9.x; современный AGP 9.x меняет встроенную Kotlin-интеграцию и требует отдельного compatibility pass.
5. Убрать deprecated Activity APIs: `systemWindowInset*`, `consumeSystemWindowInsets()`, `startActivityForResult()`, `onActivityResult()`.
6. Разделять гигантский `MainActivity.kt` на UI/state/network/persistence/recovery слои небольшими PR, без большого rewrite.
7. Расширить CI отдельным release/AAB build и проверкой release variant, потому что текущий workflow гарантирует только debug APK.
8. Проверить FileProvider paths, локальное orphan-file cleanup и retention диагностического журнала.
9. Для полного event-driven Responses renderer сохранить отдельный проектный этап; текущий live renderer намеренно остаётся быстрым текстовым buffer.

## Ограничения

Полноценный event-by-event renderer всех Responses API событий, Computer Use executor, MCP UX и Realtime voice — отдельные функции. Финальный ответ уже проходит через `AstraResponseModel`, а live streaming display использует быстрый текстовый буфер. Background Responses автоматически восстанавливаются через polling; cursor-based stream resume (`starting_after`) пока не используется.

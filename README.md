# OpenAI API Chat — Astra v4.17.0

Нативный Android-клиент для собственного OpenAI API key, ориентированный на GPT-6 Astra.

## Текущий статус

**Канонический репозиторий:** `luoecrt-pixel/OpenAIApiChat`  
**Основная ветка:** `main`  
**Текущая кодовая база:** `4.17.0` / versionCode `64`  
**DB schema:** `v15`  
**MainActivity:** ~4.5k строк

После базовой ветки v4.17.0 уже вошли ещё два reliability-fix PR:

- **PR #19** — строгая проверка непрерывности `sequence_number` в foreground SSE; тот же cursor-контракт используется resumable recovery.
- **PR #20** — cancellation/Stop привязан к generation запроса, чтобы устаревший executor не повторял UI-восстановление после ручной остановки.

VersionName пока остаётся **4.17.0**: #19/#20 — точечные исправления поверх текущей кодовой базы, без отдельного bump версии.

## Что уже реализовано

### Chat / Responses

- несколько локальных чатов;
- SQLite-история с миграцией старого `chat_store`;
- Responses API streaming + `previous_response_id`;
- `gpt-6-astra`, reasoning low/medium/high/xhigh/max + Auto;
- `configuration_update` для standard mode;
- generation-based защита от устаревших callback;
- request-aware DB writes для active response;
- отмена генерации без повторного применения старого Stop-handler;
- resumable SSE с durable `active_last_sequence_number` и `starting_after`;
- fail-fast на sequence gap + bounded retry/backoff/jitter;
- восстановление фоновых Responses через WorkManager после process death;
- partial response text сохраняется и восстанавливается.

### Tools / files / images

- `web_search` + кликабельные `url_citation`;
- File Search + `file_citation` с явным скачиванием;
- Code Interpreter + `container_file_citation` и локальный cache;
- Image Generation + локальное сохранение PNG и preview;
- Images input через Files API `file_id`;
- Markdown и bare HTTP(S) links;
- ZIP import с потоковой распаковкой и лимитами;
- local Astra file library (Open/Save/Share/Delete);
- экспорт/сохранение/поделиться через Android Storage Access Framework.

### UI / persistence / performance

- persistent composer drafts и uploaded attachments per chat;
- `RecyclerView` вместо `ScrollView + LinearLayout`;
- `AsyncListDiffer` для структурного diff истории;
- O(1) identity/model/display lookups в `ChatMessageAdapter`;
- streaming text/reasoning обновляются точечно, без diff на каждый token;
- async image decode + 12 MB LRU thumbnail cache;
- race-защита thumbnail callbacks при recycling ViewHolder;
- sticky scroll policy: streaming не сбрасывает пользователя вниз, если он читает старую историю; новая ручная отправка прокручивает к последнему сообщению.

### Diagnostics / feedback / metrics

- диагностика и usage/effort metrics;
- 👍/👎 feedback;
- live tool activity statuses;
- успешным считается только `completionState == "completed"`.

## CI и источник истины

README хранит **смысл и статус проекта**. Фактический результат сборки надо смотреть в GitHub Actions.

### Debug CI

[Build Android APK](../../actions/workflows/build.yml)

Сейчас workflow проверяет:

```
gradle --no-daemon --stacktrace test lintDebug assembleDebug
```

Также запускается статический verifier `tools/verify_v4_17_0.py`.

Артефакт:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Release CI

[Build Android Release](../../actions/workflows/release.yml)

Сейчас workflow проверяет:

```
gradle --no-daemon --stacktrace test lintRelease assembleRelease bundleRelease
```

И публикует как workflow artifacts:

```
app/build/outputs/apk/release/*.apk
app/build/outputs/bundle/release/*.aab
```

Это **проверка сборки**, а не production signing/publishing.

## Текущая сборочная конфигурация

- compileSdk / targetSdk: **35**
- minSdk: **26**
- JDK: **17**
- AGP: **8.7.3**
- Kotlin Gradle Plugin: **2.0.21**
- Gradle в CI: **8.9**
- Activity KTX: **1.10.1**
- WorkManager: **2.11.2**
- RecyclerView: **1.4.0**
- Core KTX: **1.15.0**
- OkHttp: **4.12.0**
- JUnit: **4.13.2**
- JVM `org.json`: **20250517**

Текущий baseline намеренно остаётся на compileSdk 35. Попытки поднять отдельные AndroidX Activity/Core ветки до более новых версий упирались в требования к более новому compileSdk; такой upgrade нужно делать отдельным совместимым pass, а не вместе с мелкими fix PR.

## История инженерных изменений

### v4.17.0 engineering baseline

- **PR #10** — resumable background Responses, durable sequence cursor, retry/gap recovery.
- **PR #11** — `AsyncListDiffer` для истории чатов.
- **PR #12** — modern Activity Result API.
- **PR #13** — RecyclerView 1.4.0.
- **PR #15** — централизованный `ActiveResponseState`.
- **PR #17** — release APK/AAB CI.
- **PR #18** — O(1) lookups в `ChatMessageAdapter`.

### Reliability fixes после v4.17.0 baseline

- **PR #19** — единый строгий sequence-gap contract для foreground и resumable SSE.
- **PR #20** — Stop/cancellation generation race fix.

Исторические PR, закрытые без merge или superseded, остаются только частью истории GitHub и не являются альтернативными реализациями:

- **PR #2** — закрыт без merge;
- **PR #14** — первая попытка release/AAB CI, закрыта;
- **PR #16** — superseded by #17, закрыт.

## Что сейчас в плане

План ниже содержит только **не завершённые** направления. Сделанные пункты из старых планов сюда не возвращаются — они должны появляться в истории выше.

### 1. Детерминированные lifecycle/recovery tests

Добавить тесты на:

- Stop до `response.created`;
- late callback после Stop/Delete;
- stale Worker finalization после нового request;
- переключение чата во время callback;
- orphan active-response rows;
- DB migrations v13→v14→v15;
- сохранение partial response при recovery.

### 2. Выделение response/recovery controller

Сначала вынести из `MainActivity` lifecycle вокруг:

- generation;
- response ID;
- completion state;
- retry/backoff;
- DB checkpoints;
- Activity ↔ WorkManager coordination.

Activity должна остаться тонким lifecycle/composition слоем.

### 3. Attachment/upload/composer controller

После response lifecycle отдельно вынести:

- URI/file/image upload;
- ZIP extraction;
- upload progress;
- persistent composer state.

### 4. Thread-safety audit

Отдельно проверить границы потоков для `ActiveResponseStateStore` и участков Activity/Executor/WorkManager, не меняя модель без подтверждённого race.

### 5. Diagnostics/security review

Проверить отдельно:

- retention/clear поведения диагностического журнала;
- отсутствие API keys и message bodies в diagnostics;
- отсутствие orphan local files;
- минимальные FileProvider paths.

Базовая защита FileProvider и cleanup уже присутствуют в текущем коде; здесь нужен именно дополнительный аудит.

### 6. Release hardening

Отдельный PR для release variant:

- явно определить policy shrinker/minification;
- проверить renderer/reflection/file handling под shrinker;
- не смешивать это с signing/publishing.

### 7. Compose — только после выделения state/controller

Не делать big-bang rewrite.

Порядок:

1. response controller;
2. attachment/composer state;
3. явный immutable screen state + events;
4. один bounded Compose feature через interop;
5. затем постепенная миграция chat UI.

`RecyclerView + AsyncListDiffer` уже является закрытым этапом, поэтому возвращаться к нему в плане не нужно.

### 8. AGP / Kotlin / compileSdk migration

Отдельный compatibility project. Не совмещать с текущими небольшими reliability PR.

### 9. Gradle Wrapper / local-build parity

Репозиторий пока полагается на Gradle, установленный CI. Добавление wrapper улучшит воспроизводимость локальной сборки и совпадение local/CI toolchain.

## Архитектурные ограничения

Сейчас намеренно **не** реализованы:

- полноценный event-by-event renderer всех Responses API events;
- Computer Use executor;
- MCP UX;
- Realtime voice.

Live renderer остаётся быстрым текстовым buffer, а background recovery использует resumable stream с `starting_after`.

Полный Compose rewrite также не является текущей задачей: сначала нужно уменьшить ответственность `MainActivity.kt`.

## Правило поддержания документации

Чтобы README не превращался в устаревший backlog:

- **что уже работает** → в «Что уже реализовано»;
- **новое завершённое изменение** → в «История инженерных изменений»;
- **что ещё предстоит** → только в «Что сейчас в плане»;
- **факт CI** → проверять в GitHub Actions;
- подробности для следующего агента → в `NEXT_AGENT_HANDOFF.md`.

Подробный continuity note: [NEXT_AGENT_HANDOFF.md](NEXT_AGENT_HANDOFF.md).

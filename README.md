# OpenAI API Chat — Astra v4.17.0

Нативный Android-клиент для собственного OpenAI API key, ориентированный на GPT-6 Astra.

## Текущий статус

**Канонический репозиторий:** `luoecrt-pixel/OpenAIApiChat`  
**Основная ветка:** `main`  
**Кодовая база:** `4.17.0` / versionCode `64`  
**DB schema:** `v15`  
**MainActivity:** ~4.5k строк

Последний кодовый baseline перед этим документальным PR:

`ce251af52ef899907e17f11dff237fb9cf3798c6`

После baseline были добавлены точечные reliability-fix PR без bump версии:

- **PR #19** — строгая непрерывность `sequence_number` для foreground SSE и resumable recovery.
- **PR #20** — Stop/cancellation привязан к generation запроса, чтобы устаревший executor не повторял UI-восстановление.
- **PR #23** — `ActiveResponseStateStore.state` сделан `@Volatile`; добавлен тест контракта visibility.

Документальный **PR #24** синхронизировал правила работы следующего агента; следующий кодовый reliability fix — **PR #25**.

## CI — где смотреть фактический результат

README описывает состояние и архитектуру. GitHub Actions является источником факта о прохождении сборки.

[Build Android APK](../../actions/workflows/build.yml)  
[Build Android Release](../../actions/workflows/release.yml)

Debug workflow:

```
gradle --no-daemon --stacktrace test lintDebug assembleDebug
```

Release workflow:

```
gradle --no-daemon --stacktrace test lintRelease assembleRelease bundleRelease
```

Debug artifact:

```
app/build/outputs/apk/debug/app-debug.apk
```

Release artifacts:

```
app/build/outputs/apk/release/*.apk
app/build/outputs/bundle/release/*.aab
```

Source verifier:

`tools/verify_v4_17_0.py`

Release workflow проверяет упаковку APK/AAB. Это не production signing/publishing.

## История инженерных изменений

### v4.17.0 engineering baseline

- **PR #10** — resumable background Responses, durable sequence cursor, `starting_after`, retry/gap recovery.
- **PR #11** — `AsyncListDiffer` для истории чатов.
- **PR #12** — modern Activity Result API.
- **PR #13** — RecyclerView 1.4.0.
- **PR #15** — централизованный `ActiveResponseState`.
- **PR #17** — release APK/AAB CI.
- **PR #18** — O(1) lookups в `ChatMessageAdapter`.

### Reliability fixes поверх baseline

- **PR #19** — единый строгий sequence-gap contract для foreground и resumable SSE.
- **PR #20** — Stop/cancellation generation race fix.

### Ранние reliability/UI изменения

До v4.17.0 были последовательно закрыты:

- request-aware background recovery и атомарная финализация по `chatId + requestId`;
- bounded polling/retry и HTTP error policy;
- SSE parser hardening;
- WorkManager process-death recovery;
- incremental SQLite persistence + `content_hash`;
- async thumbnails + LRU cache;
- RecyclerView migration;
- Activity Result migration;
- sticky-scroll policy;
- FileProvider hardening;
- локальный cleanup принадлежащих чату файлов.

Детали старых версий остаются в `V4.*_NOTES.md`.

Исторические PR, не являющиеся альтернативными реализациями:

- **PR #2** — закрыт без merge;
- **PR #14** — первая попытка release/AAB CI, закрыта;
- **PR #16** — superseded by #17, закрыт.

## Что приложение умеет сейчас

### Chat / Responses

- несколько локальных чатов;
- SQLite-история с миграциями;
- Responses API streaming + `previous_response_id`;
- `gpt-6-astra`, reasoning low/medium/high/xhigh/max + Auto;
- `configuration_update`;
- generation-based защита от устаревших callback;
- request-aware DB writes;
- отмена генерации;
- resumable SSE с durable cursor;
- fail-fast на sequence gap + retry/backoff/jitter;
- background Responses через WorkManager после process death;
- сохранение и восстановление partial response.

### Tools / files / images

- `web_search` + `url_citation`;
- File Search + `file_citation`;
- Code Interpreter + `container_file_citation`;
- Image Generation + локальные PNG/preview;
- Images input через Files API `file_id`;
- Markdown/bare HTTP(S) links;
- ZIP import с потоковой распаковкой и лимитами;
- local Astra file library;
- Android Storage Access Framework для export/share/save.

### UI / performance / diagnostics

- persistent composer drafts и uploaded attachments per chat;
- RecyclerView + AsyncListDiffer;
- O(1) adapter identity/model/display indexes;
- payload-style streaming updates;
- async image decode + 12 MB LRU thumbnail cache;
- sticky scroll policy;
- diagnostics и usage/effort metrics;
- 👍/👎 feedback;
- live tool activity statuses;
- успешным считается только `completionState == "completed"`.

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

Текущий baseline намеренно остаётся на compileSdk 35. Отдельные попытки обновить более новые Activity/Core KTX показали требования к более новому compileSdk; такой upgrade нужно делать отдельным compatibility pass.

## Что сейчас в плане

Это только **незавершённые** направления. Сделанные пункты из старых планов сюда не возвращаются.

### 1. Детерминированные lifecycle/recovery tests

Покрыть:

**Подтверждённый новый race:** marker ручного Stop нельзя очищать сразу и нельзя хранить только последний остановленный generation. Иначе поздний executor callback может потерять факт user-stop и снова уйти в background continuation. PR #25 меняет контракт state на инвалидизацию всех generation до последнего user-stop.

- Stop до `response.created`;
- late callback после Stop/Delete;
- stale Worker finalization после нового request;
- переключение чата во время callback;
- orphan active-response state;
- DB v13→v14→v15;
- partial-response preservation.

### 2. Response/recovery controller

Вынести из `MainActivity.kt` lifecycle вокруг:

- generation;
- response ID;
- completion state;
- retry/backoff;
- DB checkpoints;
- Activity ↔ WorkManager coordination.

### 3. Attachment/upload/composer controller

Отдельно вынести:

- URI/file/image upload;
- ZIP extraction;
- upload progress;
- composer persistence.

### 4. Thread-safety audit

Проверить границы потоков для `ActiveResponseStateStore`, Executor, Activity и WorkManager. Менять синхронизацию только после подтверждённого race.

**Подтверждённая граница:** PR #23 уже merged и фиксирует visibility `ActiveResponseStateStore.state` через `@Volatile`. Более широкий audit ещё не считается завершённым.

### 5. Diagnostics/security review

Проверить:

- retention/clear диагностического журнала;
- отсутствие API keys и message bodies в diagnostics;
- orphan local files;
- FileProvider least-privilege paths.

Базовая защита FileProvider и cleanup уже присутствуют; здесь нужен дополнительный аудит.

### 6. Release hardening

Отдельный PR для:

- явной политики shrinker/minification;
- проверки renderer/reflection/file handling под shrinker;
- сохранения signing/publishing отдельно.

### 7. Compose — после state/controller extraction

Не делать big-bang rewrite.

Порядок:

`response controller → attachment/composer state → immutable screen state/events → один bounded Compose feature → incremental chat migration`

### 8. AGP / Kotlin / compileSdk migration

Отдельный compatibility project. Не совмещать с небольшими reliability PR.

### 9. Gradle Wrapper

Добавить wrapper для совпадения local/CI toolchain и воспроизводимой сборки.

### 10. Продуктовый кандидат: ветвление / fork чата

Идея: из выбранного сообщения создать **независимую ветку чата**, чтобы экспериментировать с альтернативным продолжением, не меняя исходный диалог.

Пока это **не реализация, а техническая заготовка для следующего агента**.

Что проверить и сохранить как контракт:

- UX первого MVP: действие «Ветвить отсюда» на сообщении; исходный чат остаётся неизменным, новый чат получает историю только до выбранного сообщения включительно.
- Новый чат должен получить собственный `chatId` и собственное состояние генерации. Нельзя переносить активный response/WorkManager job/`active_response` состояния из исходного чата.
- Критический момент — серверный контекст Responses API: нельзя без проверки просто переиспользовать `previous_response_id` исходного чата, потому что он может продолжить исходную ветку дальше выбранной точки. Сначала отдельно подтвердить актуальный механизм API, позволяющий получить контекст **ровно до fork point**, либо явно выбрать семантику независимого нового контекста.
- Скопированные сообщения должны стать самостоятельными локальными записями нового чата; исходные IDs и служебное состояние не должны случайно связывать две ветки.
- В первой версии не переносить незавершённые streaming/background операции и временный UI state. Вложения и ссылки на локальные файлы требуют отдельной проверки, чтобы fork не получил владельца файлов исходного чата по ошибке.
- Сначала сделать маленький детерминированный domain/DB шаг и тесты: fork от первого, среднего и последнего сообщения; исходный чат не меняется; новая история обрезана в точке fork; нет переноса active-response state.
- Только после подтверждения API-семантики добавлять реальную отправку нового запроса из fork. Не проектировать большую branch-graph систему заранее.

Минимальная цель: **fork как независимый snapshot диалога + безопасное продолжение**, а не полноценный Git-подобный граф веток.

## Архитектурные ограничения

Сейчас намеренно не реализованы:

- полноценный event-by-event renderer всех Responses API events;
- Computer Use executor;
- MCP UX;
- Realtime voice.

Live renderer остаётся быстрым текстовым buffer, background recovery — resumable stream с `starting_after`.

Полный Compose rewrite не является текущей задачей: сначала нужно уменьшить ответственность `MainActivity.kt`.

## Правила поддержания документации

Рабочий цикл аудита обычно укладывается в **10–25 минут**: достаточно долго, чтобы разобраться и проверить результат, но не настолько долго, чтобы копить изменения в огромный PR. Количество PR за такой заход не фиксировано.

Также периодически делать отдельный **архитектурный и продуктовый обзор**: проверять, не появились ли более фундаментальные решения и не стоит ли добавить одну действительно полезную возможность. Новая идея должна иметь понятную пользовательскую ценность и первый проверяемый маленький шаг; уникальность сама по себе не является причиной для реализации.

- что уже работает → в «Что приложение умеет сейчас» и/или истории;
- завершённый пункт плана → убрать из плана и добавить в историю;
- незавершённое → держать только в «Что сейчас в плане»;
- факт CI → смотреть в GitHub Actions;
- детали для следующего агента → `NEXT_AGENT_HANDOFF.md`.

Подробный continuity note: [NEXT_AGENT_HANDOFF.md](NEXT_AGENT_HANDOFF.md).

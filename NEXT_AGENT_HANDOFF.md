# NEXT_AGENT_HANDOFF

**Last updated:** 2026-10-01 09:55 +03:00
**Следующий рабочий срез:** Thread-safety audit — после PR G; затем PR H — human-facing documentation.

# START HERE — ОБЯЗАТЕЛЬНО

**Это основной документ для агента. README на старте не нужен.**

1. Начинай с фактического `main` и открытых PR.
2. Сначала докажи, что проблема существует. Не делай PR ради уменьшения TODO.
3. Для lifecycle/recovery смотри границу **Activity ↔ Executor ↔ Worker ↔ DB**, а не только один метод.
4. Используй узкие чтения файлов. Не загружай весь README, весь `MainActivity` или полные CI-логи без причины.
5. CI: queued/in_progress ≠ green. Static verifier и Gradle CI — отдельные проверки.
6. После merge проверь post-merge Debug/Release, если GitHub Actions предоставляет эти runs.
7. После завершения задачи: план → история; устаревшее удалить, а не оставлять вечным backlog.
8. Архитектурный/продуктовый обзор делай **каждые 5 merged PR или раньше, если активный план опустел/сменил слой**. Новая идея нужна только при конкретной ценности + маленьком проверяемом первом шаге.
9. **Не теряй общую картину:** Compose, controller extraction, thread-safety и fork chat — это разные уровни одной дорожной карты, а не взаимозаменяемые TODO.

## 30-секундная карта проекта

**Продукт:** локальный multi-chat Android-клиент с OpenAI Responses API и OpenRouter Chat Completions, tools/files/images и streaming. #47 добавил отдельный Claude/OpenRouter backend и app-side GitHub tool execution; OpenAI Responses backend остаётся отдельным путём. #48 исправил реальный UI wiring bug: GitHub-tools CheckBox создавался и сохранялся, но не был добавлен в видимый settings layout; теперь он виден рядом с PAT/repository/branches и начальное состояние читается из сохранённого `prefs.getBoolean("github_tools", false)`.

**Надёжность:** основная lifecycle-модель:
`generation → request/response ownership → immutable UI snapshot → persisted active-response state → WorkManager recovery`.

**Persistence:** DB schema v15; важные поля:
`active_request_id`, `active_response_id`, `active_text`, `active_status`, `active_background_enabled`, `active_last_sequence_number`.
Устаревший request не должен финализировать persisted state.

**Тесты:** JVM/pure contracts + Robolectric SQLite покрывают большую часть lifecycle/persistence. #35 добавил реальный Android ActivityScenario harness для callback после Stop/Delete. #36 добавил общий pure response-event reducer и прогнал его через foreground + background paths. #37 вынес active-response DB access за persistence adapter.

**Архитектура:** `MainActivity.kt` всё ещё ~4.5k строк. `ResponseEventReducer` владеет response semantics, `ActiveResponsePersistence` — boundary к persisted active-response state, `ResponseCheckpointPolicy` — pure timing/status/payload decision для checkpoint, `BackgroundResponseUiOwnershipPolicy` — pure UI ownership decision для принятого background snapshot, а `AttachmentUploadCompletionPolicy` — pure routing decision для async attachment completion. Background checkpoint, assistant-text/reasoning UI paths и file/image/ZIP attachment completion routing уже переведены на эти seams; foreground cadence намеренно не менялась. Tool-state path оставлен на `chatId + generation`, потому что он обновляет глобальный Activity tool label, а не конкретный assistant item. #47 добавил второй model transport без замены существующего Responses path: OpenRouter работает через отдельный Chat Completions client с собственным synchronous tool loop; GitHub API вызывается только приложением через allow-listed repository/branches, а write/merge/review/workflow actions требуют пользовательского подтверждения и audit log. OpenRouter пока не подключён к существующему OpenAI background-response recovery. PR G добавляет отдельный local attachment path: content:// сразу копируется в private storage; text/image/pdf/zip готовятся локально; history хранит attachment manifest, а OpenAI File API path остаётся отдельным. GitHub-tools toggle теперь имеет реальный visible UI wiring; сама app-side execution/confirmation boundary #47 не изменялась. Compose остаётся следующим UI-слоем после state/controller extraction.

**Размер изображений — подтверждённый внешний лимит:** Anthropic сейчас допускает до 10 MB base64 на одно изображение и 32 MB на стандартный API request; OpenRouter не объявляет единого универсального лимита, поэтому ограничения зависят от provider/model. Наш путь EXIF→PNG для non-normal orientation может многократно увеличить wire bytes у больших JPEG. До отдельного решения не добавлять lossy compression и не считать PNG-бюджет безопасным для Claude/OpenRouter.
**Recent #48 implementation note:** `githubTools` в `showSettings()` был реальным `CheckBox`, созданным через `chk(..., "github_tools")`, но отсутствовал `box.addView(...)`. Это не ломало существующие `prefs` read/write и runtime executor, но делало GitHub tools практически невключаемыми из UI. PR #48 добавил `box.addView(githubTools)` и сохранил инициализацию из `prefs.getBoolean("github_tools", false)`. Никакой логики `SecureStore`, `GitHubToolCatalog`, `GitHubToolExecutor`, confirmation или OpenRouter transport PR #48 не менял.

**Продуктовый радар:** см. отдельный раздел; это идеи-кандидаты, а не backlog.

## Фактический baseline

- main code baseline: `593009176082f32881d1f310e07bf54a14aaa4b6` (PR #57 merged into main; cleanup commit immediately follows to restore manual-only CI triggers)
- versionName/code: `4.17.0 / 64`
- DB: `v16`
- compile/target SDK: `35`
- min SDK: `26`
- JDK: `17`
- AGP: `8.7.3`
- Kotlin Gradle Plugin: `2.0.21`
- Gradle CI: `8.9`
- WorkManager: `2.11.2`
- RecyclerView: `1.4.0`
- Core KTX: `1.15.0`
- OkHttp: `4.12.0`

Последующие документационные commits не являются новым code baseline.

## Подтверждённые lifecycle/recovery контракты

- [pure] **#19:** foreground и resumable SSE используют строгий sequence-gap contract.
- [static] **#20:** Stop привязан к generation конкретного request.
- [pure] **#23:** `ActiveResponseStateStore.state` — `@Volatile` immutable snapshot.
- [pure] **#25:** user-stop инвалидирует все поколения до последнего user-stop; повторный Stop не забывает старые поколения.
- [pure] **#27:** background resume захватывает immutable `chatId + generation + assistantItemId` snapshot; callback не читает «текущую» generation постфактум.
- [pure] **#29:** Stop-before-create, stale Worker finalization и ownership проверяются через детерминированные contracts.
- [pure] **#31:** background recovery запускается только при явной eligibility policy.
- [robolectric] **#32:** реальные SQLite/Robolectric проверки v13→v15, v14→v15, reopen partial response/cursor и stale-request DB protection.
- [robolectric] **#34:** Delete удаляет effort telemetry вместе с chat в одной транзакции; поздний metric callback больше не может заново создать telemetry для удалённого chat.
- [instrumentation] **#35:** реальный Android ActivityScenario подтверждает положительный callback path в RESUMED и rejection того же mutation path после Stop и Delete; PR-head Debug/Release зелёные.
- [pure] **#36:** `ResponseEventReducer` содержит общую semantics для response.created, text/reasoning deltas и terminal states; foreground и background paths используют один reducer. Sequence cursor намеренно не дублируется в reducer.
- [static] **#37:** `ActiveResponsePersistence` изолирует set/update/get/list/clear active-response state; `ChatDatabaseActiveResponsePersistence` сохраняет прежнюю request-aware DB реализацию.
- [pure] **#38:** `ResponseCheckpointPolicy` изолирует pure checkpoint decision: interval vs terminal, persisted status/text/responseId/sequence; background recovery перенесён на policy, foreground 750ms cadence не менялась.
- [pure] **#39:** `BackgroundResponseUiOwnershipPolicy` изолирует pure decision для применения background UI snapshot по `chatId + generation + assistantItemId + lifecycle`.
- [static] **#40:** reasoning background callback использует тот же immutable UI snapshot ownership contract и передаёт `assistantItemId` из snapshot в state mutation.
- [pure] **#41:** file upload completion использует `AttachmentUploadCompletionPolicy` для DISCARD/CURRENT_COMPOSER/SOURCE_CHAT.
- [static] **#42:** image и ZIP completion используют ту же policy; image MIME захватывается до async hop.
- [instrumentation] **#43:** persisted SOURCE_CHAT ZIP consume теперь привязан к captured `rawUri + kind`, `preparingZipAttachments` failure-state mutation выполняется на main thread; Android regression tests подтверждают persisted consume и failure path.
- [instrumentation] **#44:** `renderVectorStoreList(vsId)` безопасен при вызове из off-main Executor callback: начальная UI mutation и повторный render проходят через внешний `runOnUiThread` gate; ActivityScenario regression test и post-merge Debug/Release подтверждены. **Контракт #44 закрыт PR #46**; PR #44 и PR #45 были отдельными отрицательными verifier-checks и не закрывали этот lifecycle-контракт.
- [robolectric] **#57:** миграция ChatDatabase с v13/v14 до v16 сохраняет active-response ownership/cursor и добавляет `messages.attachments` без поломки существующей истории; PR-head Debug `36826110992` прошёл эти lifecycle tests, а Release `36826110999` также завершился success.

Не переоткрывай эти задачи без нового доказательства регрессии.

Тег = **сильнейший реально выполненный harness для этого конкретного утверждения**, а не доказательство всего callback/runtime-пути целиком. Если pure-policy покрыта JVM-тестом, это не делает wiring-код, который её вызывает, `[pure]`. Поэтому отдельный wiring без runtime-теста остаётся `[static]` даже при наличии соседнего pure-теста.

- `[instrumentation]` — Android instrumentation/ActivityScenario, выполнявшийся на эмуляторе или устройстве; это не означает физическое устройство.
- `[robolectric]` — Android/SQLite-сценарий через Robolectric, без реального emulator/device.
- `[pure]` — JVM/pure-logic тест без Android runtime.
- `[static]` — утверждение подтверждено только статической/source-level/compile-time проверкой или verifier-wiring; отдельного runtime-теста именно этого утверждения нет.

Для thread-safety audit это различие является частью доказательства: тег показывает уровень реально пройденного harness, но не разрешает переносить proof с соседней policy на весь async callback path.

**Перепроверка 2026-09-26:** текущий `main` test tree проверен целиком (JVM + instrumentation), а не по PR diff: #20 — тестирует `markUserStop()`/state contract, но не вызывает `stopCurrentResponse()`; #37 — отдельного теста `ActiveResponsePersistence` adapter wiring нет; #40 — reasoning runtime wiring callback не гоняется; #42 — есть ZIP/persisted consume regression и pure policy tests, но нет runtime-теста именно image/ZIP completion wiring через policy. Поэтому `[static]` для #20/#37/#40/#42 оставлен без изменения.

## АРХИТЕКТУРНЫЕ ПРИНЦИПЫ — стабильные правила

- `ResponseEventReducer` — response semantics; `ResumableResponseStreamClient` — transport + sequence cursor; `ActiveResponsePersistence` — persisted active-response boundary. `ResponseCheckpointPolicy`, `BackgroundResponseUiOwnershipPolicy` и `AttachmentUploadCompletionPolicy` остаются pure decision seams.
- Async callbacks должны сначала захватывать immutable ownership data; UI mutation выполняется через main-thread lifecycle gate.
- `chatId + generation` недостаточны там, где callback меняет конкретный assistant item; для этого нужен immutable `chatId + generation + assistantItemId` snapshot. Tool label — отдельный global Activity state.
- Composer/upload mutable lists и flags сейчас преимущественно UI-thread-owned. Не добавлять synchronized/Concurrent коллекции без доказанного cross-thread access.
- Persistence extraction должна начинаться с pure snapshot/codec или одной доказанной transactional operation, а не с generic controller framework.
- Compose — следующий UI-слой только после state/controller extraction; не смешивать Compose migration с upload/persistence refactors.

## АКТИВНЫЙ ПЛАН — только незавершённое

### 1. Thread-safety audit — **следующий**
После PR G пройти новые и существующие фоновые операции attachments, ZIP-распаковки и проверки model capabilities.
Первый шаг: составить таблицу ownership для новых background операций и выбрать один доказуемый violation; не добавлять массовую синхронизацию.

### 2. PR H — документация
Обновить human-facing README про OpenRouter/Claude, attachments, local artifacts и ручной CI.
Первый шаг: сделать только документационный snapshot после закрытия thread-safety audit.

## Репозиторий / branches

После #35–#37 накопились локальные feature branches, многие из которых соответствуют уже merged PR. Их удаление **не удаляет commits или PR history** и обычно полезно для hygiene. В текущем GitHub connector нет операции DELETE git ref, поэтому автоматически удалить их здесь нельзя; активным сейчас является только `main` плюс открытые PR branches. Перед ручным удалением ориентируйся на merged/closed PR state, а не на имя ветки.


## Контекстный режим для агента

Чтобы не перегружать рабочую память:

- В начале читай **этот файл**, а не полный README.
- README открывай только когда меняется человекочитаемое описание проекта.
- Для кода сначала fetch конкретных файлов/символов; полный `MainActivity.kt` нужен только при действительно сквозном анализе.
- CI logs запрашивай только для конкретного failing run/step.
- Историю старых PR читай точечно, когда нужен конкретный invariant.
- Не тащи в текущий контекст завершённые задачи как активные.
- Краткий пользовательский ответ не означает сокращённого инженерного анализа.


## Как проверять новую работу

**Принцип для нового verifier/lint кода:** проверяй инварианты («ровно один», «ровно N»), а не конкретные текущие значения (какой пункт активного плана сейчас «следующий», какие PR — последние 5) — привязка к текущим значениям уже дважды ломала `tools/verify_handoff.py` на следующем же PR.

Перед изменением:
- найти существующий invariant/test;
- локализовать реальную границу риска;
- понять, какой state владеет request;
- определить, нужен ли runtime fix или только тест/документация.

После изменения:
- поискать старые symbols/helpers;
- реально запустить static verifier и любой новый/изменённый verifier-скрипт, дождаться фактического вывода команды; чтение кода вместо запуска не считается проверкой;
- если менялся сам NEXT_AGENT_HANDOFF.md — отдельно прогнать `python3 tools/verify_handoff.py`, даже если сам скрипт не менялся;
- если PR меняет сам verifier/lint-скрипт — дополнительно намеренно воспроизвести целевую поломку и убедиться, что скрипт её ловит, затем вернуть корректное состояние и снова получить PASS (позитивный и негативный кейс обязательны оба);
- для нового verifier/lint-инварианта негативная проверка — это **намеренная mutation matrix именно этого правила**: как минимум три целевых поломки (например, убрать gate, вынести защищаемую mutation за gate, вернуть прежний баг) должны быть отклонены verifier-ом; случайная syntax error/build failure не считается доказательством. Mutation harness хранится в репозитории и запускается в CI вместе с позитивным кейсом;
- при падении CI/проверки приводить одну дословную строку из реального failing log (или явное `AssertionError:`/step output), а не только пересказ места остановки;
- дождаться реального CI результата;
- если merge — проверить post-merge runs, когда connector их предоставляет; если нет — явно зафиксировать ограничение;
- обновить план и историю.

### Командная шпаргалка

Debug:
`gradle --no-daemon --stacktrace test lintDebug assembleDebug`

Release:
`gradle --no-daemon --stacktrace test lintRelease assembleRelease bundleRelease`

Static verifier:
`python3 tools/verify_v4_17_0.py`



## История последних рабочих заходов — последние 5 PR

- **#54:** merged `d262ea8e14554527c0bdaf9eef8b56da76fdb6af`; PR-head Release `#191` — success; Debug `#369` — success incl. `Verify handoff`, verifier mutation matrix and Android lifecycle instrumentation. Tool traces are now persisted in assistant messages and restored into OpenRouter history.
- **#55:** merged `16b712112e2710c8725de4203d75f23c51e15ad6`; PR-head Release `#196` — success; Debug `#374` — success; post-merge Debug `#376` — success incl. `Verify handoff`, verifier mutation matrix and Android lifecycle instrumentation. Anthropic OpenRouter caching uses explicit system breakpoint + top-level automatic breakpoint, `session_id` sticky routing, a 20-minute whole-cycle budget, and a 200000 cumulative-token soft cap.
- **#56:** merged `a1857bfacc56584ea6244fdfafc21ec22793227a`; PR-head Release `#199` — success; Debug `#377` — success incl. `Verify handoff`, verifier mutation matrix and Android lifecycle instrumentation. Anthropic OpenRouter cache breakpoint now requests `ttl: "1h"`; 1-hour writes cost 2.0x input versus 1.25x for the default 5-minute TTL, while cache reads remain 0.1x input. No provider/model-side `ttl: auto` exists.
- **#57:** merged `593009176082f32881d1f310e07bf54a14aaa4b6`; PR-head Release `36826110999` — success; Debug `36826110992` — success incl. handoff verifier, verifier mutation matrix, JVM tests, lint/build and Android lifecycle instrumentation. PR G added local OpenRouter/Claude attachments, multi-select, ZIP-safe extraction, EXIF wire normalization, attachment history manifest, model-modality preflight and `create_local_text_file`.
- **#H1 (current):** проверка внешних image-size limits выявила риск EXIF→PNG для больших JPEG; handoff дополнен подтверждённым v16 lifecycle contract и этим provider-limit finding. Поведенческого изменения пока нет.
## Product radar — идеи с реальной ценностью

Это не backlog и не обещание реализации. Идея попадает сюда только если её можно проверить маленьким шагом и она заметно улучшает ежедневный сценарий.

### 1. Версии ответа + обратимость
Сейчас regenerate/edit — точечные действия над линейным чатом. Следующий уровень — сохранять альтернативы и уметь вернуть чат к состоянию «до этого ответа». Это естественное продолжение fork: пользователь не должен бояться попробовать другой prompt/effort. Первый шаг: pure domain contract для snapshot/branch point + DB test, без UI.

### 2. Context Inspector перед отправкой
Показывать по запросу компактный «что реально уйдёт»: модель, effort, tools, attachments, примерный объём контекста, background mode, выбранные IDs файлов. Это уменьшит загадочность дорогих/долгих запросов и даст объяснимость auto-effort. Первый шаг: immutable preflight snapshot из уже существующего sendMessage().

### 3. Поиск по всей истории
Для длинной локальной истории нужен мгновенный full-text search с переходом прямо к сообщению, а не только список чатов. SQLite уже является source of truth, поэтому Первый шаг: оценить FTS5 на messages(text) и тесты по индексации/удалению/поиску.

### 4. Переиспользование вложений
После локальной библиотеки файлов следующий полезный шаг — «использовать снова» без повторного выбора/ручной загрузки. Ещё сильнее — локальный content hash → reuse уже загруженного файла там, где это безопасно и совместимо с текущей серверной семантикой. Первый шаг: инвентаризация того, какие file_id + local path + mime + size уже можно считать устойчивым cache key; сеть и dedup пока не менять.

### 5. Per-chat configuration profile
Сейчас значительная часть настроек живёт глобально в SharedPreferences. Для рабочего чата полезнее помнить его собственный model/reasoning/tools/verbosity profile, чтобы переключение между чатами не меняло незаметно условия эксперимента. Первый шаг: snapshot только settings metadata в DB, без миграции всех UI-настроек сразу.

### 6. «Продолжить / починить ответ» как отдельный UX
У приложения уже есть сильная recovery-модель. Её можно превратить в пользовательское действие: после incomplete/failed/background interruption предлагать понятные варианты «Продолжить», «Повторить», «Открыть checkpoint». Первый шаг: описать состояния response и разрешённые пользовательские действия как pure policy; это лучше, чем ещё один набор if в MainActivity.

### 7. Fork / ветвление чата
MVP: «Ветвить отсюда» на выбранном сообщении создаёт независимый local snapshot до этой точки. Первый шаг: pure DB/domain contract + tests. Обязательно: новый `chatId`, новые local records, не переносить active response / Worker / temporary UI state, и сначала подтвердить актуальную семантику Responses API для продолжения ровно от fork point.

**Черновик идей — без фильтра, до следующего обзора**
Сюда можно писать сырое: без доказанной ценности и без первого шага — это специально разрешено, чтобы не отсеивать идею раньше, чем она успела оформиться. Максимум 5 строк. На каждом архитектурном/продуктовом обзоре: по каждой старой записи — либо дожать её до формата Product radar (с «Первый шаг:») и перенести выше, либо вычеркнуть одной строкой почему; добавить не больше одной новой записи, обязательно вдохновлённой взглядом наружу — как эту же проблему решает другой multi-chat/Responses API клиент, либо какая уже существующая pure-архитектура (`ResponseEventReducer`, `ActiveResponsePersistence`, `AttachmentUploadCompletionPolicy`) недоиспользуется и что даст её переиспользование иначе.

(пусто — заполнится на следующем обзоре)

### Что сознательно не тянуть сейчас
- MCP и Computer Use — пока нет доказанной продуктовой потребности и это резко увеличивает execution surface.
- Полный Compose rewrite — инфраструктурная стадия, не пользовательская функция.
- «AI сам организует чаты/саммари/теги» — выглядит эффектно, но пока не доказано, что это важнее поиска, обратимости и прозрачности контекста.
- Синхронизация между устройствами — отдельная security/backend задача, не естественное продолжение текущей local-first архитектуры.

## Документация

**README** = человекочитаемый snapshot проекта.

**NEXT_AGENT_HANDOFF** = операционный контекст следующей модели.

Жизненный цикл незавершённой работы:
`plan → implementation → CI/check → merge → history`.

Если идея признана ненужной/ошибочной/заменённой — удалить из плана. Не сохранять ради количества задач.

## Короткий отчёт пользователю

После рабочего захода достаточно 3–6 строк:
- что изменено;
- почему;
- что подтвердили тесты/CI;
- что осталось;
- новый архитектурный вывод, если он появился.

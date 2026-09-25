# NEXT_AGENT_HANDOFF

**Last updated:** 2026-09-25 21:21 +03:00  
**Следующий рабочий срез:** thread-safety audit — сначала детерминированно проверить off-main UI mutation в Vector Store delete callback и составить таблицу оставшихся `Executor/Worker → Activity` путей; generic controller extraction не начинать до ясной ownership boundary.

# START HERE — ОБЯЗАТЕЛЬНО

**Это основной документ для агента. README на старте не нужен.**

1. Начинай с фактического `main` и открытых PR.
2. Один рабочий заход = ориентир **10–25 минут**: понять → изменить/проверить → оставить понятный результат.
3. Сначала докажи, что проблема существует. Не делай PR ради уменьшения TODO.
4. Для lifecycle/recovery смотри границу **Activity ↔ Executor ↔ Worker ↔ DB**, а не только один метод.
5. Используй узкие чтения файлов. Не загружай весь README, весь `MainActivity` или полные CI-логи без причины.
6. CI: queued/in_progress ≠ green. Static verifier и Gradle CI — отдельные проверки.
7. После merge проверь post-merge Debug/Release, если GitHub Actions предоставляет эти runs.
8. После завершения задачи: план → история; устаревшее удалить, а не оставлять вечным backlog.
9. Архитектурный/продуктовый обзор делай **каждые 5 merged PR или раньше, если активный план опустел/сменил слой**. Новая идея нужна только при конкретной ценности + маленьком проверяемом первом шаге.
10. **Не теряй общую картину:** Compose, controller extraction, thread-safety и fork chat — это разные уровни одной дорожной карты, а не взаимозаменяемые TODO.

## 30-секундная карта проекта

**Продукт:** локальный multi-chat Android-клиент с Responses API, tools/files/images и streaming.

**Надёжность:** основная lifecycle-модель:
`generation → request/response ownership → immutable UI snapshot → persisted active-response state → WorkManager recovery`.

**Persistence:** DB schema v15; важные поля:
`active_request_id`, `active_response_id`, `active_text`, `active_status`, `active_background_enabled`, `active_last_sequence_number`.
Устаревший request не должен финализировать persisted state.

**Тесты:** JVM/pure contracts + Robolectric SQLite покрывают большую часть lifecycle/persistence. #35 добавил реальный Android ActivityScenario harness для callback после Stop/Delete. #36 добавил общий pure response-event reducer и прогнал его через foreground + background paths. #37 вынес active-response DB access за persistence adapter.

**Архитектура:** `MainActivity.kt` всё ещё ~4.5k строк. `ResponseEventReducer` владеет response semantics, `ActiveResponsePersistence` — boundary к persisted active-response state, `ResponseCheckpointPolicy` — pure timing/status/payload decision для checkpoint, `BackgroundResponseUiOwnershipPolicy` — pure UI ownership decision для принятого background snapshot, а `AttachmentUploadCompletionPolicy` — pure routing decision для async attachment completion. Background checkpoint, assistant-text/reasoning UI paths и file/image/ZIP attachment completion routing уже переведены на эти seams; foreground cadence намеренно не менялась. Tool-state path оставлен на `chatId + generation`, потому что он обновляет глобальный Activity tool label, а не конкретный assistant item. Compose остаётся следующим UI-слоем после state/controller extraction.

**Продуктовый радар:** см. отдельный раздел; это идеи-кандидаты, а не backlog.

## Фактический baseline

- main code baseline: `39b5d01b3cc6a395d1ddccc0cc4f77ba2b32c996` (PR #43 squash)
- versionName/code: `4.17.0 / 64`
- DB: `v15`
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

- **#19:** foreground и resumable SSE используют строгий sequence-gap contract.
- **#20:** Stop привязан к generation конкретного request.
- **#23:** `ActiveResponseStateStore.state` — `@Volatile` immutable snapshot.
- **#25:** user-stop инвалидирует все поколения до последнего user-stop; повторный Stop не забывает старые поколения.
- **#27:** background resume захватывает immutable `chatId + generation + assistantItemId` snapshot; callback не читает «текущую» generation постфактум.
- **#29:** Stop-before-create, stale Worker finalization и ownership проверяются через детерминированные contracts.
- **#31:** background recovery запускается только при явной eligibility policy.
- **#32:** реальные SQLite/Robolectric проверки v13→v15, v14→v15, reopen partial response/cursor и stale-request DB protection.
- **#34:** Delete удаляет effort telemetry вместе с chat в одной транзакции; поздний metric callback больше не может заново создать telemetry для удалённого chat.
- **#35:** реальный Android ActivityScenario подтверждает положительный callback path в RESUMED и rejection того же mutation path после Stop и Delete; PR-head Debug/Release зелёные.
- **#36:** `ResponseEventReducer` содержит общую semantics для response.created, text/reasoning deltas и terminal states; foreground и background paths используют один reducer. Sequence cursor намеренно не дублируется в reducer.
- **#37:** `ActiveResponsePersistence` изолирует set/update/get/list/clear active-response state; `ChatDatabaseActiveResponsePersistence` сохраняет прежнюю request-aware DB реализацию.
- **#38:** `ResponseCheckpointPolicy` изолирует pure checkpoint decision: interval vs terminal, persisted status/text/responseId/sequence; background recovery перенесён на policy, foreground 750ms cadence не менялась.
- **#39:** `BackgroundResponseUiOwnershipPolicy` изолирует pure decision для применения background UI snapshot по `chatId + generation + assistantItemId + lifecycle`.
- **#40:** reasoning background callback использует тот же immutable UI snapshot ownership contract и передаёт `assistantItemId` из snapshot в state mutation.
- **#41:** file upload completion использует `AttachmentUploadCompletionPolicy` для DISCARD/CURRENT_COMPOSER/SOURCE_CHAT.
- **#42:** image и ZIP completion используют ту же policy; image MIME захватывается до async hop.
- **#43:** persisted SOURCE_CHAT ZIP consume теперь привязан к captured `rawUri + kind`, `preparingZipAttachments` failure-state mutation выполняется на main thread; Android regression tests подтверждают persisted consume и failure path.

Не переоткрывай эти задачи без нового доказательства регрессии.

## АРХИТЕКТУРНЫЕ ПРИНЦИПЫ — стабильные правила

- `ResponseEventReducer` — response semantics; `ResumableResponseStreamClient` — transport + sequence cursor; `ActiveResponsePersistence` — persisted active-response boundary. `ResponseCheckpointPolicy`, `BackgroundResponseUiOwnershipPolicy` и `AttachmentUploadCompletionPolicy` остаются pure decision seams.
- Async callbacks должны сначала захватывать immutable ownership data; UI mutation выполняется через main-thread lifecycle gate.
- `chatId + generation` недостаточны там, где callback меняет конкретный assistant item; для этого нужен immutable `chatId + generation + assistantItemId` snapshot. Tool label — отдельный global Activity state.
- Composer/upload mutable lists и flags сейчас преимущественно UI-thread-owned. Не добавлять synchronized/Concurrent коллекции без доказанного cross-thread access.
- Persistence extraction должна начинаться с pure snapshot/codec или одной доказанной transactional operation, а не с generic controller framework.
- Compose — следующий UI-слой только после state/controller extraction; не смешивать Compose migration с upload/persistence refactors.

## АКТИВНЫЙ ПЛАН — только незавершённое

### 1. Thread-safety audit — **следующий**
PR #43 закрыл доказанный ZIP/composer regression slice. Следующий слой — только реальные cross-thread ownership violations, без массовой синхронизации.

**Первый шаг:** детерминированно покрыть off-main вызов `renderVectorStoreList(vsId)` из Vector Store delete callback и составить таблицу `state / writer thread / reader thread / lifecycle gate` для оставшихся `Executor/Worker → Activity` paths.

### 2. Attachment/upload/composer controller
Routing slice закрыт в #41–#43; следующий controller slice не вводит новый routing abstraction.

**Первый шаг:** после thread-safety audit выбрать одну bounded composer state/persistence boundary и вынести её как маленький pure snapshot/codec или одну доказанную transactional operation.

### 3. Diagnostics/security
**Первый шаг:** проверить retention/clear policy diagnostics, отсутствие API keys/message bodies в логах, orphan local files и FileProvider scope; сначала аудит, потом точечные fixes.

### 4. Release hardening
**Первый шаг:** проверить minification/shrinker на `RichTextRenderer`, reflection/file handling и release-only paths; signing/publishing не смешивать с runtime cleanup.

### 5. Compose migration
**Первый шаг:** после закрытия controller/persistence slice выбрать один bounded screen-state surface; не начинать полный rewrite.

### 6. Compatibility / build hygiene
**Первый шаг:** отдельно проверить AGP/Kotlin/compileSdk drift и Gradle Wrapper; не смешивать dependency migration с feature PR.

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

Перед изменением:
- найти существующий invariant/test;
- локализовать реальную границу риска;
- понять, какой state владеет request;
- определить, нужен ли runtime fix или только тест/документация.

После изменения:
- поискать старые symbols/helpers;
- проверить static verifier;
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

Handoff verifier:
`python3 tools/verify_handoff.py`

## История последних рабочих заходов — последние 5 PR

- **#39:** merged `a36ff37732931a6aa5ef256e9a03a352395c80a1`; PR-head Release `36067979922` — success; Debug `36067980079` — success incl. instrumentation. Post-merge run не предоставлен connector.
- **#40:** merged `e786d557ec6240b050aef12e0898ac6bb7076fc7`; PR-head Release `36070742037` — success; Debug `36070742104` — success incl. instrumentation. Post-merge run не предоставлен connector.
- **#41:** merged `35ae9fbf1c62a2e855ba25f8a8a9ef6fbae8dc1d`; PR-head Release `36072696907` — success; Debug `36072696947` — success incl. instrumentation. Post-merge run не предоставлен connector.
- **#42:** merged `ae47af930dee1b72669be54be9ad595442dea48a`; PR-head Release `36104592806` — success; Debug `36104592766` — success incl. instrumentation. Post-merge run не предоставлен connector.
- **#43:** merged `39b5d01b3cc6a395d1ddccc0cc4f77ba2b32c996`; PR-head Release `36171645931` — success; Debug `36171645950` — success incl. instrumentation. Post-merge run пока не предоставлен connector.

**Старше:** смотри git history; не тащи старые CI детали в operational handoff без причины.

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

### Что сознательно не тянуть сейчас
- MCP/custom functions и Computer Use — пока нет доказанной продуктовой потребности и это резко увеличивает execution surface.
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

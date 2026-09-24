# NEXT_AGENT_HANDOFF — 2026-09-25

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
9. Периодически делай архитектурный/продуктовый обзор. Новая идея нужна только при конкретной ценности + маленьком проверяемом первом шаге.
10. **Не теряй общую картину:** Compose, controller extraction, thread-safety и fork chat — это разные уровни одной дорожной карты, а не взаимозаменяемые TODO.

## 30-секундная карта проекта

**Продукт:** локальный multi-chat Android-клиент с Responses API, tools/files/images и streaming.

**Надёжность:** основная lifecycle-модель:
`generation → request/response ownership → immutable UI snapshot → persisted active-response state → WorkManager recovery`.

**Persistence:** DB schema v15; важные поля:
`active_request_id`, `active_response_id`, `active_text`, `active_status`, `active_background_enabled`, `active_last_sequence_number`.
Устаревший request не должен финализировать persisted state.

**Тесты:** JVM/pure contracts + Robolectric SQLite покрывают большую часть lifecycle/persistence. #35 добавил реальный Android ActivityScenario harness для callback после Stop/Delete. #36 добавил общий pure response-event reducer и прогнал его через foreground + background paths. #37 вынес active-response DB access за persistence adapter.

**Архитектура:** `MainActivity.kt` всё ещё ~4.5k строк. `ResponseEventReducer` владеет response semantics, `ActiveResponsePersistence` — boundary к persisted active-response state, `ResponseCheckpointPolicy` — pure timing/status/payload decision для checkpoint, а `BackgroundResponseUiOwnershipPolicy` — pure UI ownership decision для принятого background snapshot. Background checkpoint и один assistant-text UI path уже переведены на эти seams; foreground cadence намеренно не менялась. Следующая стадия — расширять ownership transfer только там, где это даёт доказанный выигрыш. Compose остаётся следующим UI-слоем после state/controller extraction.

**Продуктовый радар:** fork чата от выбранного сообщения — кандидат для проверки, не обязательная задача.

## Фактический baseline

- main code baseline: `a36ff37732931a6aa5ef256e9a03a352395c80a1` (PR #39 squash)
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
- **#39:** `BackgroundResponseUiOwnershipPolicy` изолирует pure decision для применения background UI snapshot по `chatId + generation + assistantItemId + lifecycle`; `ActiveResponseState` делегирует snapshot ownership этой policy; только assistant-text background path переведён на неё.

Не переоткрывай эти задачи без нового доказательства регрессии.

## АРХИТЕКТУРНЫЙ ОБЗОР — 2026-09-25

### Что улучшать теперь

1. **Ownership orchestration.** После #38 checkpoint timing/status для background path вынесен в pure policy. Следующий seam — ownership decision вокруг immutable `chatId + generation + assistantItemId` snapshot, чтобы controller не повторно читал mutable Activity state для уже принятого callback.
2. **Foreground checkpoint seam.** Только после ownership contract проверить, есть ли смысл переносить foreground 750ms checkpoint без смешивания его cadence с background 500ms path.
3. **Transport adapter.** SSE/HTTP остаются источником normalized events и sequence semantics; reducer/policy не должны знать OkHttp, Android или WorkManager.
4. **Tool/UI effects — следующим слоем.** Не переносить все tool events в reducer заранее; сначала стабилизировать state + persistence.
5. **Тестировать решение отдельно от adapter.** Pure policy tests → один runtime path → Robolectric/instrumentation только там, где Android lifecycle реально нужен.

### Что уже реализовано в #36–#37

`ResponseEventReducer` + JVM tests покрывают:
- response id creation/changes;
- text delta accumulation;
- reasoning summary delta;
- completed / failed / incomplete / cancelled;
- terminal response / usage / completion error;
- unknown и empty events;
- whitespace-preserving deltas.

#37 добавил `ActiveResponsePersistence` без изменения DB schema и request-aware semantics.

## АКТИВНЫЙ ПЛАН — только незавершённое

### 1. Response/recovery controller — следующий срез
Ownership policy закрыт для одного background assistant-text path в #39. Следующий шаг — проверить оставшиеся reasoning/tool UI callbacks и переносить их только при наличии отдельного race/ownership smell; не делать массовую замену автоматически.

### 2. Attachment/upload/composer controller
URI/file/image upload, ZIP extraction, progress, composer persistence.

### 3. Thread-safety audit
Проверить оставшиеся Activity/Executor/Worker границы и shared mutable state. Менять синхронизацию только при доказанном race.

### 4. Diagnostics/security
Diagnostics retention/clear, отсутствие API keys/message bodies, orphan local files, FileProvider least privilege.

### 5. Release hardening
Shrink/minification policy, renderer/reflection/file handling under shrinker; signing/publishing отдельно.

### 6. Compose migration
**Не начинать до state/controller extraction.**
Порядок:
`response reducer/controller → persistence + attachment state → immutable screen state/events → one bounded Compose feature → incremental migration`.
Compose — не «забытый TODO», а отложенная архитектурная стадия.

### 7. Отдельные compatibility tasks
AGP/Kotlin/compileSdk migration и Gradle Wrapper.

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

## История последних рабочих заходов

- **#35 merged:** `6c975658698d24cf40da3874f3c96eaf17da634f`.
- **#35 PR-head CI:** Debug `36054984135` и Release `36054984134` — success.
- **#36 merged:** `409996fe2484d5ac71f096a0331fb9c64ada4ff6`.
- **#36 final PR-head CI:** Release `36060342110` — success; Debug `36060342139` — success, включая lifecycle instrumentation.
- **#37 merged:** `98ca902bd2474129fbb6e95ebfaa23cef8b1cb01`.
- **#37 final PR-head CI:** Release `36062915006` — success; Debug `36062914977` — success, включая lifecycle instrumentation.
- **#38 merged:** `e3b346d78247ccaa08aefbdc31060b65935d827f`.
- **#38 PR-head CI:** Release `36064830714` — success; Debug `36064830967` initially failed only at lifecycle instrumentation, rerun job `107855282931` — success. Static verifier + Gradle test/lint/build were successful on both attempts.
- **#38 post-merge CI:** connector не вернул workflow runs для merge commit `e3b346d...`; post-merge green не заявляется.
- **#39 merged:** `a36ff37732931a6aa5ef256e9a03a352395c80a1`.
- **#39 PR-head CI:** Release `36067979922` — success; Debug `36067980079` — success, включая lifecycle instrumentation.
- **#39 post-merge CI:** connector не вернул workflow runs для merge commit `a36ff377...`; post-merge green не заявляется.
- Во время #36 были исправлены промежуточные integration проблемы, после чего exact-head CI стал зелёным.
- **Post-merge runs:** connector не вернул runs для `409996fe...` и `98ca902b...`; post-merge green не заявляется.

## Product radar: fork chat

MVP-идея: «Ветвить отсюда» на выбранном сообщении создаёт **независимый local snapshot** до этой точки.

Обязательные ограничения для первого шага:
- новый `chatId`;
- не переносить active response / Worker job / temporary UI state;
- copied messages — новые local records;
- не переиспользовать исходный `previous_response_id` вслепую: сначала подтвердить актуальную семантику Responses API для продолжения **ровно от fork point**;
- первый PR лучше сделать pure DB/domain contract + tests, а не сразу UI + network + branch graph.

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

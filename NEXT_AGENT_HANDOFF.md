# NEXT_AGENT_HANDOFF — 2026-09-24

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

**Тесты:** JVM/pure contracts + Robolectric SQLite уже покрывают большую часть lifecycle/persistence. PR #35 добавил реальный Android ActivityScenario harness для callback после Stop/Delete; head CI Debug/Release зелёные.

**Архитектура:** `MainActivity.kt` всё ещё ~4.5k строк. Следующее фундаментальное направление — response/recovery controller extraction. Compose не выброшен: он **следующий UI-слой после extraction**, поэтапно через interop. RecyclerView/AsyncListDiffer сейчас валидны.

**Продуктовый радар:** fork чата от выбранного сообщения — кандидат для проверки, не обязательная задача.

## Фактический baseline

- main code baseline: `6c975658698d24cf40da3874f3c96eaf17da634f` (PR #35 squash)
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

Документальные коммиты после merge не являются новым code baseline.

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
- **#35:** реальный Android ActivityScenario подтверждает положительный callback path в RESUMED и rejection того же mutation path после Stop и Delete; CI на API 34 Debug/Release зелёный.

Не переоткрывай эти задачи без нового доказательства регрессии.

## АКТИВНЫЙ ПЛАН — только незавершённое

### 1. Response/recovery controller
Постепенно вынести из `MainActivity.kt`:
- generation/ownership;
- response lifecycle;
- retry/backoff;
- DB checkpoints;
- Activity ↔ WorkManager coordination.

Сначала общий контракт и тесты, затем маленькие переносы. Не делать big-bang rewrite.

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
`response controller → attachment/composer state → immutable screen state/events → one bounded Compose feature → incremental migration`.
Compose — не «забытый TODO», а отложенная архитектурная стадия.

### 7. Отдельные compatibility tasks
AGP/Kotlin/compileSdk migration и Gradle Wrapper.

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
- если merge — проверить post-merge runs, когда connector их предоставляет; если нет — явно зафиксировать ограничение.
- обновить план и историю.

## История последнего рабочего захода

- **#35 merged:** `6c975658698d24cf40da3874f3c96eaf17da634f`.
- **CI on PR head:** Debug run `36054984135` и Release run `36054984134` — `success`.
- Исправлены две реальные причины CI/test instability: неверная JVM reflection-сигнатура Kotlin default-args в instrumentation test и отсутствие доступа к KVM на emulator runner. Disk pressure также устранялся отдельным cleanup step.

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

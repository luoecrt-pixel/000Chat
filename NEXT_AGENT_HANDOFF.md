# NEXT_AGENT_HANDOFF

**Last updated:** 2026-10-08 09:20 +03:00
**Следующий рабочий срез:** OpenRouter server-side tools

# START HERE — ОБЯЗАТЕЛЬНО

**Это основной документ для агента. README на старте не нужен.**

1. Начинай с фактического `main` и открытых PR.
2. Сначала докажи, что проблема существует. Не делай PR ради уменьшения TODO.
3. Для lifecycle/recovery смотри границу **Activity ↔ Executor ↔ Worker ↔ DB**, а не только один метод.
4. Используй узкие чтения файлов. Не загружай весь README, весь `MainActivity` или полные CI-логи без причины.
5. CI: queued/in_progress/Skipped/Cancelled ≠ green. Static verifier и Gradle CI — отдельные проверки. Правила — в разделе «CI и merge-правила».
6. Пользователь не программист: объясняй просто и коротко; ветки, PR, `[ci]` и merge по гейту делай сам, проси только то, чего коннектор не умеет (настройки GitHub, repository variable, установка и проверка APK на телефоне). Чужие оценки, в том числе другой модели, — гипотезы: проверяй по коду и логам; «зелёным» называй только Successful run.
7. После завершения задачи: план → история; устаревшее удалить, а не оставлять вечным backlog.
8. Архитектурный/продуктовый обзор делай **каждые 5 merged PR или раньше, если активный план опустел/сменил слой**. Новая идея нужна только при конкретной ценности + маленьком проверяемом первом шаге.
9. **Не теряй общую картину:** Compose, controller extraction, thread-safety и fork chat — это разные уровни одной дорожной карты, а не взаимозаменяемые TODO.
10. **Изменения в main только через PR** (включая CI, docs и handoff, даже одной строки). Исключение — если пользователь прямо написал «коммить в main».

## CI и merge-правила

**CI.** Полный прогон (Build с эмулятором и Release, суммарно ≈ 15 мин) идёт только по метке: `[ci]` в сообщении head-коммита push, `CI_AUTO_RUN=true` или ручной `workflow_dispatch`; иначе run = **Skipped**. Точное условие — в `build.yml`/`release.yml`.
- Коннектор не умеет `workflow_dispatch` и repository variables: рычаг агента — `[ci]` в коммите ветки PR. Ставь, когда прогон нужен; перед повтором назови причину прошлого падения строкой из лога. Пользователь может запустить вручную: Actions → Run workflow → ветка.
- Смотри строки `(push)` у Build и Release. `(pull_request)` Skipped — норма; счётчик «4/4» с галочкой может значить «всё пропущено». Skipped, Cancelled, queued, in_progress ≠ green.
- Concurrency group включает имя workflow, ref и событие (иначе Build и Release с одним `[ci]` отменяют друг друга); новый push в ветку отменяет идущий прогон — не пушь, пока он идёт.
- После merge push-runs на main Skipped по дизайну. Для APK или проверки main — ветка от main + пустой коммит `[ci]`; назови run, откуда скачать artifact.
- `Verify handoff` — дешёвый workflow (без Android) на PR, меняющие `NEXT_AGENT_HANDOFF.md` или `tools/**`; docs-коммиты без `[ci]`.

**Merge-гейт.**
1. Код или workflow: оба `(push)` — Build и Release — Successful на последнем коммите, меняющем код/workflow (включая instrumentation); после него допустимы docs-коммиты без `[ci]`, но `Verify handoff` на финальном head должен быть Successful. Docs-only PR: достаточно Successful `Verify handoff`.
2. Описание PR точно перечисляет изменённые файлы, а размер diff соответствует задаче: неожиданно большой diff у большого файла (`MainActivity.kt`) — признак его полной перезаписи.
3. Не мержи, если обязательный run красный, Cancelled или не запускался (Skipped), даже при «Able to merge». Force-push после старта проверок — только с сообщением пользователю и перезапуском проверок.
4. Статус PR — по `merged`/`merged_at`; `merge_commit_sha` у closed PR ничего не доказывает.
5. В отчёте: PR, merge SHA, run ID и результат каждого обязательного run (сверены со страницей GitHub); отдельно — что не проверено.

## 30-секундная карта проекта

**Продукт:** локальный multi-chat Android-клиент. Основной путь — OpenAI Responses API; отдельный путь — OpenRouter Chat Completions для Claude (#47). GitHub-инструменты исполняет само приложение; включаются чекбоксом в настройках (#48).

**OpenAI (основной путь):** foreground Responses без app-side таймаутов (`readTimeout(0)`, `callTimeout(0)`); `max_output_tokens` отправляется только при явной настройке; background recovery — общий потолок 5 ч для Activity и WorkManager (#84).

**Надёжность:** основная lifecycle-модель:
`generation → request/response ownership → immutable UI snapshot → persisted active-response state → WorkManager recovery`.

**Persistence:** DB schema v16; важные поля:
`active_request_id`, `active_response_id`, `active_text`, `active_status`, `active_background_enabled`, `active_last_sequence_number`.
Устаревший request не должен финализировать persisted state.

**Тесты:** JVM/pure contracts + Robolectric SQLite покрывают большую часть lifecycle/persistence; Android ActivityScenario/instrumentation — критичные callback-пути (с #35). Что доказано, а что нет — в разделе контрактов.

**Архитектура:** `MainActivity.kt` ~4.5k строк. Pure seams: `ResponseEventReducer` (response semantics), `ActiveResponsePersistence` (persisted active-response boundary), `ResponseCheckpointPolicy`, `BackgroundResponseUiOwnershipPolicy`, `AttachmentUploadCompletionPolicy`. Foreground cadence 750ms не менялась. Tool-state path остаётся на `chatId + generation`: он обновляет глобальный tool label, а не конкретный assistant item. Compose — после state/controller extraction.

**OpenRouter/Claude (#47–#57, #76, #78, #80, #89, #90, #91):** отдельный Chat Completions client с синхронным tool loop; с #89 transport-level SSE streaming для одного round; без фиксированного лимита раундов и без app-side read/call таймаутов (#80), только аварийный потолок 5 ч / 5M токенов на ответ (#78); `max_tokens` необязателен; partial output при ошибке; usage/cache telemetry (#76); #90 добавляет отдельный каталог source/model и сохраняет requested/response model telemetry + session-scoped request events; репозиторий и ветки GitHub добавляются в system prompt (#50); `tool_trace` сохраняется в историю (#54); `reasoning.effort` нормализуется на границе (#53); запись файла в GitHub принимает обычный UTF-8 `content`, base64 оставлен для совместимости (#52); composer не уходит под клавиатуру (#51). GitHub API вызывает только приложение: allow-list репозитория/веток, write/merge/review/workflow только после подтверждения пользователя, audit log. Prompt caching Anthropic: system как text block + top-level `cache_control`, TTL 1 час (поле `cache_key` в настройках — это OpenAI `prompt_cache_key`, к нему не относится). К OpenAI background recovery OpenRouter не подключён (намеренно). #91 добавляет user-facing session telemetry и Drawer поверх уже сохранённых request_events; UI runtime на реальном устройстве отдельно не подтверждён.

**Вложения (#57, #83):** `content://` сразу копируется в private storage; text/image/pdf/zip готовятся локально; история хранит attachment manifest; OpenAI File API path отдельный и не затронут. Модель может создавать локальные текстовые файлы (UTF-8). Картинки уходят без lossy-сжатия и downscale: EXIF-поворот 3/6/8 — только strict lossless (`ajpegtran:0.1.14`) при идеальной MCU-геометрии, иначе исходные байты; старые manifests с PNG-wire обновляются при чтении. Anthropic принимает до 10 MB base64 на изображение и 32 MB на запрос; через OpenRouter лимиты зависят от provider/model.

## Фактический baseline

- main code baseline: `39a885be73eb0190e54708a360d60118ddc6c57f` (merge #90); #91 основан на этом main и добавляет session telemetry + Drawer; request telemetry теперь также фиксируется при OpenAI background finalization, используя request metadata из persisted user message. Open PR #92 is stacked on #91 and adds OpenRouter server-side web tools + IJG NOTICE.

## Подтверждённые lifecycle/recovery контракты

Единая карта доказанности: подтверждено запуском (контракты ниже) → только source-level → не подтверждено (список в конце раздела). Тег — самый сильный реально выполненный harness именно для этого утверждения; соседний pure/static контракт не переносит proof на runtime wiring.

Теги: instrumentation — эмулятор/ActivityScenario; robolectric — Android/SQLite без эмулятора; pure — JVM-тест без Android runtime; static — только source-level/verifier, runtime-теста этого утверждения нет.

- [pure] **#19:** foreground и resumable SSE соблюдают строгий sequence-gap contract: пропущенная последовательность не принимается как корректное продолжение stream.
- [static] **#20:** Stop привязан к generation конкретного request; state-level contract `markUserStop()` проверен, но прямой runtime-вызов полного `stopCurrentResponse()` в этом proof отсутствует.
- [pure] **#23:** `ActiveResponseStateStore.state` — immutable snapshot под `@Volatile`, поэтому читатели получают цельный state, а не частично изменённую структуру.
- [pure] **#25:** user-stop инвалидирует все поколения до последнего user-stop; повторный Stop не забывает уже инвалидированные поколения.
- [pure] **#27:** background resume захватывает immutable `chatId + generation + assistantItemId` snapshot и не читает «текущую» generation постфактум.
- [pure] **#29:** Stop-before-create, stale Worker finalization и ownership проверяются через детерминированные contracts; stale request не должен финализировать чужое persisted state.
- [pure] **#31:** background recovery запускается только при явной eligibility policy.
- [robolectric] **#32:** реальные SQLite/Robolectric сценарии проверяют миграции v13→v15, v14→v15, reopen partial response/cursor и stale-request DB protection.
- [robolectric] **#34:** Delete удаляет effort telemetry вместе с chat в одной транзакции; поздний metric callback не может заново создать telemetry для удалённого chat.
- [instrumentation] **#35:** реальный Android ActivityScenario подтверждает положительный callback path в RESUMED и rejection того же mutation path после Stop и Delete.
- [pure] **#36:** `ResponseEventReducer` содержит общую response semantics для created/text/reasoning deltas и terminal states; foreground и background используют один reducer.
- [static] **#37:** `ActiveResponsePersistence` изолирует set/update/get/list/clear active-response state; request-aware DB реализация сохранена, но отдельный runtime adapter test не закрыт.
- [pure] **#38:** `ResponseCheckpointPolicy` определяет pure checkpoint decision: interval vs terminal и persisted status/text/responseId/sequence; foreground cadence остаётся 750 ms.
- [pure] **#39:** `BackgroundResponseUiOwnershipPolicy` разрешает background UI snapshot только для совпадающих `chatId + generation + assistantItemId` и допустимого lifecycle.
- [static] **#40:** reasoning background callback использует `assistantItemId` из immutable UI snapshot; отдельный runtime wiring proof этого callback ещё отсутствует.
- [pure] **#41:** file upload completion выбирает DISCARD/CURRENT_COMPOSER/SOURCE_CHAT через `AttachmentUploadCompletionPolicy`.
- [static] **#42:** image и ZIP completion используют ту же policy, image MIME захватывается до async hop; отдельного runtime-теста wiring через policy нет.
- [instrumentation] **#43:** persisted SOURCE_CHAT ZIP consume привязан к captured `rawUri + kind`, а failure-state mutation выполняется на main thread; Android regression подтверждает consume и failure path.
- [instrumentation] **#44:** `renderVectorStoreList(vsId)` безопасен при off-main Executor callback: UI mutation и повторный render проходят через внешний `runOnUiThread` gate; ActivityScenario regression закрыла контракт. PR #44/#45 были verifier-checks, контракт фактически закрыт PR #46.
- [robolectric] **#57:** миграция ChatDatabase до v16 сохраняет active-response ownership/cursor и добавляет `messages.attachments` без поломки истории; lifecycle DB tests прошли на PR head.
- [instrumentation] **#59:** pending GitHub confirmation имеет одного владельца (future/dialog); Stop/onStop/onDestroy завершают решение как `false`; ActivityScenario проверяет Stop против подтверждения.
- [instrumentation] **#60:** ActivityScenario `recreate()` при открытом GitHub-диалоге закрывает старое решение и оставляет новую Activity без pending confirmation.
- [instrumentation] **#61:** Stop отменяет сохранённый `Call` model-capability preflight; helper сохранения вложения в исходный chat работает после навигации. Executor → copy/ZIP → policy отдельно подтверждён #68.
- [instrumentation] **#62:** новое OpenRouter-сообщение отменяет предыдущий active Call и pending GitHub confirmation; request-ownership проверяется до/после HTTP и вокруг tool boundaries.
- [instrumentation] **#67:** stale `onCallCreated` не публикует Call старого request; active Call принадлежит текущему request на Activity boundary.
- [instrumentation] **#68:** блокирующий `AttachmentInputStreamSource` и real-path ActivityScenario для picker-copy/ZIP после навигации прошли на эмуляторе.
- [instrumentation] **#72:** production-владение вложением на main использует captured `chatId + generation`, AtomicInteger-счётчик и lifecycle-gated `endUpload()`; stopped-Activity regression зелёная.
- [instrumentation] **#75:** после upload, завершившегося в stopped Activity, `onStart()` пересчитывает attachment/Send UI; ActivityScenario подтверждает Stop → `endUpload()` → Resume → Send enabled.
- [pure] **#76:** OpenRouter provider usage агрегируется по всем HTTP-раундам tool loop; JVM regression подтверждает client-level usage/cache/reasoning telemetry aggregation.
- [static] **#78:** OpenRouter app-side safety envelope равен 5 часам на весь `complete()` cycle и 5,000,000 накопленных `usage.total_tokens`; это не модельный reasoning limit.
- [pure] **#80:** OpenRouter tool loop не обрывается на 24 раундах и передаёт явный `max_tokens` > 128000 (JVM regression); снятие 10-минутных таймаутов OpenRouter и implicit 128K `max_output_tokens` у direct OpenAI подтверждено только на уровне source.
- [instrumentation] **#83:** EXIF-повёрнутый JPEG (ориентации 3/6/8) при идеальной MCU-геометрии поворачивается строго lossless (`ajpegtran`); mirrored/transpose и неидеальная геометрия уходят исходными байтами; Android instrumentation-тест на реальном EXIF-JPEG + unit-тест политики; старые PNG-wire manifests обновляются из исходного JPEG при чтении.
- [pure] **#84:** общий `BackgroundRecoveryPolicy.MAX_RECOVERY_MS = 5h`; boundary test подтверждает: 5h−1ms ещё не expired, ровно 5h уже expired. Это safety boundary одной response/recovery cycle, не session-wide cap.
- [pure] **#89:** OpenRouter SSE transport на JVM накапливает fragmented text, reasoning_details и tool-call arguments по index; local tool loop стартует только после завершения SSE round; partial text сохраняется при mid-stream error; существующие >24-round и ownership-loss regressions перенесены на streamed transport.
- [robolectric] **#90:** request_events сохраняются в SQLite, изолированы по chat_id, переживают reopen и удаляются вместе с chat; runtime truth фактической модели у реального провайдера этими тестами не доказана.
- [pure] **#91:** regression test на OpenRouter SSE подтверждает накопление `responseModel` из поля `model` stream-чанка; UI telemetry/Drawer runtime на реальном устройстве этим PR не подтверждены.

**Не подтверждено запуском — общая картина пробелов.** Когда пробел закрыт тестом или наблюдением, перенеси его в список выше с тегом.

Только source-level: #20 — тест проверяет state-контракт `markUserStop()`, но не вызывает `stopCurrentResponse()`; #37 — нет теста wiring adapter; #40 — runtime wiring reasoning callback не гоняется; #42 — нет runtime-теста image/ZIP completion wiring через policy; #78 — потолок 5 ч / 5M токенов; #80 — снятие 10-минутных таймаутов и implicit 128K.

Не подтверждено вообще (нет ни теста, ни наблюдения):
1. Очень длинный ответ на реальном провайдере не наблюдался. OpenAI: source-аудит без app-side timeout, реального прогона не было. OpenRouter: #89 теперь покрывает transport-level SSE на JVM, но реальный long-running provider/mobile run всё ещё не наблюдался; Anthropic рекомендует streaming для long-running запросов, особенно при больших `max_tokens`, поскольку некоторые сети могут разрывать idle-соединения; «10 минут» — ориентир/ограничение SDK для non-streaming, а не универсальный лимит модели.
2. Prompt caching (#55/#56): реальный cache hit не наблюдался; минимальный размер кэшируемого префикса для Opus 5.5 не выяснен; эффект 1-часового TTL на стоимость не проверялся. OpenRouter usage/cache telemetry доказана на уровне client (#76); показ в UI не подтверждён.
3. IME/insets (#51): результат на реальном устройстве с Android 15 не подтверждён.
4. Вложения Claude (#57, #83): чтение моделью картинки/PDF/текста и multi-select через `clipData` не подтверждены на реальной модели; JPEG с неидеальной геометрией или mirrored/transpose-ориентацией уходит как есть — трактовка EXIF-orientation провайдерами не проверена; память/время lossless-поворота на больших фото не измерялись. Для `ajpegtran:0.1.14` отдельно проверены published license metadata и IJG attribution requirements; release APK/AAB overhead измерен, но project-side notice/attribution ещё не зафиксирован как отдельный compliance contract.
5. GitHub-инструменты: read подтверждён живым использованием; реальный write (комментарий, review, merge, запись файла, workflow dispatch) не выполнялся — проверены только подтверждение и отмена (#59/#60).
6. Остальные async-границы `MainActivity` вне четырёх аудированных (подтверждение при Stop и rotation, фоновые пути PR G, tool loop OpenRouter) не аудировались: аудит не называть завершённым.

**Не переоткрывай закрытые lifecycle/recovery контракты без нового доказательства регрессии.**

## АРХИТЕКТУРНЫЕ ПРИНЦИПЫ — стабильные правила

- `ResponseEventReducer` — response semantics; `ResumableResponseStreamClient` — transport + sequence cursor; `ActiveResponsePersistence` — persisted active-response boundary. `ResponseCheckpointPolicy`, `BackgroundResponseUiOwnershipPolicy` и `AttachmentUploadCompletionPolicy` остаются pure decision seams.
- Async callbacks должны сначала захватывать immutable ownership data; UI mutation выполняется через main-thread lifecycle gate.
- `chatId + generation` недостаточны там, где callback меняет конкретный assistant item; для этого нужен immutable `chatId + generation + assistantItemId` snapshot. Tool label — отдельный global Activity state.
- Composer/upload mutable lists и flags сейчас преимущественно UI-thread-owned. Не добавлять synchronized/Concurrent коллекции без доказанного cross-thread access.
- Gate, отбрасывающий UI-мутацию в stopped Activity, требует реконсиляции состояния при возврате (`onStart()`), иначе мутация теряется (пример: #75, `refreshAttachChips()`).
- Не добавлять искусственные app-side лимиты на длину/время одного ответа (раунды tool loop, read/call timeout, default `max_output_tokens`): единственные потолки — аварийные, 5 ч на ответ/цикл восстановления и 5M токенов на цикл OpenRouter (#78, #80, #84).
- Persistence extraction должна начинаться с pure snapshot/codec или одной доказанной transactional operation, а не с generic controller framework.
- Compose — следующий UI-слой только после state/controller extraction; не смешивать Compose migration с upload/persistence refactors.

## АКТИВНЫЙ ПЛАН — только незавершённое

### 1. Завершить PR #91/#92 и провести merge-gates — **следующий**
Цель: закрыть session telemetry + Drawer, OpenRouter server-side web tools и IJG notice только после подтверждённого code CI и финального handoff verification; не объявлять Skipped/unknown как green.
Первый шаг: дождаться подтверждаемого Successful push Build + Release для последнего code-changing head PR #91, затем выполнить тот же gate для #92/стековой интеграции; текущий connector не даёт workflow_dispatch и показывает для commit только PR-triggered runs, поэтому push-runs нельзя выдумывать или считать по косвенным признакам.

### 2. Реальные прогоны провайдеров
Цель: подтвердить на живом приложении длинный OpenAI response, длинный Claude/OpenRouter response, server-side web search/fetch и один длинный GitHub-диалог с Claude; зафиксировать длительность, usage, cache, server-tool поведение, обрыв/завершение и фактическую response model.
Первый шаг: после появления возможности запустить APK пользователь делает три прогона: длинный OpenAI, длинный Claude/OpenRouter с server-side web search или fetch, затем длинный GitHub-диалог с Claude; агент записывает только фактические наблюдения.

### 3. UI/device smoke для PR #91–#92
Цель: подтвердить на реальном Android-девайсе Drawer, telemetry card, copy/select, rename/delete, IME/insets и OpenRouter server-tool toggles.
Первый шаг: после получения APK переключить 2–3 сессии, раскрыть history, скопировать event, переименовать/удалить чат, открыть OpenRouter settings и отдельным запросом проверить web search/fetch.

## Репозиторий / branches

Локальных feature branches накопилось много; удаление веток не удаляет коммиты и PR. Ограничения коннектора: нет DELETE ref, нет `workflow_dispatch`, нельзя создавать/менять repository variables; можно читать runs/логи и перезапускать существующие runs (перезапуск Skipped-run job не включит). Чего коннектор не умеет — проси у пользователя конкретным шагом.

## Контекстный режим для агента

Работай от фактического состояния репозитория и проверяй утверждения по source, тестам и CI. План — это рабочая гипотеза, а не приказ: сначала установи, что проблема существует, и выбирай следующий шаг по реальной доказательной ценности.
Не расширяй claim доказательств: уровень harness должен соответствовать конкретному утверждению. При конфликте между старым описанием и текущим кодом/логами доверяй текущему коду и фактическим проверкам.

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
- если менялся `NEXT_AGENT_HANDOFF.md` — `python3 tools/verify_handoff.py` (если среда позволяет) и обязательно Successful `Verify handoff` на PR; правки handoff без `[ci]`;
- если PR меняет сам verifier/lint-скрипт — дополнительно намеренно воспроизвести целевую поломку и убедиться, что скрипт её ловит, затем вернуть корректное состояние и снова получить PASS (позитивный и негативный кейс обязательны оба);
- для нового verifier/lint-инварианта негативная проверка — это **намеренная mutation matrix именно этого правила**: как минимум три целевых поломки (например, убрать gate, вынести защищаемую mutation за gate, вернуть прежний баг) должны быть отклонены verifier-ом; случайная syntax error/build failure не считается доказательством. Mutation harness хранится в репозитории и запускается в CI вместе с позитивным кейсом;
- при падении CI/проверки приводить одну дословную строку из реального failing log (или явное `AssertionError:`/step output), а не только пересказ места остановки;
- дождаться реального CI результата по «CI и merge-правила»;
- тесты не лезут в `MainActivity` рефлексией (`by lazy`, `internal`, name mangling меняют имена) — нужен test-visible API; ContentProvider в androidTest не использовать (`NoClassDefFoundError: kotlin/jvm/internal/Intrinsics`) — инжектируемый источник, как `AttachmentInputStreamSource`;
- тест обязан дойти до проверяемого пути: реальные предусловия (непустой API key, `beginUpload(...)` вместо прямой записи счётчика), иначе он ничего не доказывает;
- обновить план и историю.

### Командная шпаргалка

Debug:
`gradle --no-daemon --stacktrace test lintDebug assembleDebug`

Release:
`gradle --no-daemon --stacktrace test lintRelease assembleRelease bundleRelease`

Static verifier:
`python3 tools/verify_v4_17_0.py`



Это не полный реестр PR. Здесь остаются только последние 5 merged PR; старые доказательства должны читаться через карту проекта и раздел подтверждённых контрактов по номерам PR.

## История последних рабочих заходов — последние 5 PR

- **#87:** merged; docs-only: раздел контрактов переписан как карта доказанности; `Verify handoff` Successful.
- **#88:** merged; docs-only: восстановлен обязательный `## Контекстный режим для агента`; `Verify handoff` Successful.
- **#89:** merged; OpenRouter Chat Completions SSE streaming; Build/Release/Verify handoff Successful.
- **#90:** merged; provider/model telemetry + request_events persistence; Build/Release/Verify handoff Successful.
- **#91:** code complete; session telemetry card + Drawer + formatter regressions; дополнительно восстановлен OpenRouter streamed `responseModel` capture; merge ожидает подтверждённый green push CI и финальный `Verify handoff`.

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

# NEXT_AGENT_HANDOFF

**Last updated:** 2026-10-05 15:20 +03:00
**Следующий рабочий срез:** OpenAI provider-level long-thinking / very-long response validation

# START HERE — ОБЯЗАТЕЛЬНО

**Это основной документ для агента. README на старте не нужен.**

1. Начинай с фактического `main` и открытых PR.
2. Сначала докажи, что проблема существует. Не делай PR ради уменьшения TODO.
3. Для lifecycle/recovery смотри границу **Activity ↔ Executor ↔ Worker ↔ DB**, а не только один метод.
4. Используй узкие чтения файлов. Не загружай весь README, весь `MainActivity` или полные CI-логи без причины.
5. CI: queued/in_progress/Skipped/Cancelled ≠ green. Static verifier и Gradle CI — отдельные проверки. Правила — в разделе «CI и merge-правила».
6. После merge push-runs Build/Release на main будут Skipped (в merge-коммите нет `[ci]`) — это не проверка и не провал; опирайся на реальные Successful run'ы ветки PR.
7. После завершения задачи: план → история; устаревшее удалить, а не оставлять вечным backlog.
8. Архитектурный/продуктовый обзор делай **каждые 5 merged PR или раньше, если активный план опустел/сменил слой**. Новая идея нужна только при конкретной ценности + маленьком проверяемом первом шаге.
9. **Не теряй общую картину:** Compose, controller extraction, thread-safety и fork chat — это разные уровни одной дорожной карты, а не взаимозаменяемые TODO.
10. **Изменения в main только через PR:** прямые коммиты/push в `main` запрещены, включая CI, docs и handoff. Любое изменение, даже одной строки, сначала делается в ветке и идёт через PR. Единственное исключение — если пользователь прямо написал «коммить в main». Формулировка «без код-ревью» сама по себе PR не отменяет; она может означать, например, изменение repository variable в настройках без коммита.

## CI и merge-правила

- Полный прогон (Build с эмулятором ≈ 9–13 мин, Release ≈ 4–5 мин) идёт только по метке: `[ci]` в сообщении head-коммита push, `CI_AUTO_RUN=true` или ручной `workflow_dispatch`; иначе run = **Skipped** (минуты не тратятся). Точное условие — в `build.yml`/`release.yml`.
- Коннектор не умеет `workflow_dispatch` и repository variables, поэтому рычаг агента — `[ci]` в коммите ветки PR: ставь, когда прогон нужен, и перед повтором назови причину прошлого падения строкой из лога. Пользователь может запустить вручную: Actions → Run workflow → ветка.
- На странице PR смотри `(push)` у Build и Release; `(pull_request)` Skipped — норма, а счётчик «4/4» с галочкой может значить «всё пропущено». Новый push в ветку отменяет идущий прогон — не пушь, пока он идёт.
- После merge push-runs на main Skipped по дизайну. Свежий APK оттуда не собирается: для APK или проверки main — ветка от main + пустой коммит `[ci]`, назови run, откуда скачать artifact.
- `Verify handoff` — дешёвый workflow (без Android) на PR, меняющие `NEXT_AGENT_HANDOFF.md` или `tools/**`.

**Merge-гейт.**
1. Код или workflow: оба `(push)` — Build и Release — на последнем коммите ветки Successful, включая instrumentation. Docs-only (handoff/README): достаточно Successful `Verify handoff`, `[ci]` не нужен.
2. Описание PR точно перечисляет изменённые файлы — сверь с Files changed (#70 влит с «No runtime code changes» при правках `MainActivity.kt`).
3. Не мержи при красном/Cancelled/Skipped обязательном run, даже при «Able to merge» (#70 влит с красным Build). Force-push после старта проверок — только с сообщением пользователю и перезапуском проверок.
4. В отчёте: PR, merge SHA, run ID и результат каждого обязательного run; отдельно — что не проверено.

## Пользователь и стиль работы

- Не программист: объясняй просто и коротко; ветки, PR, `[ci]` и merge по гейту делай сам. Проси только то, чего коннектор не умеет: настройки GitHub, repository variable, установку и проверку APK на телефоне.
- Решения пользователя: картинки уходят в оригинале, без lossy-сжатия и тихого downscale; предупреждения о размере вложений не нужны (при отказе провайдера — понятная ошибка с размером).
- Стоимость важна: один ответ Opus (6 раундов, 7 файлов, среди них `MainActivity.kt` ~4.5k строк) стоил ≈ $10 по оценке пользователя — каждый раунд заново отправляет историю. Читай файлы узко.
- Пользователь сверяет работу с другим ассистентом и скриншотами GitHub. Чужие оценки — гипотезы: проверяй по коду и логам, не соглашайся рефлекторно; «зелёным» называй только Successful run.

## 30-секундная карта проекта

**Продукт:** локальный multi-chat Android-клиент. Основной путь — OpenAI Responses API; отдельный путь — OpenRouter Chat Completions для Claude (#47). GitHub-инструменты исполняет само приложение; включаются чекбоксом в настройках (#48).

**Надёжность:** основная lifecycle-модель:
`generation → request/response ownership → immutable UI snapshot → persisted active-response state → WorkManager recovery`.

**Persistence:** DB schema v16; важные поля:
`active_request_id`, `active_response_id`, `active_text`, `active_status`, `active_background_enabled`, `active_last_sequence_number`.
Устаревший request не должен финализировать persisted state.

**Тесты:** JVM/pure contracts + Robolectric SQLite покрывают большую часть lifecycle/persistence; Android ActivityScenario/instrumentation — критичные callback-пути (с #35). Что доказано, а что нет — в разделе контрактов.

**Архитектура:** `MainActivity.kt` ~4.5k строк. Pure seams: `ResponseEventReducer` (response semantics), `ActiveResponsePersistence` (persisted active-response boundary), `ResponseCheckpointPolicy`, `BackgroundResponseUiOwnershipPolicy`, `AttachmentUploadCompletionPolicy`. Foreground cadence 750ms не менялась. Tool-state path остаётся на `chatId + generation`: он обновляет глобальный tool label, а не конкретный assistant item. Compose — после state/controller extraction.

**OpenRouter/Claude (#47–#57):** отдельный Chat Completions client с синхронным tool loop; после PR #80 нет фиксированного лимита раундов, partial output при ошибке; репозиторий и ветки GitHub добавляются в system prompt; `tool_trace` сохраняется в историю; `reasoning.effort` нормализуется на границе. GitHub API вызывает только приложение: allow-list репозитория/веток, write/merge/review/workflow только после подтверждения пользователя, audit log. Prompt caching Anthropic: system как text block + top-level `cache_control`, TTL 1 час (поле `cache_key` в настройках — это OpenAI `prompt_cache_key`, к нему не относится). К OpenAI background recovery OpenRouter не подключён (намеренно).

**Вложения (#57):** `content://` сразу копируется в private storage; text/image/pdf/zip готовятся локально; история хранит attachment manifest; OpenAI File API path отдельный и не затронут. Модель может создавать локальные текстовые файлы (UTF-8).

**Размер изображений:** Anthropic принимает до 10 MB base64 на изображение и 32 MB на запрос; через OpenRouter лимиты зависят от provider/model. EXIF→PNG для non-normal orientation может многократно раздуть байты. Lossy-сжатие не добавлять до отдельного решения пользователя.

**Продуктовый радар:** см. отдельный раздел; это идеи-кандидаты, а не backlog.

## Фактический baseline

- main code baseline: `4a17af3d967e1d50a93b9739c188df66fbe9396d` (PR #84 runtime merge); current main HEAD is `93d61b851c88fdaf19be38d4cbd3b132bdf44d69` with docs-only #85 and #86 on top

Последний рабочий срез 2026-10-03:
- PR #76 — merged; push Build `37111989401` and Release `37111989339` both completed Successful. Build included Android lifecycle instrumentation; `Verify handoff` #37111992407 also completed Successful.
- PR #75 — merged; push Build `37027054462` and Release `37027053533` both completed Successful. The foreground-resume upload-gate regression passed on real Android instrumentation.
- PR #74 — merged; lightweight `Verify handoff` workflow for `NEXT_AGENT_HANDOFF.md` and `tools/**`, without Android.
- PR #73 — merged; handoff update (docs-only: `Verify handoff` Successful, full Build/Release Skipped by design).
- PR #72 — merged; final push Build `37005211862` and Release `37005211936` both completed Successful.

## Проверка OpenAI лимитов — 2026-10-05

- **Source audit complete:** OpenAI Responses foreground transport uses `readTimeout(0)` and `callTimeout(0)`; `max_output_tokens` is sent only when the user explicitly configures it; OpenAI File API uploads use the original local file bytes; OpenAI built-in tools do not use the OpenRouter-style local 24-round loop.
- **Found and fixed:** `MainActivity.streamBackgroundResponse()` had an app-side 30-minute recovery deadline while `BackgroundResponseWorker` already used 5 hours.
- **PR #84 merged:** `4a17af3d967e1d50a93b9739c188df66fbe9396d`.
- Full push **Build** `37305367442` — Successful.
- Full push **Release** `37305367459` — Successful.
- `Verify handoff` `37305373719` — Successful on the final handoff commit before merge.
- **Conclusion:** no remaining client-side artificial time/output cap was found in the audited OpenAI path. The 5-hour ceiling is an app safety boundary for one response/recovery cycle, not a session-wide/model-provider reasoning limit.

## EXIF JPEG wire policy — fully verified

- **PR #83 merged:** `b02150a5cc59de02b33742c8a2928184ad8c8caf`.
- Full push **Build** `37303250725` — Successful, including Android lifecycle instrumentation.
- Full push **Release** `37303250886` — Successful.
- Runtime contract: JPEG EXIF rotations use strictly lossless jpegtran transforms only when MCU geometry is perfect; mirrored/transpose or non-perfect geometry keeps the original JPEG/EXIF bytes; no lossy compression and no silent downscale.
- Legacy manifests that previously stored JPEG as PNG wire are refreshed from the original JPEG when read.

## Подтверждённые lifecycle/recovery контракты

Единая карта доказанности: ниже каждый подтверждённый контракт оформлен как отдельная проверяемая гарантия с **одним** proof tag. Тег показывает самый сильный реально выполненный harness именно для этого утверждения; соседний pure/static контракт не переносит proof на runtime wiring.

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
- [static] **#80:** удалены app-side 10-minute HTTP и 24-round OpenRouter caps; Direct OpenAI не навязывает legacy 128K `max_output_tokens` при пустой настройке; background recovery поднят с 30 минут до 5 часов.
- [pure] **#84:** общий `BackgroundRecoveryPolicy.MAX_RECOVERY_MS = 5h`; boundary test подтверждает: 5h−1ms ещё не expired, ровно 5h уже expired. Это safety boundary одной response/recovery cycle, не session-wide cap.

**Важно про OpenAI long-thinking:** source-level аудит текущего foreground Responses path завершён, но реальный provider-level long-thinking/very-long response пока не наблюдался. `readTimeout(0)`, `callTimeout(0)` и отсутствие implicit 128K `max_output_tokens` доказывают отсутствие найденного client-side cutoff, но не доказывают поведение реального провайдера до terminal event. Это остаётся единственным текущим пробелом active plan.

**Не переоткрывай закрытые lifecycle/recovery контракты без нового доказательства регрессии.**

## АРХИТЕКТУРНЫЕ ПРИНЦИПЫ — стабильные правила

- `ResponseEventReducer` — response semantics; `ResumableResponseStreamClient` — transport + sequence cursor; `ActiveResponsePersistence` — persisted active-response boundary. `ResponseCheckpointPolicy`, `BackgroundResponseUiOwnershipPolicy` и `AttachmentUploadCompletionPolicy` остаются pure decision seams.
- Async callbacks должны сначала захватывать immutable ownership data; UI mutation выполняется через main-thread lifecycle gate.
- `chatId + generation` недостаточны там, где callback меняет конкретный assistant item; для этого нужен immutable `chatId + generation + assistantItemId` snapshot. Tool label — отдельный global Activity state.
- Composer/upload mutable lists и flags сейчас преимущественно UI-thread-owned. Не добавлять synchronized/Concurrent коллекции без доказанного cross-thread access.
- Persistence extraction должна начинаться с pure snapshot/codec или одной доказанной transactional operation, а не с generic controller framework.
- Compose — следующий UI-слой только после state/controller extraction; не смешивать Compose migration с upload/persistence refactors.

## Уроки этого захода

1. **Тесты не должны лезть в MainActivity рефлексией:** `by lazy`, `internal` и Kotlin name mangling меняют runtime-имя/видимость методов и полей. Для новых lifecycle tests нужен прямой test-visible API.
2. **Concurrency group всегда включает имя workflow:** иначе Build и Release с одним `[ci]` push отменяют друг друга.
3. **Тест должен дойти до проверяемого пути:** без непустого API key «новое сообщение отменяет цикл» не доходил до `nextGeneration()` (#62 → #67); предусловие создавай реальным вызовом (`beginUpload`), а не выставляй счётчик напрямую (#72).
4. **ContentProvider в androidTest** падал с `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics`; заменён инжектируемым `AttachmentInputStreamSource` (#68).
5. **Gate, пропускающий мутацию в stopped Activity, требует реконсиляции при возврате** (`onStart()` → `refreshAttachChips()`, #75).
6. **Отчёт ≠ факт:** merge при красном CI и неверное описание PR (#70), «зелёный» счётчик из одних Skipped (#73), «нового прогона не было» при красном `[ci]`-прогоне (#68). Сверяй PR и run ID со страницей GitHub.
7. **Закрытый #81 ≠ merged:** PR #81 был закрыт без merge из-за handoff-verifier; комментарий с причиной добавлен ретроспективно 2026-10-05. Не считать `merge_commit_sha` у closed PR доказательством merge — проверять `merged` и `merged_at`.

## АКТИВНЫЙ ПЛАН — только незавершённое

### 1. OpenAI provider-level long-thinking / very-long response validation — **следующий**
Client-side audit is complete, but a real provider run has not been executed in this agent environment because no OpenAI API key / provider execution connector is available. Do not treat synthetic/local SSE tests as equivalent evidence.
Первый шаг: run one real OpenAI Responses request with an explicitly long task, no app-imposed `max_output_tokens`, high/maximum available reasoning effort, and verify that the client keeps the stream alive until the provider terminal event without local cutoff; record actual duration and usage/reasoning telemetry.

## Репозиторий / branches

Локальных feature branches накопилось много; удаление веток не удаляет коммиты и PR. Ограничения коннектора: нет DELETE ref, нет `workflow_dispatch`, нельзя создавать/менять repository variables; можно читать runs/логи и перезапускать существующие runs (перезапуск Skipped-run job не включит). Чего коннектор не умеет — проси у пользователя конкретным шагом.

## Контекстный режим для агента

- Читай этот файл, а не весь README; код — узко (конкретные файлы/символы); CI-логи — только для конкретного failing run/step; старые PR — точечно.
- Не тащи завершённые задачи как активные. Краткий ответ пользователю не означает сокращённого инженерного анализа.

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
- дождаться реального CI результата по правилам «CI и merge-правила» (Successful `(push)` Build и Release на последнем коммите; Skipped не считается);
- после merge push-runs на main Skipped по дизайну; зафиксируй, на какой реальный прогон ты опираешься;
- обновить план и историю.

### Командная шпаргалка

Debug:
`gradle --no-daemon --stacktrace test lintDebug assembleDebug`

Release:
`gradle --no-daemon --stacktrace test lintRelease assembleRelease bundleRelease`

Static verifier:
`python3 tools/verify_v4_17_0.py`



## История последних рабочих заходов — последние 5 PR

- **#82:** merged `8520a8f3e42616de81bbbf01a63564de3648aea3`; docs-only handoff finalization after #80.
- **#83:** merged `b02150a5cc59de02b33742c8a2928184ad8c8caf`; lossless EXIF JPEG wire policy + legacy PNG-wire migration; full Build/Release successful, including instrumentation.
- **#84:** merged `4a17af3d967e1d50a93b9739c188df66fbe9396d`; OpenAI five-hour recovery policy; full Build/Release successful.
- **#85:** merged `c680f7da8bf18d453b17030b1db7ad88ba3b268b`; docs-only handoff update; `Verify handoff` `37309166754` Successful.
- **#86:** merged `93d61b851c88fdaf19be38d4cbd3b132bdf44d69`; docs-only handoff history clarity; `Verify handoff` `37311559775` Successful.

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

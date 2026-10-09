# NEXT_AGENT_HANDOFF

**Last updated:** 2026-10-09 11:24 +03:00
**Следующий рабочий срез:** Сведения о запросе и события — APP-01: BILL-01-MIN + UI-01 + MODEL-01 одним PR на существующих данных; затем APP-02 (ввод/настройки), APP-03 (проверки/доказанные регрессии). Точное восстановление расходов отложено.

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
- **Бюджет Actions ограничен:** подготовь весь согласованный небольшой срез и выполни доступные локальные проверки до `[ci]`. План — одна обязательная пара APK/Release на окончательном коде выбранного пакета; промежуточные коммиты без `[ci]`. Handoff подготовь в том же кодовом PR до полного CI. Повтор — только после разобранного падения или изменения кода/workflow. Не расширяй задачу ради новых проверок; required merge-гейт сохраняется.

**Merge-гейт.**
1. Код или workflow: оба `(push)` — Build и Release — Successful на последнем коммите, меняющем код/workflow (включая instrumentation); после него допустимы docs-коммиты без `[ci]`, но `Verify handoff` на финальном head должен быть Successful. Docs-only PR: достаточно Successful `Verify handoff`.
2. Описание PR точно перечисляет изменённые файлы, а размер diff соответствует задаче: неожиданно большой diff у большого файла (`MainActivity.kt`) — признак его полной перезаписи.
3. Не мержи, если обязательный run красный, Cancelled или не запускался (Skipped), даже при «Able to merge». Force-push после старта проверок — только с сообщением пользователю и перезапуском проверок.
4. Статус PR — по `merged`/`merged_at`; `merge_commit_sha` у closed PR ничего не доказывает.
5. В отчёте: PR, merge SHA, run ID и результат каждого обязательного run (сверены со страницей GitHub); отдельно — что не проверено.

## 30-секундная карта проекта

**Продукт:** локальный multi-chat Android-клиент. Основной путь — OpenAI Responses API; отдельный путь — OpenRouter Chat Completions для Claude (#47). GitHub-инструменты исполняет само приложение; включаются чекбоксом в настройках (#48).

**OpenAI (основной путь):** foreground Responses без app-side таймаутов (`readTimeout(0)`, `callTimeout(0)`); `max_output_tokens` отправляется только при явной настройке; background recovery использует общую константу 5 ч (#84); непрерывность бюджета Activity↔Worker и прерывание зависшего recovery stream ещё не доказаны (RISK-01).

**Надёжность:** основная lifecycle-модель:
`generation → request/response ownership → immutable UI snapshot → persisted active-response state → WorkManager recovery`.

**Persistence:** DB schema v20 (после #121); metadata сообщений введена в v17 #105, `request_events.cost_kind/cost_usd` — v18 #112, независимый `request_accounting` — v19 #115, `openrouter_request_rounds`/`rounds_sealed` — v20 #121. Важные active-поля:
`active_request_id`, `active_response_id`, `active_text`, `active_status`, `active_background_enabled`, `active_last_sequence_number`.
Устаревший request не должен финализировать persisted state.

**Тесты:** JVM/pure contracts + Robolectric SQLite покрывают большую часть lifecycle/persistence; Android ActivityScenario/instrumentation — критичные callback-пути (с #35). Что доказано, а что нет — в разделе контрактов.

**Архитектура:** `MainActivity.kt` — большой монолит (>6 тыс. строк на дату передачи); Pure seams: `ResponseEventReducer` (response semantics), `ActiveResponsePersistence` (persisted active-response boundary), `ResponseCheckpointPolicy`, `BackgroundResponseUiOwnershipPolicy`, `AttachmentUploadCompletionPolicy`. Foreground cadence 750ms не менялась. Tool-state path остаётся на `chatId + generation`: он обновляет глобальный tool label, а не конкретный assistant item. Compose — после state/controller extraction.

**OpenRouter/Claude (#47–#57, #76, #78, #80, #89, #90, #91):** отдельный Chat Completions client с синхронным tool loop; с #89 transport-level SSE streaming для одного round; без фиксированного лимита раундов и без app-side read/call таймаутов (#80), только аварийный потолок 5 ч / 5M токенов на ответ (#78); `max_tokens` необязателен; partial output при ошибке; usage/cache telemetry (#76); #90 добавляет отдельный каталог source/model и сохраняет requested/response model telemetry + session-scoped request events; репозиторий и ветки GitHub добавляются в system prompt (#50); `tool_trace` восстанавливается из SQLite после reopen только с #105 (ранние несохранённые traces не восстановить); `reasoning.effort` нормализуется на границе (#53); запись файла в GitHub принимает обычный UTF-8 `content`, base64 оставлен для совместимости (#52); composer не уходит под клавиатуру (#51). GitHub API вызывает только приложение: allow-list репозитория/веток, write/merge/review/workflow только после подтверждения пользователя, audit log. Prompt caching Anthropic: plain system text + только top-level automatic `cache_control`, TTL 1 час (#103, без прежнего конфликтующего системного marker 5m) (поле `cache_key` в настройках — это OpenAI `prompt_cache_key`, к нему не относится). К OpenAI background recovery OpenRouter не подключён (намеренно). #91 добавляет user-facing session telemetry и Drawer поверх уже сохранённых request_events; UI runtime на реальном устройстве отдельно не подтверждён.

**Вложения (#57, #83):** `content://` сразу копируется в private storage; text/image/pdf/zip готовятся локально; история хранит attachment manifest; OpenAI File API path отдельный и не затронут. Модель может создавать локальные текстовые файлы (UTF-8). Картинки уходят без lossy-сжатия и downscale: EXIF-поворот 3/6/8 — только strict lossless (`ajpegtran:0.1.14`) при идеальной MCU-геометрии, иначе исходные байты; старые manifests с PNG-wire обновляются при чтении. Anthropic принимает до 10 MB base64 на изображение и 32 MB на запрос; через OpenRouter лимиты зависят от provider/model.

## Фактический baseline

- **Фактический main при перегруппировке 2026-10-09, до этого docs PR:** `8a532e2b1e0a8b67007f45cde7bafe1ccabc140d` после docs #123; открытых PR нет. #123 Verify `37903090821` — **Successful** на final docs head `1059654f16715e5436f123209245c8ac38d297ea`; Android-код не менялся. Проверенная база кода — #122 `ba11422a3bd9ea9d2ea8b56cb336825fdaa5f7f5`; последний код #121, merge `2acc39bf98b432742e5e28462d82d1f952de62a3`, head `37e061b9c2179c4a301b223013104361250b01fe`. APK push `37895662110` — **Successful (attempt 2)** с instrumentation, Release push `37895662075` — **Successful**, оба до merge. APK attempt 1: прежний `stopCancelsOpenRouterModelPreflightCall`, Activity осталась RESUMED вместо CREATED; retry не доказывает устранения причины нестабильности. Для #112/#113/#115/#117/#119 тоже сверены Successful final-code push APK/Release до merge, для docs #114/#116/#118/#120/#122 — Successful Verify.
- **#109 exact code head:** `fe98178be0fe1fd6e1b2c4dfa2060da86a8a8d59`; push Build APK `37817541075` и Release `37817541030` — **Successful**, включая Android-тесты меню `+`/dismiss. На merge #109 `73e677ab86d0a80a8c0016dcccd6ee03dcbfa2cb` Build `37821264884` и Release `37821264874` завершились **Successful**. Более поздние повторные main-runs `37822530048`/`37822530081` — **Cancelled**, новой положительной проверки не дают. На #110 final docs head `727a984a5740d82e7fa065e6ab4ad37fccfecaac` Verify handoff `37822753073` — **Successful**; Skipped Build/Release не считать новой проверкой Android.
- Прежнее падение `37818798866` в ActivityScenario `stopCancelsOpenRouterModelPreflightCall` (expected CREATED, stayed RESUMED) относится к старой ветке; успешный #109 не доказывает отсутствие любой lifecycle flaky-проблемы на телефоне. #107 exact head `fd07bb68ec74458f5ec083ecd0f97e0882429fac`: Build `37814838353`, Release `37814838262` — **Successful**, повторно сверены.
- **Сопоставление присланного внешнего отчёта 08-10-2026:** пункты **7a/7b/8d** помечены там открытыми, но закрыты #107 на уровне source/pure/CI: полный comment/review preview, exact head + required checks/recheck, explicit allow-listed ref. Серверная защита main этим не закрыта. #105 доказывает сохранение переданных полей в DB. Worker success/fatal producer coverage дополнено #115/#117: provider/requested_model/usage и итоговый event там уже есть. В Activity recovery assistant ещё не получает requested_model, а foreground partial-error path — часть metadata; проверить producer matrix в BILL-02, не переделывать миграцию #105.
- **STREAM-01 #113, BILL-02 #115/#117, BILL-03 #119 и BILL-04 #121 — подтверждено source/CI:** SQLite v20 `openrouter_request_rounds` + `rounds_sealed` сохраняет отдельную identity/cost каждого поколения и исключает double-count одинаковых повторных SSE-usage frames. Уточняющиеся стоимости пока не покрыты: observeOpenRouterRound сохраняет только первый известный cost, даже если следующая стоимость изменилась. Сумма request-level становится `provider_reported` только если известна цена всех обнаруженных раундов; явный ноль и tiny positive сохранены, один unknown round удерживает весь request USD unknown. Для известных generation IDs применяется bounded read-only GET `/api/v1/generation?id=...` (до 4 за проход), проверяются `data.id`, `total_cost` и chatId/localRequestId/generation ownership; после Stop cost может быть дополнен без UI resurrection. MockWebServer, Robolectric, Android instrumentation и Release CI — Successful. До этого #119 ограничил OpenAI /cancel+GET reconciliation после Stop, USD OpenAI по usage остаётся unknown. Ни один green CI не доказывает платные LIVE charges; физический телефон и настоящие API не проверены.

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
- [pure] **#92:** OpenRouter server tool catalog формирует точные типы `openrouter:web_search`/`openrouter:web_fetch`; mixed server/local tool path и запрет передавать server tool в local executor подтверждены на JVM, не живым провайдером.
- [pure] **#95:** recorder для успешного OpenRouter завершения вызывает запись session request event и при отсутствующем provider usage; metric callback остаётся условным по наличию usage. Production wiring в MainActivity проверено по source; JVM callback regressions не заменяют реальный UI/provider run. Текущая DB схема сохраняет отсутствующие счётчики как нули, а не как отдельное состояние unknown.
- [pure] **#96:** OpenRouter SSE round требует `[DONE]` перед successful result и local tool execution; JVM transport tests отвергают EOF без маркера даже после `finish_reason=stop`/tool_calls, сохраняют partial text и принимают терминальный фрейм без финальной пустой строки.
- [pure] **#98:** `GenerationInstructions` сохраняет legacy developer-only текст для OpenAI, при заполненном system prompt отдаёт JSON instruction items с отдельными `system` и `developer` ролями; для OpenRouter проверено сохранение app GitHub/attachment/tool контекста и порядок частей. Production wiring в MainActivity есть на source-level, но реальная работа Settings/провайдера не доказана.
- [robolectric] **#101:** `ChatDatabaseAttachmentOwnershipTest` подтверждает DB `wire_path` enumeration по всем/одному чату после reopen и `attachments`-only upsert; JVM `LocalAttachmentReferencesTest` проверяет старый referenced wire, другой сохранённый composer, malformed prefs и aged orphan. Production cleanup wiring проверено на source, но реальный 7-дневный device cleanup не гоняли.
- [pure] **#103:** `OpenRouterCachePolicyTest` и JVM `OpenRouterChatClientTest` проверяют 1h top-level marker без nested 5m system marker, в том числе при растущей истории двух tool rounds. Реальный OpenRouter error показан на телефоне **до** фикса, что не доказывает успешный повтор **после**.
- [robolectric] **#105:** `ChatDatabaseMessageMetadataTest` покрывает DB v16→v17 migration, write/reopen, metadata-only update и импорт LegacyPrefs; pure `ChatMessageFingerprintTest` отслеживает изменения всех optional metadata. Production wiring/source подтверждено, но длительный Claude/GitHub диалог на устройстве после фикса не гоняли. CI Build `37810616575` / Release `37810616596` Successful на exact code head `ca06cec760407c0e8aa84895dce044f7e290efe4`; static verifier + 3 intentional negative mutations PASS (матрица проверяет UI gate renderVectorStoreList, не metadata/usage producer coverage).

**Не подтверждено запуском — общая картина пробелов.** Когда пробел закрыт тестом или наблюдением, перенеси его в список выше с тегом.

Только source-level: #20 — тест проверяет state-контракт `markUserStop()`, но не вызывает `stopCurrentResponse()`; #37 — нет теста wiring adapter; #40 — runtime wiring reasoning callback не гоняется; #42 — нет runtime-теста image/ZIP completion wiring через policy; #78 — потолок 5 ч / 5M токенов; #80 — снятие 10-минутных таймаутов и implicit 128K.

Не подтверждено вообще (нет ни теста, ни наблюдения):
1. Очень длинный ответ на реальном провайдере не наблюдался. OpenAI: source-аудит без app-side timeout, реального прогона не было. OpenRouter: #89 теперь покрывает transport-level SSE на JVM, но реальный long-running provider/mobile run всё ещё не наблюдался; Anthropic рекомендует streaming для long-running запросов, особенно при больших `max_tokens`, поскольку некоторые сети могут разрывать idle-соединения; «10 минут» — ориентир/ограничение SDK для non-streaming, а не универсальный лимит модели.
2. Prompt caching (#55/#56): реальный cache hit не наблюдался; минимальный размер кэшируемого префикса для Opus 5.5 не выяснен; эффект 1-часового TTL на стоимость не проверялся. OpenRouter usage/cache telemetry доказана на уровне client (#76); показ в UI не подтверждён.
3. IME/insets (#51): результат на реальном устройстве с Android 15 не подтверждён.
4. Вложения Claude (#57, #83): чтение моделью картинки/PDF/текста и multi-select через `clipData` не подтверждены на реальной модели; JPEG с неидеальной геометрией или mirrored/transpose-ориентацией уходит как есть — трактовка EXIF-orientation провайдерами не проверена; память/время lossless-поворота на больших фото не измерялись. Для `ajpegtran:0.1.14` опубликованные license metadata и IJG attribution requirements проверены; `NOTICE.md` добавлен в #92, но отдельного runtime/публикационного аудита видимости notice внутри APK не проводилось.
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

**Три этапа вместо списка отдельных PR.** Старые BILL/UI/MODEL/RISK коды — метки проверки и трассировки, а не требование делать отдельный PR на каждый. Владелец ограничивает бюджет Actions и разрешил небольшую правку стоимости после отмены. Реализацию Android-кода выполняет следующий агент; проверяющий ведёт планы. Полный внешний план `OpenAIApiChat-implementation-plan-2026-10-08.md` и отдельный ответ исходному аудитору остаются вне GitHub. Ответ аудитору содержит только его находки. README не трогать. Уже merged #101/#103/#105/#107/#109 и #112/#113/#115/#117/#119/#121 сохранять.

### 1. Сведения о запросе и события — **следующий**

**APP-01 = BILL-01-MIN + UI-01 + MODEL-01. Цель — один согласованный UI PR на текущей v20 схеме.**

Первый шаг: проверить актуальный main/open PR и actual Stop/display path, затем подготовить все три связанные правки до одного полного CI:

- **Стоимость текущего Stop:** сейчас stopCurrentResponse пишет только «Остановлено — запрос отменён»; OpenRouter stopped catch пропускает recordRequestEvent, история читает прежние request_events. Показывать остановленный текущий local request и достоверный полный cost именно этого запроса либо **«Расход неизвестен»**. Старую цену, известную часть и estimate не выдавать за полный итог. При неоднозначности достаточно unknown.
- **Формат стоимости:** общий небольшой formatter, explicit known zero отличается от отсутствия данных; положительная сумма меньше $0.0001 — «< $0.0001», а не округлённый $0.0000. При необходимости stopped-event/snapshot на существующей таблице с captured metadata, без дубликатов или UI resurrection от late callback.
- **События:** сохранить цветную нажимаемую строку, убрать большую рамку/card background, добавить явное «Закрыть» раскрытой истории.
- **Модель ответа:** bubble использует response/requested model из metadata конкретного сообщения; legacy/unknown не подменять текущим global picker или «Astra». Worker producer дополнен #115; при неполных старых metadata использовать честный fallback без нового DB refactor.

Не добавлять схемы/миграции, WorkManager/очередь сверки, новые API-вызовы, полноценный ledger/UI join или переработку всех producer paths. Не возвращать отдельные кнопки вложений вместо `+` #109. Если получение точной суммы требует большой переделки, завершить BILL-01-MIN через unknown. Остальные простые правки APP-01 идут в тот же PR; при обнаружении большого независимого блока отложить его, а не дробить заранее всё на мини-PR.

Проверить прежний completed event с ценой → новый request → Stop, missing/known zero/tiny positive, late callback; открытие/«Закрыть» истории; label после смены global model и reopen/legacy fallback. Runtime использовать по доступным seams; source assertion не выдавать за Android. Условия готовности APP-01: эти UI outcomes доказаны, оба exact final-code push APK/Release Successful, handoff обновлён в этом же PR до запуска CI, один итоговый отчёт. Точная сумма всех реальных отмен пока не гарантируется.

### 2. Поле ввода, шапка и настройки

**APP-02 = UI-02 + MODEL-02 + SET-01. Один следующий PR в пределах существующего UI/prefs.**

Первый шаг: инвентаризировать текущие controls/prefs и проверить capability/serialization выбранных API/model по актуальной документации. Затем перенести reasoning effort к composer, показывать `standard/pro` только при подтверждённой поддержке, уменьшить шапку с корректными Insets/IME, убрать повторные composer model/GitHub badges и разнести длинные настройки по понятным категориям. `temperature` и reasoning effort — разные параметры. OpenRouter сейчас нормализует xhigh/max→high и auto→medium: отсутствие сведений о поддержке не считать поддержкой, не добавлять silent downgrade.

Сохранить все prefs/опции/секреты/guards. Подготовить связанные UI/settings изменения и локальные capability/serialization проверки до одной пары APK/Release. Без Compose/controller rewrite, новых модельных features или смены схемы. Если часть требует отдельной архитектуры, отложить её; делить PR только при конкретной независимой причине, а не по количеству подпунктов. Device smoke: маленький экран, Android bars, keyboard, доступность прежних настроек.

### 3. Проверка приложения и подтверждённые регрессии

**APP-03 = LIVE-01 + UI-03 + RISK-01/02/03 + GH-01/02 + DOC-01. Сначала проверки; новый кодовый PR не обязателен.**

Первый шаг: использовать готовый Successful APK #121 `37895662110` (attempt 2) либо APP-01/02; до телефона/API выполнены только source/CI проверки. Не собирать новый APK ради каждого smoke-теста. Телефон: `+`/Camera/Photo/Files, camera Cancel и RESULT_OK после chat switch/recreate, IME, Drawer свайпы в обе стороны. DrawerLayout уже поддерживает gestures. В разрешённом объёме API: OpenAI/OpenRouter completion/Stop, затем нужные long/web/GitHub read/confirmation/cache scenarios; новых оплачиваемых запросов без согласования не выполнять. Сохранять обезличенные IDs, время/таймзону, outcome и источник usage/cost; задержку сайта считать pending, а не нулём.

Диагностические source findings требуют воспроизведения: RISK-01 — непрерывный 5h deadline Activity↔Worker/restart и quiet recovery stream; UI-03 — сохранность фото исходного draft; RISK-02 — immutable ownership при blocked OpenRouter attachment preflight/chat switch; RISK-03 — замер heap больших файлов/base64. OpenRouter per-Call timeout по remaining 5h уже есть. Исправлять только доказанные нарушения; связанные регрессии одного участка собирать в один PR с одной финальной парой CI. Потерю данных/краш поднимать по фактическому доказательству, не начинать общий rewrite.

GH-01 — негативный тест docs-only/exact-head merge gate: приложение требует Verify для любого markdown, workflow запускается на handoff/tools; не ослаблять #107. GH-02 — main protected=false, серверная защита зависит от владельца/тарифа; кодовый PR не требуется. DOC-01 — handoff включать в соответствующий PR до полного CI; отдельный docs PR нужен лишь для самостоятельного изменения плана/документации. По завершении выбранного пакета уведомить владельца для повторного аудита. Физический телефон/реальные API не объявлять проверенными по CI.

**Отложенный финансовый backlog — не четвёртый обязательный этап:** BILL-04-H1/H2, BILL-02-H1 и полные BILL-01/BILL-02 subcontracts. #119 уже разбирает cancel body и делает максимум 2 GET captured background response; #121 уже сохраняет ID обычного раннего SSE и bounded generation lookup. Не повторять выполненное. Результаты ревью: header X-Generation-Id/первый error-frame ID пропущены; observeOpenRouterRound замораживает первый известный cost (синтетический SQL trace 0→0.07 оставляет round/ledger 0); financial accounting/event пишутся после chat/active CAS отдельными операциями, foreground completed может предшествовать Stop claim; lookup Activity не имеет durable retry/startup drain; настоящая v19 ALTER-ветка не покрыта fixture, который оставляет rounds_sealed. Это source/SQLite findings, а не новые выполненные Android regressions или доказанная причина реальных списаний. Header/final accounting frame [описаны OpenRouter](https://openrouter.ai/docs/api_reference/streaming). Сначала production reproduction, если к backlog позже вернутся; durable financial system сейчас не реализовывать. $0.20 — неточная оценка OpenAI, списания после Stop владелец подтвердил для обоих провайдеров.

## Репозиторий / branches

Локальных feature branches накопилось много; удаление веток не удаляет коммиты и PR.
PR #92 был stacked поверх старого snapshot #91; после merge #91 ветка была перепривязана к новому main и лишняя история #91 удалена из diff. #92 затем merged в `main` как `dc687591a642a48b87572197c673fad2a2951858`. Коннектор не умеет DELETE ref, `workflow_dispatch` и repository variables; можно читать runs/логи и перезапускать существующие runs.

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



Это не полный реестр: пять последних объединённых PR #117–#121. Исторические доказательства в GitHub PR/CI. Ни реальная стоимость по LIVE API, ни физический Android-телефон не проверены.

## История последних рабочих заходов — последние 5 PR


- **#119:** BILL-03 merged `355b337be9e5321a2fc0206efb64071972d915dc`; final head `394779aa498a46ec74ca1e8ff9bbd587e0dbf35a`; push Build Android APK `37892879735` **Successful** включая instrumentation; push Release `37892879656` **Successful**; OpenAI background /cancel response parse, bounded retrieve (2 GET max), exact owned ledger reconciliation after Stop, cost unknown. API/phone not tested.
- **#120:** docs-only handoff после BILL-03: merge `5f2796a3507fdc7cf7e942cfeb958761d5f3ba00`; Verify handoff `37894088101` — **Successful**; Android code unchanged.
- **#121:** BILL-04 first round-level ledger: merged `2acc39bf98b432742e5e28462d82d1f952de62a3`, final code head `37e061b9c2179c4a301b223013104361250b01fe`; Build Android APK push `37895662110` — **Successful** включая instrumentation; Build Android Release push `37895662075` — **Successful**. SQLite v20 per-round generation ID/cost, bounded GET lookup, idempotent usage. No paid API/device checks.
- **#122:** docs-only handoff BILL-04: merged `ba11422a3bd9ea9d2ea8b56cb336825fdaa5f7f5`; final head `a27f0a41bf2f9e21d4db4b918b7b9a3510df91c0`; Verify handoff `37899366370` — **Successful**.
- **#123:** docs-only ограничение стоимости после Stop до BILL-01-MIN и фиксация бюджета Actions; merged `8a532e2b1e0a8b67007f45cde7bafe1ccabc140d`; final head `1059654f16715e5436f123209245c8ac38d297ea`; Verify `37903090821` — **Successful**. История — snapshot перед текущим документационным PR.

## Product radar — идеи с реальной ценностью


Это не backlog и не обещание реализации. Идея попадает сюда только если её можно проверить маленьким шагом и она заметно улучшает ежедневный сценарий.

**Повторная ревизия на базе #110:** хранение метаданных, GitHub tool safeguards и `+` уже реализованы; не повторять их как TODO. Потребность следующего слоя — достоверная стоимость при cancel и компактный UI, модельные capability-aware настройки и живой provider/device proof. Регрессионные тесты доказывают конкретные pure/SQLite/ActivityScenario contracts, но не фактические расходы и провайдерский биллинг. Не затевать полный controller/Compose rewrite. Владелец просил README не трогать.

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

### 8. Настройки генерации, зависящие от источника и модели
**Системный промпт реализован в #98 (v1)**: глобальное поле, отделённое от существующего Developer instructions; OpenAI получает типизированные system/developer instructions, OpenRouter — отдельную пользовательскую часть в system text вместе с app контекстом. Не подтверждено живым API/UI. **«Градус» = temperature (sampling/randomness), а не reasoning effort**: это два независимых рычага; `top_p`, `top_k`, reasoning effort/summary, verbosity, tool-choice и structured output тоже зависят от API source + model, универсального набора нет. Сейчас `temperature`/`top_p` не экспонируются; OpenRouter молча нормализует `xhigh/max` в `high` и `auto` в `medium`, что требует явной политики вместо silent downgrade. OpenRouter model endpoint отдаёт `supported_parameters`, но реальная совместимость также зависит от upstream provider. Первый шаг: описать pure `ModelCapabilities`/`GenerationSettings` contract для обоих request builders, затем regression matrix для unsupported/omitted parameters, invalid ranges и неявной смены effort. Дальнейшие ползунки UI — только после этого; per-chat profiles — отдельная задача.

### 9. Мобильный интерфейс и расходы — уточнённое ТЗ пользователя
**Не скрывать строку событий**: вернуть компактную цветную нажимаемую строку **без рамки/card background**, раскрытую историю закрывать отдельной кнопкой «Закрыть». Уменьшить высоту шапки, но сначала учитывать status/navigation bars через Insets; fullscreen без Android status bar не включать автоматически — только после device UX-проверки. Drawer должен открываться свайпом от левого края и закрываться обратным жестом: AndroidX `DrawerLayout` уже умеет это по умолчанию; **сначала проверить на телефоне**, нет ли фактического конфликта gesture navigation. В composer после #109 кнопки `📎`/`🖼` уже заменены на единое `+` (не возвращать обратно); убрать повторяющийся badge модели и надпись GitHub (настройки оставить); effort у поля ввода по мотивам UI Claude, `standard/pro` показывать лишь если поддерживает source+model. В ответных пузырях хранить и отображать model на момент ответа, не текущую глобальную; основа уже есть с SQLite v17 #105, Worker producer дополнен #115; остаточные Activity recovery/partial paths проверять в BILL-02. **Settings IA:** вместо длинного общего диалога сделать компактные логические разделы подключения, мышления, инструментов, файлов и Advanced; перенести частые действия к composer без дублирования, предусмотреть доступность всех старых опций. **Billing:** владелец подтвердил списания OpenAI и OpenRouter после Stop; **$0.20 — только неточная оценка для OpenAI**, а не итог каждого запроса. В #115 есть независимый request-level ledger для provider-reported/unknown, но без live per-request reconciliation; не показывать незнание как $0. Дальнейшее точное восстановление расходов отложено по уточнению владельца. **Первый шаг:** APP-01 — BILL-01-MIN (текущий Stop со стоимостью либо unknown), компактные события UI-01 и label ответа MODEL-01 в одном PR; затем APP-02 и APP-03. Новая финансовая архитектура отложена. Исторические мотивы — `docs/audits/2026-10-08-live-and-independent-triage.md` раздел 5; ближайший объём определяет активный план выше.

**Точное уточнение от 19:29, реализовано в #109 на уровне кода/Android emulator:** обе отдельные кнопки `📎` и `🖼` заменены **одной кнопкой `+`** рядом с полем ввода. `+` раскрывает аккуратную нижнюю панель (*bottom sheet*) по предоставленному примеру Claude: **Камера**, **Фото**, **Файлы**. Нельзя слепо копировать функции Claude «Добавить в проект / Интернет / Коннекторы», которых нет в текущем приложении; существующие `chooseImage()` и `chooseAny()` переиспользовать с проверкой сохранения вложений, разрешений и IME/insets. Камера уже реализована через FileProvider/ActivityResult без постоянного camera permission; проверка реального внешнего camera app и обработка cancellation/rotation/device-memory ещё открыты. Клик вне панели и Back закрывают диалог; **жест вниз не подтверждён и может потребовать отдельного поведения**. Отдельных 📎/🖼 на поверхности больше нет. Первый шаг: device smoke на камере/свайпе/IME, затем точечная доработка, если проблема действительно воспроизведена.

**Черновик идей — без фильтра, до следующего обзора**
Сюда можно писать сырое: без доказанной ценности и без первого шага — это специально разрешено, чтобы не отсеивать идею раньше, чем она успела оформиться. Максимум 5 строк. На каждом архитектурном/продуктовом обзоре: по каждой старой записи — либо дожать её до формата Product radar (с «Первый шаг:») и перенести выше, либо вычеркнуть одной строкой почему; добавить не больше одной новой записи, обязательно вдохновлённой взглядом наружу — как эту же проблему решает другой multi-chat/Responses API клиент, либо какая уже существующая pure-архитектура (`ResponseEventReducer`, `ActiveResponsePersistence`, `AttachmentUploadCompletionPolicy`) недоиспользуется и что даст её переиспользование иначе.

(пусто — заполнится на следующем обзоре)

### Что сознательно не тянуть сейчас
- MCP и Computer Use — пока нет доказанной продуктовой потребности и это резко увеличивает execution surface.
- Полный Compose rewrite — инфраструктурная стадия, не пользовательская функция.
- «AI сам организует чаты/саммари/теги» — выглядит эффектно, но пока не доказано, что это важнее поиска, обратимости и прозрачности контекста.
- Синхронизация между устройствами — отдельная security/backend задача, не естественное продолжение текущей local-first архитектуры.

## Документация

**Актуальный аудит/UX-план:** `docs/audits/2026-10-08-live-and-independent-triage.md` (source-confirmed vs runtime-observed, порядок регрессионных PR и отложенная UI-компоновка). **Пересылаемый отчёт независимому аудитору ведётся отдельно, не в репозитории**, без пользовательских UX-находок. README сейчас устарел (DB v15, baseline #42), не использовать как источник фактов. **По просьбе пользователя не трогать README в обозримом будущем**, включая чистку и переоформление: P0/P1 важнее. Отсутствие .gitignore/wrapper и архив notes — P3, не важнее риска потери данных.

**README** = устаревший человекочитаемый snapshot; по просьбе владельца не редактировать в этом цикле.

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

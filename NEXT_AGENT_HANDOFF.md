# NEXT_AGENT_HANDOFF

**Last updated:** 2026-10-09 15:04 +03:00
**Следующий рабочий срез:** Отправка, сохранность ответа и управление чатом — APP-04: один кодовый PR по результатам телефона и повторного source/CI-аудита. Сначала фото/файлы Claude, безопасная отправка и сохранение результатов, затем точный UI. Цена отмены и полная финансовая система отложены.
**Внесённый код и приёмка:** #125 APP-01, #126 APP-02 и camera fix #128 merged после Successful APK/Release/Verify. Владелец подтвердил камеру, но выявил нарушения доставки фото и UI; эти требования не считать закрытыми по CI. База аудита #129 `93cb4e500bebfd7c197d12f8a6943d8cdf723f9e`, SQLite v20; подробности ниже.

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
- **Бюджет Actions ограничен:** подготовь весь согласованный пакет и выполни доступные локальные проверки до `[ci]`. План — одна обязательная пара APK/Release на окончательном коде выбранного пакета; промежуточные коммиты без `[ci]`. Handoff подготовь в том же кодовом PR до полного CI. Повтор — только после разобранного падения или изменения кода/workflow. Не расширяй задачу ради новых проверок; required merge-гейт сохраняется.

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

- **Проверено 2026-10-09 перед этим docs PR:** main `93cb4e500bebfd7c197d12f8a6943d8cdf723f9e` после #129; открытых PR нет, AGENTS.md в дереве нет. Последний Android-код — #128; SQLite v20. Сравнение #124 → main: 21 commit, 13 изменённых файлов; README не менялся. Перед реализацией заново сверить HEAD/open PR.
- **Повторно проверены реальные runs/jobs и merged_at:** #125 final code `6aa069be89500a86bf4907cc90205e640af628e9`, APK `37908225095`, Release `37908225079`, Verify `37908229012`; #126 `a2d8de607606c7ec4213d73705b39393d0b83cf1`, APK `37911721448`, Release `37911721472`, Verify `37911728484`; #128 `1676c5c2964478a3bb0379b1c01dbe2b04db1c75`, APK `37917519700`, Release `37917519729`, Verify `37917524650`. Все перечисленные проверки **Successful**; push APK instrumentation действительно выполнялся, Release также выполнял проверки; финальные Android runs завершены до merge. Docs #127/#129 Verify `37913526595`/`37919168979` Successful. Skipped Android docs-runs не использованы как доказательство.
- **Исправления действительно внесены:** #125 Stop event текущего запроса/unknown, компактная история/Close и formatter; #126 quick effort, header picker, перестановка Settings и capability guard; #128 camera source-chat ownership/Cancel/recreation. Это не равно полному выполнению пользовательского ТЗ: unconditional Astra, подпись вместо явной model row, неполная панель мышления и невыполненная gesture-приёмка открыты заново в APP-04.
- **Новые доказательства владельца:** камера работает; Claude/OpenRouter отклоняет фото с `image.source.base64.mediatype` enum error; в error/новом bubble остаётся Astra, после текста ответа бывает «продолжает ответ…»/Stop; Drawer открывается только кнопкой; Settings дублируется. На скриншотах OpenAI отвечает по фото и возвращает token usage, USD unknown; один Claude completion имеет reported cost. Это не проверка всех файлов, всех моделей, размера списаний или причины MIME/terminal race.
- **Новый обзор:** прочитаны все diff #125/#126/#128 и соседние send/attachments/lifecycle/DB/model/settings paths, существующие тесты и финальные jobs. Конкретные SOURCE findings и критерии исполнения — APP-04. Новые Android regressions, замеры heap и платные API в этом аудите не запускались. Local handoff verifier проверяется отдельно; source diagnosis не называть runtime reproduction.
- #101 attachments/wire_path/drafts/fingerprint, #103 Claude caching, #105 persisted metadata, #107 GitHub guards и #109 меню + не переделывать без новой регрессии. Внешние аудиторские 7a/7b/8d закрыты #107 на source/pure/CI; серверная branch protection этим не закрыта. #112–121 добавили unknown/reported, ledger, OpenAI terminal semantics, CAS recovery и round accounting. История этих proof не отменяется новыми узкими потерями OpenRouter/UI.

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
6. Полный набор async-границ `MainActivity` не покрыт runtime. Повторный source-аудит 2026-10-09 выявил узкие пути потери OpenRouter result, stale recovery callback и отсутствующую onStart reconciliation (APP-04); новые production regressions ещё должен выполнить исполнитель. Аудит не называть доказательством отсутствия остальных ошибок.

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

**Один ближайший кодовый пакет APP-04.** Уточнение владельца 2026-10-09 заменяет прежнюю очередь APP-01 → APP-02 → APP-03. Код этих пакетов объединён, но пользовательская приёмка выявила недоделки и ошибки; не объявлять весь интерфейс или передачу вложений исправными по CI. Проверяющий меняет план, Android реализует следующий агент. Внешний пересылаемый план — `OpenAIApiChat-implementation-plan-2026-10-08.md`, редакция 7; отдельный ответ исходному аудитору остаётся вне GitHub и содержит только его находки.

### 1. Отправка, сохранность ответа и управление чатом — **следующий**

**APP-04: один PR для связанных повседневных сценариев, одна финальная пара APK/Release.** Внутренние коды ниже — критерии приёмки, не отдельные PR. Сначала вложения и потеря данных, затем интерфейс. Новая схема SQLite, финансовый ledger/reconciliation, Compose и общий controller rewrite не требуются для выбранного объёма. Сохранить исправления #101/#103/#105/#107/#109/#112–121/#128; README не менять.

**Первый шаг:** сверить main/open PR, затем написать воспроизведения на существующих production seams: настоящий image → сохранённый manifest → сериализованный HTTP body; завершение ответа при остановленной Activity; возврат после завершения Worker. В том же пакете подготовить точную компоновку ниже. Сначала добиться падения целевых тестов на baseline; source finding не выдавать за уже выполненный Android regression.

| Код / приоритет внутри PR | Проверенное основание | Требуемый результат |
| --- | --- | --- |
| APP-04-FILE / P1 | Владелец: Claude отвергает camera JPEG 6.3 MB с `image.source.base64.mediatype` enum error. SOURCE: `finalizeFile` различает kind по magic, но MIME берёт из имени/resolver; `toDataUrl` доверяет manifest. Точная причина именно этого live запроса пока неизвестна | Проверять реальный формат байтов, MIME и data URI на конечном request boundary; Camera/Photo/Files и история после reopen доходят в корректном формате. Ошибка не должна уничтожать исходник/черновик |
| APP-04-SEND / P1 | SOURCE: `buildOpenRouterMessagesSnapshot()` в `sendMessage` выполняет чтение истории/Base64 на UI thread, вне обработчика ошибки, после очистки composer/setBusy. Executor затем вновь читает mutable chat для modalities | Сначала неизменяемый снимок владельца, сообщений, вложений и параметров; тяжёлая подготовка вне UI thread, проверка тех же вложений, понятная ошибка и сохранённый draft. Stop/chat switch не отправляют другой чат и не оставляют ложный busy |
| APP-04-LIFE / P1 | SOURCE: OpenRouter success очищает active до UI callback; сохранение assistant зависит от `isRequestCurrent`, включающего видимость. Error/partial path меняет только bubble. `onStart` не перечитывает уже завершённый Worker-ом результат; recovery final callback проверяет только chatId | Сохранить принадлежащий запросу результат независимо от видимости, затем обновить UI по полному ownership. После возврата перечитать итог без потери нового draft. Старый callback не завершает новый запрос; Stop/delete/duplicate не воскрешают ответ |
| APP-04-CONTEXT / P1 | SOURCE: OpenRouter composer attachments не входят в OpenAI body после смены провайдера. OpenAI full-history fallback передаёт только text прежних сообщений | Ни одно видимое вложение не пропадает молча: подготовить совместимое представление либо явно заблокировать отправку, сохранив draft. При переходе провайдера проверить историю и previous_response_id; чужие file IDs не переносить |
| APP-04-MODEL / P2 | SOURCE + скриншоты: formatter всегда добавляет Astra; pending/error bubbles обходят formatter; тест закрепляет Astra для Claude | В каждом состоянии показывать модель данного запроса/ответа. Пока фактическая неизвестна — честно указать запрошенную; при отсутствии metadata — нейтральный fallback. Astra допустима только как действительное имя выбранной/вернувшейся Astra |
| APP-04-UI / P2 | Владелец: model row убрана, нужные controls остались в Settings, Drawer не открывается свайпом, Settings дублируется | Выполнить точное ТЗ ниже; функциональные UI-тесты должны нажимать controls и проверять результат, а не только наличие label |

**Вложения и безопасная отправка — детали приёмки.**

- Фото: настоящий JPEG/PNG/GIF/WebP; URI без расширения, MIME alias/octet-stream, несовпадение расширения и байтов, повреждённый файл; обычный и legacy manifest после SQLite/composer round-trip, отсутствующий wire/text/original path, JSON null в optional metadata. Размер/расширение в карточке и локальный preview не доказывают содержимое HTTP body. Текущий unit fixture с произвольными шестью байтами и вручную заданным `image/jpeg` эту проверку не заменяет.
- Перед HTTP проверить готовый `image_url.url`: допустимый MIME, декодируемые байты правильного формата, число блоков. Сохранить original hash/EXIF и действующий lossless policy; слова модели «EXIF не видно» не доказывают повреждение файла. Не вводить скрытое lossy-сжатие или публичную загрузку фото.
- Ошибку владельца воспроизвести по outbound request и upstream error. Диагностика: provider/model/request identity, тип блока, MIME/data URI prefix без payload, число байтов/размеры; никаких ключей, base64, текста файлов или полного prompt. При корректном запросе установить upstream route/отказ и дать понятную ошибку; не объявлять MIME root cause по одному сообщению. Лимиты зависят от API/upstream; не объявлять любое фото >5 MB запрещённым для всех Claude.
- PDF/text/ZIP: проверить содержимое и порядок блоков, multi-select, повторную отправку из истории; unsupported/пропущенные ZIP entries объяснять. PDF native capability и возможный parser route различать; не добавлять незаметно платный OCR fallback ради обхода preflight. OpenAI uploads, поиск/скачивание/шаринг оригиналов и camera ownership #128 сохраняются.
- Невалидная/пустая обязательная JSON Schema должна останавливать отправку до очистки draft и HTTP. Сейчас catch лишь меняет status и запрос всё равно отправляется без требуемого формата. Это часть APP-04-SEND, а не отдельный PR.
- Перенос I/O в executor не должен переносить туда чтение mutable `chat`/composer. Захватить chatId/localRequestId/generation, provider/model/settings и attachment manifest один раз; повторный Send/Stop/switch проверять на этой identity. Секреты не сохранять в DB/логах. Не добавлять универсальную framework-архитектуру.

**Сохранность результата — обязательные воспроизведения.**

1. OpenRouter terminal получен → UI callback задержан → Activity hidden/другой чат: ответ и metadata доступны после reopen ровно один раз. Аналогично partial/error: сохранить полезный результат и исход, не возвращать намеренно отменённое сообщение.
2. OpenAI Worker завершился, пока Activity stopped → тот же экран возвращается без recreation: DB-ответ появляется, placeholder/Stop/«продолжает ответ…» исчезают, новый composer draft сохранён. На скриншоте владельца есть такое противоречие UI, но причинная привязка к Worker пока SOURCE-гипотеза.
3. Recovery final callback старого request задержан → новый request в том же чате: старый callback не делает `nextGeneration`/`setBusy(false)` для нового, не заменяет его bubble. Проверять chatId + requestId/generation + assistant item и persisted claim.
4. Повтор terminal, Stop, удаление чата, switch/recreate: не дублировать и не воскрешать результат. Переиспользовать существующие CAS/ownership операции; общий финансовый transaction rewrite сюда не включать.

**Точное ТЗ интерфейса, заменяет прежнюю неточную формулировку APP-02.**

- Видимая отдельная компактная строка выбора **провайдера и модели над перепиской, под шапкой**, с понятным нажатием/раскрытием. Маленькая подпись внутри заголовка её не заменяет. Убрать provider/model selector из общих Settings; API keys/подключения остаются там. Выбор следующей модели не меняет metadata уже отправленного запроса.
- Существующая кнопка effort у поля ввода открывает **полную панель «Мышление и ответ»**: усилие, поддерживаемый `standard/pro`, summary и связанные существующие параметры ответа (verbosity, max output, формат/schema). Убрать дубли из общих Settings, сохранить значения/ключи prefs. Показывать только применимые controls; явно отражать нормализацию effort OpenRouter. `temperature` — отдельный sampling parameter, не effort; новые параметры не добавлять.
- `standard/pro` не считать автоматически ошибкой: текущая официальная OpenAI документация описывает reasoning.mode для GPT-5.6/GPT-6. Проверить конкретную модель/API и фактическую сериализацию каждого отображаемого control; внутренний whitelist-тест сам по себе не доказывает поддержку провайдера.
- В Drawer убрать нижнюю кнопку Settings; оставить основную шестерёнку. Открытие свайпом вправо и закрытие влево проверить на gesture navigation и обычной навигации, с открытой IME. Наличие DrawerLayout не считается пройденной приёмкой. Реализовать удобную доступную зону без перехвата вертикальной прокрутки, выделения текста, code-scroll, composer и системного Back; причину сбоя жеста сначала воспроизвести.
- Сохранить компактную цветную строку событий без большой карточки и кнопку «Закрыть». Раскрытая история ограничена по высоте/прокручивается либо открывается в компактной панели; не вытесняет всю переписку и composer. Убрать противоречивое дублирование текущего статуса и завершённого события, не скрывать последнюю полезную статистику.
- Маленький экран, большой системный шрифт, portrait/rotation, status/navigation bars, IME, +/Camera/Photo/Files, меню моделей и панели: controls доступны и не перекрываются. Не возвращать отдельные старые кнопки вложений.

**Готовность APP-04:** описанные production regressions проходят; все шесть внутренних групп сведены в один PR с краткой матрицей «проверено / не проверено»; APK instrumentation и Release Successful на окончательном кодовом SHA, Verify на final head. Перед полным CI завершить код, handoff и доступные локальные проверки. Промежуточные коммиты без `[ci]`, не пушить во время полного прогона. Одна пара CI — цель бюджета, а не разрешение пропустить повтор после нужной правки. После merge один итоговый отчёт и готовый APK владельцу; телефон/API не объявлять пройденными по эмулятору. Новые платные запросы не выполнять без разрешения; если для MIME осталась только live-проверка, явно оставить её открытой и не обещать, что Claude уже проверен.

### 2. Проверки после основного пакета и отложенные риски

**Первый шаг:** использовать тот же итоговый APK APP-04 для короткой проверки владельцем: фото Claude → ответ/ошибка, модель в bubble, переключение провайдера с вложением, background/return, model row/панель мышления/Drawer/IME. Не требовать отдельную сборку на каждый smoke-сценарий. При новом подтверждённом краше или потере данных пересмотреть приоритет, а не ждать очереди формально.

- RISK-01: Activity и Worker начинают recovery clock заново; общий 5h deadline и тихий зависший stream требуют fake-clock/blocked-stream regression до переделки. OpenRouter уже имеет per-Call remaining budget; не добавлять произвольные короткие лимиты ответа.
- RISK-02: snapshot/preflight ownership, затронутый реальной отправкой, входит в APP-04-SEND; остальные async boundaries проверять по факту, не делать blanket thread-safety rewrite.
- RISK-03: полное чтение/Base64 и подготовка на UI thread найдены в source. Вынос тяжёлой работы/ошибки — APP-04-SEND; реальный peak heap/OOM больших историй ещё не измерен. Потоковый transport rewrite — только после замера, без silent compression.
- LIVE-01: реальные Claude/OpenRouter cache, длинный OpenAI, web tools, GitHub read/подтверждения и расходы не доказаны зелёным CI. Камера работает по сообщению владельца; остальные условия отдельно фиксировать. Ключи и оплачиваемые генерации не нужны для локальных regressions APP-04.
- GH-01/GH-02: Verify workflow покрывает handoff/tools, а app docs-only gate требует Verify шире; негативный сценарий произвольного Markdown не закрыт. `main.protected=false` — серверной защиты нет, настройки зависят от владельца/тарифа. Не ослаблять #107 и не смешивать изменение CI-политики с APP-04 ради удобства.
- Product radar (поиск, ветвление, профили, context inspector) остаётся будущими идеями; не увеличивать текущий PR их реализацией. DOC-01 — handoff включать в соответствующий кодовый PR до полной сборки; не делать отдельный docs PR на каждый маленький подпункт.

**Финансы отложены.** Для Stop неизвестный расход допустим; подтверждённый ноль, unknown и estimate не смешивать. Владелец подтвердил списания обоих провайдеров; $0.20 — приблизительное число для OpenAI. OpenAI usage содержит токены, но не подтверждённую USD-цену; текущий unknown не доказывает потери usage. Старый `estimateCost` использует фиксированные Astra-тарифы — не возвращать его для всех GPT как фактическую цену. Будущая модельная оценка должна явно называться «Оценка», с известными тарифом/моделью/coverage.

Сохранить достигнутое #112–121; не продолжать финансовые BILL-04-H1/H2 и BILL-02-H1 автоматически: X-Generation-Id/первый error ID, уточняющиеся round-cost snapshots, atomic accounting/event, durable lookup/retry и настоящий v19 ALTER fixture остаются открытыми source findings. #119 уже ограниченно сверяет OpenAI cancel+GET, #121 сохраняет ранний SSE generation ID и rounds. Их отсутствие полной live-сверки не блокирует APP-04. Финансовую архитектуру и новые миграции сейчас не делать.

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

Последние пять merged PR на проверенной базе #129, до текущего изменения плана. Полные Android проверки ниже относятся к final code head, а не к последующему документационному merge.

- **#125:** APP-01, merge `3fe630b5cdc3d4fc6a0f6c9f8dd6fa49eec5ef6e`, code `6aa069be89500a86bf4907cc90205e640af628e9`; APK `37908225095`, Release `37908225079`, Verify `37908229012` — Successful. Stop/события/formatter внесены; новые пользовательские замечания — APP-04.
- **#126:** APP-02, merge `3e9cbf2c03a12d6f8a005b995e5c35d0d4c77f55`, code `a2d8de607606c7ec4213d73705b39393d0b83cf1`; APK `37911721448`, Release `37911721472`, Verify `37911728484` — Successful. UI/capabilities внесены, полный UX не принят владельцем.
- **#127:** docs после APP-02, merge `fc1bc66ee2f9ac973a83f61e625a1d4ea0830a3c`, head `b1207a95d5f48565287d0c1408ef5372d4863ef0`; Verify `37913526595` — Successful.
- **#128:** camera ownership, merge `e2dcd5c363d437af43175b6537b5936ab0acc71d`, code `1676c5c2964478a3bb0379b1c01dbe2b04db1c75`; APK `37917519700`, Release `37917519729`, Verify `37917524650` — Successful. Первый instrumentation fail `37916027661` из-за неверного ожидания Cancel-теста исправлен. Владелец позже подтвердил работу камеры; доставка фото Claude не подтверждена.
- **#129:** docs после #128, merge `93cb4e500bebfd7c197d12f8a6943d8cdf723f9e`, head `37f98a4b578c9c9e084807415ebe87eda6899460`; Verify `37919168979` — Successful. Android-код не менялся.

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

### 9. Повседневный интерфейс и доставка вложений — уточнение владельца 2026-10-09

Каноническое ТЗ — APP-04 выше: отдельная видимая model/provider row над перепиской, выбор убран из общих Settings; полная панель мышления/ответа из существующей effort-кнопки; одна основная шестерёнка, Drawer без Settings и с открытием/закрытием свайпом; модель каждого bubble без навязанного имени Astra; ограниченная по высоте цветная история с Close. Меню + сохраняет Camera/Photo/Files. Доставка файлов и сохранность ответа выше цены отмены; unknown допустим, тарифную оценку не выдавать за списание. **Первый шаг:** выполнить APP-04 одним кодовым PR с проверкой фактического request body, lifecycle outcomes и действий controls. Это исправление существующих сценариев, не очередь из отдельных PR.

**Черновик идей — без фильтра, до следующего обзора**
Сюда можно писать сырое: без доказанной ценности и без первого шага — это специально разрешено, чтобы не отсеивать идею раньше, чем она успела оформиться. Максимум 5 строк. На каждом архитектурном/продуктовом обзоре: по каждой старой записи — либо дожать её до формата Product radar (с «Первый шаг:») и перенести выше, либо вычеркнуть одной строкой почему; добавить не больше одной новой записи, обязательно вдохновлённой взглядом наружу — как эту же проблему решает другой multi-chat/Responses API клиент, либо какая уже существующая pure-архитектура (`ResponseEventReducer`, `ActiveResponsePersistence`, `AttachmentUploadCompletionPolicy`) недоиспользуется и что даст её переиспользование иначе.

(пусто — заполнится на следующем обзоре)

### Что сознательно не тянуть сейчас
- MCP и Computer Use — пока нет доказанной продуктовой потребности и это резко увеличивает execution surface.
- Полный Compose rewrite — инфраструктурная стадия, не пользовательская функция.
- «AI сам организует чаты/саммари/теги» — выглядит эффектно, но пока не доказано, что это важнее поиска, обратимости и прозрачности контекста.
- Синхронизация между устройствами — отдельная security/backend задача, не естественное продолжение текущей local-first архитектуры.

## Документация

**Актуальный план:** APP-04 в этом handoff и внешний файл плана редакции 7. `docs/audits/2026-10-08-live-and-independent-triage.md` — исторический аудит, его прежняя очередь/компоновка не переопределяет уточнения владельца 2026-10-09. **Пересылаемый отчёт независимому аудитору ведётся отдельно, не в репозитории**, без пользовательских UX-находок. README сейчас устарел (DB v15, baseline #42), не использовать как источник фактов. **По просьбе пользователя не трогать README в обозримом будущем**, включая чистку и переоформление: P0/P1 важнее. Отсутствие .gitignore/wrapper и архив notes — P3, не важнее риска потери данных.

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


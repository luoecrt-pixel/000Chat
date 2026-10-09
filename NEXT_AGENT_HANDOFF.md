# NEXT_AGENT_HANDOFF

**Last updated:** 2026-10-09 18:12 +03:00
**Следующий рабочий срез:** APP-04 — F01–F20 в одной рабочей ветке/PR; новый пользовательский patch F18 (без понижения xhigh/max, resolved auto в ⚡, без Claude verbosity) включён в ту же ветку, но ожидает unit/Android/full CI. APP-05 F21–F26 после APP-04, финансовая отмена отложена.
**Внесённый код и приёмка:** #125 APP-01, #126 APP-02 и camera fix #128 merged после Successful APK/Release/Verify. Владелец подтвердил камеру, но выявил нарушения доставки фото и UI; эти требования не считать закрытыми по CI. База повторной проверки #131 `18c9cd23d7dbda0f09f5543bac93d66e83dc806e` (Android-код тот же #128), SQLite v20; подробности ниже.

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

**OpenAI (основной путь):** foreground Responses без app-side таймаутов (`readTimeout(0)`, `callTimeout(0)`); `max_output_tokens` отправляется только при явной настройке; background recovery использует общую константу 5 ч (#84); непрерывность бюджета Activity↔Worker и прерывание зависшего recovery stream ещё не доказаны (APP-05/F21).

**Надёжность:** основная lifecycle-модель:
`generation → request/response ownership → immutable UI snapshot → persisted active-response state → WorkManager recovery`.

**Persistence:** DB schema v20 (после #121); metadata сообщений введена в v17 #105, `request_events.cost_kind/cost_usd` — v18 #112, независимый `request_accounting` — v19 #115, `openrouter_request_rounds`/`rounds_sealed` — v20 #121. Важные active-поля:
`active_request_id`, `active_response_id`, `active_text`, `active_status`, `active_background_enabled`, `active_last_sequence_number`.
Устаревший request не должен финализировать persisted state.

**Тесты:** JVM/pure contracts + Robolectric SQLite покрывают большую часть lifecycle/persistence; Android ActivityScenario/instrumentation — критичные callback-пути (с #35). Что доказано, а что нет — в разделе контрактов.

**Архитектура:** `MainActivity.kt` — большой монолит (>6 тыс. строк на дату передачи); Pure seams: `ResponseEventReducer` (response semantics), `ActiveResponsePersistence` (persisted active-response boundary), `ResponseCheckpointPolicy`, `BackgroundResponseUiOwnershipPolicy`, `AttachmentUploadCompletionPolicy`. Foreground cadence 750ms не менялась. Tool-state path остаётся на `chatId + generation`: он обновляет глобальный tool label, а не конкретный assistant item. Compose — после state/controller extraction.

**OpenRouter/Claude (#47–#57, #76, #78, #80, #89, #90, #91):** отдельный Chat Completions client с синхронным tool loop; с #89 transport-level SSE streaming для одного round; без фиксированного лимита раундов и без app-side read/call таймаутов (#80), только аварийный потолок 5 ч / 5M токенов на ответ (#78); `max_tokens` необязателен; partial output при ошибке; usage/cache telemetry (#76); #90 добавляет отдельный каталог source/model и сохраняет requested/response model telemetry + session-scoped request events; репозиторий и ветки GitHub добавляются в system prompt (#50); `tool_trace` восстанавливается из SQLite после reopen только с #105 (ранние несохранённые traces не восстановить); `reasoning.effort` исторически понижался на границе (#53); в APP-04 F18 это заменяется точной transport policy, pending CI; запись файла в GitHub принимает обычный UTF-8 `content`, base64 оставлен для совместимости (#52); composer не уходит под клавиатуру (#51). GitHub API вызывает только приложение: allow-list репозитория/веток, write/merge/review/workflow только после подтверждения пользователя, audit log. Prompt caching Anthropic: plain system text + только top-level automatic `cache_control`, TTL 1 час (#103, без прежнего конфликтующего системного marker 5m) (поле `cache_key` в настройках — это OpenAI `prompt_cache_key`, к нему не относится). К OpenAI background recovery OpenRouter не подключён (намеренно). #91 добавляет user-facing session telemetry и Drawer поверх уже сохранённых request_events; UI runtime на реальном устройстве отдельно не подтверждён.

**Вложения (#57, #83):** `content://` сразу копируется в private storage; text/image/pdf/zip готовятся локально; история хранит attachment manifest; OpenAI File API path отдельный и не затронут. Модель может создавать локальные текстовые файлы (UTF-8). Картинки уходят без lossy-сжатия и downscale: EXIF-поворот 3/6/8 — только strict lossless (`ajpegtran:0.1.14`) при идеальной MCU-геометрии, иначе исходные байты; старые manifests с PNG-wire обновляются при чтении. Anthropic принимает до 10 MB base64 на изображение и 32 MB на запрос; через OpenRouter лимиты зависят от provider/model.

**APP-04 work in progress, 2026-10-09:** branch `agent/app-04-integrity-ui-20261009` от `main c9069333050d85bc1db6105c1c7ad4d9dd68c02d`. Все блоки A/B/C составляют **один кодовый PR**, но успешный unit/Android/Release/Verify пока не установлен. С пользовательского ZIP `APP-04-F18-reasoning-fix.zip` вручную перенесены изменения MainActivity (новая панель вместо legacy settings), ModelRequestPolicy, OpenRouterChatClient, новый OpenRouterReasoningPolicy и JUnit тесты. В патче отмечено: Kotlin/JUnit/Android компиляция автором патча не запускалась; нельзя выдавать git apply --check за пройденный CI. Сохраняй APP-04 изменения других блоков, не заменяй файлы целиком. Платные API вызовы не выполнять.

## Фактический baseline

- **Проверено 2026-10-09 перед этим docs PR:** main `18c9cd23d7dbda0f09f5543bac93d66e83dc806e` после docs #131; Android-код совпадает с #129/#128; открытых PR нет, AGENTS.md в дереве нет. Последний Android-код — #128; SQLite v20. Предыдущий Android diff #124 → #129: 21 commit, 13 изменённых файлов; #130 менял только handoff. README не менялся. Перед реализацией заново сверить HEAD/open PR.
- **Повторно проверены реальные runs/jobs и merged_at:** #125 final code `6aa069be89500a86bf4907cc90205e640af628e9`, APK `37908225095`, Release `37908225079`, Verify `37908229012`; #126 `a2d8de607606c7ec4213d73705b39393d0b83cf1`, APK `37911721448`, Release `37911721472`, Verify `37911728484`; #128 `1676c5c2964478a3bb0379b1c01dbe2b04db1c75`, APK `37917519700`, Release `37917519729`, Verify `37917524650`. Все перечисленные проверки **Successful**; push APK instrumentation действительно выполнялся, Release также выполнял проверки; финальные Android runs завершены до merge. Docs #127/#129 Verify `37913526595`/`37919168979` Successful. Skipped Android docs-runs не использованы как доказательство.
- **Исправления действительно внесены:** #125 Stop event текущего запроса/unknown, компактная история/Close и formatter; #126 quick effort, header picker, перестановка Settings и capability guard; #128 camera source-chat ownership/Cancel/recreation. Это не равно полному выполнению пользовательского ТЗ: unconditional Astra, подпись вместо явной model row, неполная панель мышления и невыполненная gesture-приёмка открыты заново в APP-04.
- **Новые доказательства владельца:** камера работает; Claude/OpenRouter отклоняет фото с `image.source.base64.mediatype` enum error; в error/новом bubble остаётся Astra, после текста ответа бывает «продолжает ответ…»/Stop; Drawer открывается только кнопкой; Settings дублируется. На скриншотах OpenAI отвечает по фото и возвращает token usage, USD unknown; один Claude completion имеет reported cost. Это не проверка всех файлов, всех моделей, размера списаний или причины MIME/terminal race.
- **Широкий обзор редакции 8:** snapshot всех 53 production Kotlin и 40 test files сверён с Git blob SHA текущего main; разобраны send/history/edit/delete/Repeat, Activity↔Worker, composer/upload/ZIP/download/save/picker, response semantics/render/copy, model/settings, GitHub/Vector Store, security/manifest и CI. Реестр F01–F26 отделяет новые SOURCE finding от телефонного наблюдения и ещё не измеренного риска. Закрытые helper/SQLite/CI тесты не доказывают отсутствующие end-to-end сценарии. Новые Android regressions, heap замеры и платные API в этом проверяющем аудите не запускались.
- **Документационный #130:** merge `181dca5cdaddcf0190bb446c5e161c0dfe1a501e`, head `877770bc8501e5d1a3dfa6f67a33c611ab49303c`, Verify `37931552848` Successful, verifier step выполнен; открытых PR на начале широкого аудита нет.
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

**Редакция плана 9:** два крупных пакета вместо очереди мелких PR. F01–F26 ниже — реестр доказательств и приёмки, не 26 задач/веток. APP-04 включает F01–F20 одним кодовым PR, APP-05 — оставшиеся эксплуатационные риски одним следующим пакетом. Source finding требует targeted production regression; ничего не объявлять исправленным только по этому документу. Цена отмены больше не ведёт очередь.

### 1. Отправка, сохранность и повседневный интерфейс — **следующий**

**APP-04: один кодовый PR, три связанных блока, одна плановая финальная пара APK/Release. Одна пара — бюджетная цель, не гарантия отсутствия CI regression.** Самые опасные условия — потеря истории/вложений и неверный файл — исправлять первыми; UI подготовить в том же PR. Не менять DB schema ради этого пакета, не начинать Compose/controller/финансовую архитектуру.

**Первый шаг:** заново сверить main/open PR и написать падающие production-path regressions для MIME/body, edit/delete при active request, checkpoint text/cursor и cache с одинаковым filename. Затем использовать те же ownership/snapshot/operation seams для остальных строк. Mock transport/clock и барьеры позволяют проверить race без платного API и долгого CI; чтение source не является уже запущенным тестом.

| Блок внутри одного APP-04 | Что агент завершает вместе |
| --- | --- |
| A. Сообщение и файлы (F01–F08) | Реальный wire/body, совместимый контекст, одна upload/download operation, целостный composer merge, безопасные ZIP/cancel, правильный файл по identity и устойчивое save/picker result. |
| B. Запрос и история (F09–F16) | Persisted terminal/partial независимо от видимости, reconciliation при возврате, согласованный checkpoint, edit/delete/Repeat/Stop и request-bound GitHub approval, отказ модели и постепенный текст Claude. |
| C. Управление и чтение (F17–F20) | Верная модель bubble, явная model row, полная thinking/response panel, нужные жесты/компоновка, точные Copy/TXT/URL/code. |

**Уточнение выполнимости после повторной проверки (редакция 9).** Один APP-04 остаётся выполнимым на v20, но прежнее «использовать CAS» недостаточно. `upsertCurrentChatInDb` удаляет rows, которых нет в переданном списке; безусловный `persistCurrentChat` со старым UI snapshot способен стереть уже сохранённый Worker/foreground terminal. Нужно проверить также writers до/после claim, а не только сам claim.

- **Порядок внутри того же PR:** failing production fixtures → immutable snapshot/operation ownership → безопасные DB writers и reconciliation → точный UI → полный CI. Это последовательность работы, не новые PR.
- **Черновик во время preflight:** captured request и новый ввод — разные данные. Если во время async подготовки пользователь набрал D2 после отправленного D1, успех/ошибка/Stop/return не удаляют D2 и его новые вложения. Потреблять только подтверждённый отправляемый snapshot; обычная выборка модели влияет на следующий request. JSONObject/JSONArray snapshot должен быть глубокой копией, не shared mutable list.
- **История и CAS:** финализировать owned assistant/partial узким append/update в транзакции из актуального DB state. Нельзя просто передать старый полный список в existing finalizer. Обычные navigation/asset/foreground writers также не должны удалять более свежие rows/metadata; удаление suffix допустимо только для явного Edit/Delete по проверенному owner. Использовать existing request/response IDs и content_hash/targeted SQL, не новую общую framework/схему. Нужен barrier: final commit → UI callback задержан → navigation вызывает persist; terminal и свежий download path/feedback сохраняются.
- **Stop против terminal:** первый committed persisted claim определяет локальный исход. Если Stop выиграл — late terminal не resurrect; если terminal выиграл — поздний Stop не удаляет его ответ/историю. Не вызывать UI `stopCurrentResponse()` как универсальную инвалидацию Edit: он восстанавливает prompt и удаляет bubbles. Переиспользовать нижние cancellation/claim операции, а UI менять по исходу claim. Полученные provider usage/cost не отбрасывать из-за hidden/stale UI; финансовую архитектуру не переписывать.
- **Lifetime:** onStop/onDestroy не равны Stop пользователя. Captured terminal/partial persistence не должна зависеть от видимости и закрытого Activity-owned DB/executor; согласовать короткую DB operation/resource lifetime. OpenRouter не получает новый remote resume механизм: при отмене соединения сохраняется уже полученный partial/исход. Нельзя обещать сохранение никогда не полученных байтов или всего tail при смерти процесса до checkpoint. Reconcile на возврате сохраняет новый draft/операцию upload и не запускает загрузку повторно.
- **Однозначный Repeat:** повторить последний сохранённый user prompt/attachments новым request ID под текущими выбранными provider/model/settings; сохранить прежний ответ, добавить согласованную новую пару user/assistant. Нажатие на старый assistant не означает повтор именно его: label остаётся «Повторить последний запрос». Не использовать stale lastUserText пустого/нового чата, не клонировать прежние IDs/usage/status/feedback; не очищать несвязанный draft ввода. Во время busy/preflight/ZIP повтор не создаёт вторую генерацию. Проверить actual body + DB историю, не только аргумент text.
- **Payload/поток/renderer без нового объёма:** image preflight проверяет magic/размеры/MIME без полной второй Bitmap/Base64 копии; полный decode body — тест, не лишнее runtime выделение памяти. OR delta callback передаёт накопленный текст по всем tool rounds, final/done не дописывают prefix второй раз. Refusal извлекается как контент, provider terminal status/usage сохраняется; не делать его ложной network failure. Copy/export берут актуальный source выбранного bubble, включая streaming snapshot, не stale bound item или rendered text. Renderer acceptance — конкретные code/URL/fences/escape fixtures, не обещание полного CommonMark.

**Что реально проверил аудитор:** на текущем source повторно проверены названные callers/DDL/ownership; в SQLite с настоящими v20 CREATE TABLE выполнены шесть trace-вариантов узкого append/claim (duplicate, оба порядка Stop/terminal, rollback, сохранение свежих metadata, deleted chat) и контрпример: unconditional старый snapshot удаляет уже committed ответ. Это SQL design/contract proof, не запущенный Kotlin/Android production test и не реализованный fix. Для merge исполнитель обязан провести описанные production barriers и полный CI. Новая схема для выбранной операции не нужна. Ошибка Claude на телефоне по-прежнему требует фактического outbound/upstream доказательства.

**Точное UI-ТЗ, заменяет неверно понятый APP-02:** отдельная видимая компактная строка provider/model под шапкой над перепиской, а не маленькая подпись в title; provider/model selector убрать из общих Settings. Существующая ⚡ кнопка у ввода открывает полную панель «Мышление и ответ»: effort, поддерживаемый standard/pro, summary, уже имеющиеся verbosity/max output/format/schema; эти дубли убрать из общих Settings, keys/prefs/секреты сохранить. Unsupported controls скрывать, OR effort normalization показывать честно; temperature не reasoning effort. Убрать только Settings footer Drawer, основную gear оставить. Drawer открывается вправо и закрывается влево без конфликтов с system Back/IME/scroll. Цветная строка событий и Close сохраняются, раскрытая история ограничена по высоте. Малый экран, большой шрифт и +/Camera/Photo/Files должны оставаться доступны. Astra в label — только реальная Astra; Claude/другие модели показываются из metadata конкретного запроса/ответа.

**Реестр приёмки APP-04.** Строки ниже объединены по причине; не делать PR/дублирующие тесты для каждого номера. Детальные fixtures, порядок работы и coverage gaps есть во внешнем плане редакции 9. PHONE — наблюдение владельца, SOURCE — проверенный путь кода, не обещание runtime reproduction.

| Найденный путь | Доказательство и место | Проверяемый результат |
| --- | --- | --- |
| F01 · P1: Фото Claude и целостность manifest | PHONE + SOURCE. JPEG MIME определяется по имени/manifest после magic kind; телефонный upstream media-type error, точная причина неизвестна. | Проверка реального image_url MIME/bytes после persistence, safe error; EXIF/hash/lossless сохраняются. |
| F02 · P1: Подготовка и валидация отправки | SOURCE. UI-thread Base64 после очистки draft; mutable modalities; invalid schema не останавливает HTTP. | Deep immutable owner/settings/messages snapshot; I/O вне UI; validation до потребления отправленного snapshot, новый D2 draft не теряется. |
| F03 · P1: Контекст после смены провайдера | SOURCE. Вложения исчезают из body при смене провайдера/full-history fallback. | Совместимый контекст либо явный запрет с сохранённым draft; без чужих file IDs. |
| F04 · P1: Повторные загрузки и отмена | SOURCE, новое. Initial upload не входит в resume in-flight registry; return может повторить upload. | Одна owned operation/call для initial+resume; точный Cancel и безопасный late completion. |
| F05 · P1: Параллельные вложения стирают черновик | SOURCE, новое. Concurrent SOURCE_CHAT prefs read-modify-apply теряет вложение; late write может вернуть удалённый key. | Сериализованный merge без потери нового текста; проверка существующего owner. |
| F06 · P1: ZIP может заблокировать Send или отправиться после Cancel | SOURCE, новое. Stale/hidden ZIP failure оставляет preparing=true; Cancel не всегда прекращает auto-Send; entry errors скрыты. | Освобождение state во всех исходах, отмена авто-Send и видимый partial ZIP manifest. |
| F07 · P1: Файл ответа может оказаться файлом другого ответа | SOURCE, новое. Legacy filename-only cache связывает новый file_id с чужими байтами; bind повторяет download. | Cache только по подтверждённой identity; один download; same-name fixture. |
| F08 · P2: Сохранение и внешние picker теряют операцию | SOURCE, новое. Export/save source не переживает recreate; другие picker/voice берут текущий owner; Documents I/O на main. | Сохраняемый descriptor операции/владельца и async I/O; recreate/switch/slow stream. |
| F09 · P1: Завершённый/частичный ответ теряется вне экрана | SOURCE. Foreground terminal сохранён только внутри visible/current UI gate; OR partial/error остаётся в bubble. | Owned narrow append/patch из актуальной DB + claim; все обычные writers защищены от stale snapshot; hidden/reopen/оба порядка Stop tests. |
| F10 · P1: Возврат после Worker и старый final callback | SOURCE + PHONE симптом. onStart не подхватывает завершённый Worker; final callback проверяет только chatId. | Reconcile DB/history/active/UI с новым draft; старый callback не завершает новый request. |
| F11 · P1: Checkpoint текста и SSE cursor расходятся | SOURCE, новое. Foreground checkpoint пишет text без соответствующего lastSequenceNumber. | Один text/cursor checkpoint; starting_after/partial до terminal без дубля. Cadence не менять. |
| F12 · P1: Edit/delete оставляют старый запрос или старое состояние | SOURCE, новое. Edit не снимает persisted active/Worker; Delete оставляет busy/Stop/input/tool state; NewChat не очищает старый input. | Инвалидация до обрезания истории; пустой работоспособный новый чат; late callbacks не воскрешают. |
| F13 · P2: Повтор запроса у OpenRouter не повторяет prompt | SOURCE, новое. OR Repeat не добавляет повторяемый prompt в body; menu допускает Send при busy. | Последний сохранённый prompt/attachments новым ID под текущими настройками; старая пара и новый draft сохранены; busy отклонён. |
| F14 · P1: Подтверждение GitHub не привязано к request | SOURCE, новое. Pending GitHub approval проверяет видимость, но не chat/request/generation. | Approval принадлежит request; switch/edit/delete/Stop отклоняют stale решение до write; #107 guards сохранить. |
| F15 · P2: Отказ модели показывается как пустой ответ | SOURCE, новое. Refusal delta/done/terminal content не извлекается. | Показывать/сохранять причину отказа вместо пустого ответа, включая recovery/reopen. |
| F16 · P2: Claude SSE есть в сети, но текст не появляется постепенно | SOURCE, новое. OR SSE собирает text внутри client без incremental UI callback. | Постепенный owned text/partial; blocked first-delta тест до terminal. |
| F17 · P2: Модель каждого bubble | PHONE + SOURCE. Astra навязана formatter и transient/error paths. | Actual/requested model конкретного ответа во всех состояниях и после reopen. |
| F18 · P2: Выбор модели и полная панель мышления | PHONE + SOURCE. Header subtitle заменил отдельную model row; thinking controls/Drawer Settings дублируются; патч пользователя 2026-10-09 выявил недопустимый silent xhigh/max → high и Claude verbosity conflict. | APP-04 branch: явная model row, полная панель ⚡, prefs сохранены. F18 patch интегрирован в APP-04: manual xhigh/max без понижения, auto разрешается Activity по конкретному запросу и отображается как ⚡ auto → resolved, неразрешённый auto отбрасывается до HTTP, verbosity для Anthropic не отправляется и UI-контрол отключён без удаления pref; OpenAI mode стандарт/pro не менялся. Новые pure + local MockWebServer fixtures для трёх Claude/двух tool rounds. **До CI/эмулятора это реализация, не доказанная приёмка.** |
| F19 · P2: Drawer и полезная площадь экрана | PHONE, root cause gesture не установлен. Телефон: Drawer не открывается свайпом; expanded events вытесняют чат. | Opening/closing gestures, bounded history/Close, IME/insets/малый экран; phone отдельно. |
| F20 · P2: Отображение меняет копируемый текст и ссылки | SOURCE, новое. Renderer меняет snake_case/URL; Copy/TXT берут rendered text; stats содержат literal backslash-n. | Буквальный code/URL, корректные fences/escapes; Copy/TXT исходного сообщения. |

**Закрытие APP-04:** все условия выполнимости редакции 9 проверены на production paths; production regressions для трёх блоков проходят; сохраняются guards #101/#103/#105/#107/#109/#128 и неизвестный расход вместо ложного нуля. Предварительная диагностика фото должна отделить неверный app payload от отказа корректного payload upstream; точная причина телефонного MIME error ещё открыта. Не вводить скрытое сжатие, публичную загрузку, платный OCR/retry. Полный APK instrumentation и Release Successful на окончательном кодовом SHA, Verify на final head. Код + handoff готовить до [ci]; промежуточные коммиты без метки. После merge один итоговый отчёт с APK и компактной матрицей пройдено/открыто. Если live root cause не установлен, прямо указать это, не объявлять Claude проверенным по mock. Не останавливать реализацию после каждого внутреннего подпункта.

### 2. Долгие операции, ресурсы и инструменты — следующим общим пакетом

**APP-05:** один следующий пакет F21–F26, после APP-04 и короткой проверки того же APK владельцем. Не запускать Android CI для одного лишь обзора этих рисков и не смешивать их с ближайшим пакетом. Если измерения покажут независимое опасное изменение схемы/transport, отделить только его по конкретной причине, не дробить строки автоматически.

**Первый шаг:** fake-clock/blocked-stream regression для сквозного deadline, synthetic input для copy/cleanup и delayed Vector Store dialog fixture. Сначала воспроизвести конкретный предел/потерю, затем определить минимальные budgets и ownership; не ждать пять часов в CI и не вводить короткий timeout самого ответа.

| Найденный путь | Доказательство и место | Проверяемый результат |
| --- | --- | --- |
| F21 · P1 на уровне аварийного контракта: Пятчасовой recovery budget не сквозной | SOURCE. Recovery clock начинается заново на каждом Activity/Worker; quiet stream не прерывается по deadline. | Один durable remaining budget; fake clock/blocked stream, без короткого timeout ответа. |
| F22 · P2, OOM/ANR ещё не измерены: Копирование больших файлов и heap | SOURCE. Raw copy без byte/cancel budget, whole-file text/Base64; OOM ещё не измерен. | Synthetic stream/heap measurement → bounded I/O/понятный budget, без silent compression/truncation. |
| F23 · P2: Незавершённые файлы остаются на диске | SOURCE, новое. ZIP/copy/decode/download exceptions оставляют temporary/partial files. | Cleanup только своих operation paths; failure/retry не растят мусор и не удаляют READY. |
| F24 · P2: Vector Store: старый callback, зависание и скрытая ошибка | SOURCE, новое. VS callbacks используют глобальный dialog view; unlimited metadata call, limit=100, delete error скрыт. | Dialog/store owner, finite control-call budget, Cancel/errors, pagination или явный неполный список. |
| F25 · P2: GitHub read может сообщить успех с пустым файлом | SOURCE + API contract, новое. GitHub getFile возвращает ok=true/empty при encoding=none или decode failure. | Bounded raw/blob по разрешённой identity либо явный unsupported, отличать настоящий пустой файл. |
| F26 · P2, CI/config отдельно: Docs gate и серверная защита | SOURCE + GitHub snapshot. Любой Markdown требует Verify в client gate, workflow покрывает только handoff/tools; main не protected. | Согласовать trigger/gate в APP-05; server protection отдельно у владельца/тарифа. |

**Живые проверки:** использовать итоговый APK APP-04 для одного короткого списка: Claude фото/файл; реальная модель pending/error/restored; Repeat; Edit/Delete при запросе; background→return; model row/thinking panel/Drawer/IME; Copy/Save. Камера уже подтверждена владельцем, остальные результаты фиксировать по отдельности. Настоящие API/cache/long responses/web/GitHub и фактические суммы не доказываются CI. Новые платные вызовы без разрешения не выполнять; текущих сообщений/скриншотов достаточно для offline regressions.

**Финансы в парковке, без нового PR сейчас.** Unknown после Stop/OpenAI допустим при отсутствии подтверждённой USD суммы; usage токенов не означает известную цену. Владелец подтвердил списания OpenAI и OpenRouter; $0.20 — приблизительная цифра, не точный результат. Сохранить #112–121. OpenRouter header/first error ID, уточнение round cost, durable lookup/retry, atomic accounting/event, nullable legacy tokens и реальный v19 ALTER fixture остаются в историческом финансовом приложении внешнего плана. BILL-04-H1/H2 и BILL-02-H1 не продолжать автоматически; фиксированные Astra rates не выдавать за фактическое списание всех GPT. При новом подтверждённом crash/потере данных пересмотреть очередь по доказательствам.

**Будущие функции, не срочные дефекты:** search, fork/versions, undo edit/delete, profiles и context inspector сгруппированы в Product radar. Сейчас не делать их инфраструктуру. GH branch protection — действие владельца; F26 client/workflow mismatch можно исправлять в APP-05 без обещания серверной защиты.

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



Полный исторический реестр находится в GitHub PR/CI и финансовом приложении внешнего плана. Фактические суммы LIVE API не установлены; камера проверена владельцем, остальные телефонные результаты перечислены в baseline.

## История последних рабочих заходов — последние 5 PR

Последние пять merged PR на проверенной базе #131, до текущего изменения плана. Полные Android проверки ниже относятся к final code head, а не к последующему документационному merge.

- **#127:** docs после APP-02, merge `fc1bc66ee2f9ac973a83f61e625a1d4ea0830a3c`, head `b1207a95d5f48565287d0c1408ef5372d4863ef0`; Verify `37913526595` — Successful.
- **#128:** camera ownership, merge `e2dcd5c363d437af43175b6537b5936ab0acc71d`, code `1676c5c2964478a3bb0379b1c01dbe2b04db1c75`; APK `37917519700`, Release `37917519729`, Verify `37917524650` — Successful. Первый instrumentation fail `37916027661` из-за неверного ожидания Cancel-теста исправлен. Владелец позже подтвердил работу камеры; доставка фото Claude не подтверждена.
- **#129:** docs после #128, merge `93cb4e500bebfd7c197d12f8a6943d8cdf723f9e`, head `37f98a4b578c9c9e084807415ebe87eda6899460`; Verify `37919168979` — Successful. Android-код не менялся.

- **#130:** docs/editorial plan revision 7, merge `181dca5cdaddcf0190bb446c5e161c0dfe1a501e`, head `877770bc8501e5d1a3dfa6f67a33c611ab49303c`; Verify `37931552848` — Successful, verifier step выполнен. Android-код не менялся; редакция 8 расширяет аудит, заменяет ближайшую очередь.

- **#131:** широкий docs audit revision 8, merge `18c9cd23d7dbda0f09f5543bac93d66e83dc806e`, head `b22e6e434ee06fa7f1e7c6bd4c37ca11ba8df36f`; Verify `37935958150` — Successful, verifier step выполнен. Android-код не менялся; редакция 9 уточняет DB writers/preflight/Repeat и доказательства выполнимости.

## Product radar — идеи с реальной ценностью


Это не backlog и не обещание реализации. Идея попадает сюда только если её можно проверить маленьким шагом и она заметно улучшает ежедневный сценарий.

**Повторная ревизия на базе #130:** хранение метаданных, GitHub tool safeguards и `+` уже реализованы; новые узкие wiring defects не отменяют этих гарантий. Ближайший слой — целостность истории/вложений/ответов, точный UI и понятные outcomes; отменённая стоимость отложена. Регрессионные тесты доказывают конкретные pure/SQLite/ActivityScenario contracts, но не фактические расходы и провайдерский биллинг. Не затевать полный controller/Compose rewrite. Владелец просил README не трогать.

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

**Актуальный план:** два пакета APP-04/APP-05 и реестр F01–F26 в этом handoff и внешнем плане редакции 9. Старые APP-04-FILE/SEND/LIFE/CONTEXT/MODEL/UI заменены блоками A/B/C одного PR, не продолжаются как шесть PR. `docs/audits/2026-10-08-live-and-independent-triage.md` — исторический аудит, его прежняя очередь/компоновка не переопределяет уточнения владельца 2026-10-09. **Пересылаемый отчёт независимому аудитору ведётся отдельно, не в репозитории**, без пользовательских UX-находок. README сейчас устарел (DB v15, baseline #42), не использовать как источник фактов. **По просьбе пользователя не трогать README в обозримом будущем**, включая чистку и переоформление: P0/P1 важнее. Отсутствие .gitignore/wrapper и архив notes — P3, не важнее риска потери данных.

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


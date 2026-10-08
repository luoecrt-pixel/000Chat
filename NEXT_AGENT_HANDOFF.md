# NEXT_AGENT_HANDOFF

**Last updated:** 2026-10-09 01:30 +03:00
**Следующий рабочий срез:** Достоверные расходы и завершение запросов

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

**OpenAI (основной путь):** foreground Responses без app-side таймаутов (`readTimeout(0)`, `callTimeout(0)`); `max_output_tokens` отправляется только при явной настройке; background recovery использует общую константу 5 ч (#84); непрерывность бюджета Activity↔Worker и прерывание зависшего recovery stream ещё не доказаны (RISK-01).

**Надёжность:** основная lifecycle-модель:
`generation → request/response ownership → immutable UI snapshot → persisted active-response state → WorkManager recovery`.

**Persistence:** DB schema v17 (после #105); важные поля:
`active_request_id`, `active_response_id`, `active_text`, `active_status`, `active_background_enabled`, `active_last_sequence_number`.
Устаревший request не должен финализировать persisted state.

**Тесты:** JVM/pure contracts + Robolectric SQLite покрывают большую часть lifecycle/persistence; Android ActivityScenario/instrumentation — критичные callback-пути (с #35). Что доказано, а что нет — в разделе контрактов.

**Архитектура:** `MainActivity.kt` — большой монолит (>6 тыс. строк на дату передачи); Pure seams: `ResponseEventReducer` (response semantics), `ActiveResponsePersistence` (persisted active-response boundary), `ResponseCheckpointPolicy`, `BackgroundResponseUiOwnershipPolicy`, `AttachmentUploadCompletionPolicy`. Foreground cadence 750ms не менялась. Tool-state path остаётся на `chatId + generation`: он обновляет глобальный tool label, а не конкретный assistant item. Compose — после state/controller extraction.

**OpenRouter/Claude (#47–#57, #76, #78, #80, #89, #90, #91):** отдельный Chat Completions client с синхронным tool loop; с #89 transport-level SSE streaming для одного round; без фиксированного лимита раундов и без app-side read/call таймаутов (#80), только аварийный потолок 5 ч / 5M токенов на ответ (#78); `max_tokens` необязателен; partial output при ошибке; usage/cache telemetry (#76); #90 добавляет отдельный каталог source/model и сохраняет requested/response model telemetry + session-scoped request events; репозиторий и ветки GitHub добавляются в system prompt (#50); `tool_trace` восстанавливается из SQLite после reopen только с #105 (ранние несохранённые traces не восстановить); `reasoning.effort` нормализуется на границе (#53); запись файла в GitHub принимает обычный UTF-8 `content`, base64 оставлен для совместимости (#52); composer не уходит под клавиатуру (#51). GitHub API вызывает только приложение: allow-list репозитория/веток, write/merge/review/workflow только после подтверждения пользователя, audit log. Prompt caching Anthropic: plain system text + только top-level automatic `cache_control`, TTL 1 час (#103, без прежнего конфликтующего системного marker 5m) (поле `cache_key` в настройках — это OpenAI `prompt_cache_key`, к нему не относится). К OpenAI background recovery OpenRouter не подключён (намеренно). #91 добавляет user-facing session telemetry и Drawer поверх уже сохранённых request_events; UI runtime на реальном устройстве отдельно не подтверждён.

**Вложения (#57, #83):** `content://` сразу копируется в private storage; text/image/pdf/zip готовятся локально; история хранит attachment manifest; OpenAI File API path отдельный и не затронут. Модель может создавать локальные текстовые файлы (UTF-8). Картинки уходят без lossy-сжатия и downscale: EXIF-поворот 3/6/8 — только strict lossless (`ajpegtran:0.1.14`) при идеальной MCU-геометрии, иначе исходные байты; старые manifests с PNG-wire обновляются при чтении. Anthropic принимает до 10 MB base64 на изображение и 32 MB на запрос; через OpenRouter лимиты зависят от provider/model.

## Фактический baseline

- **Актуальный main после #113:** merge `601fe4b3f0981440454fcb578745010f80be8789` (2026-10-09 01:28:59 +03:00); code head `da21cf236de3e06b8aef50b96248a9bd9b957835`. #113 STREAM-01: APK push `37852576665` — **Successful**, включая Android instrumentation; Release push `37852576727` — **Successful**; Verify handoff `37852582803` — **Successful** на том же head. Предыдущий #112: BILL-01 partial, main merge `0690c7b14752e2e7707cdbbe3dd5c49644a8b1b0`, final PR head `e92d30b6b051e9e4610b152f59c97dd61400cf5e`, Build APK push `37848391691` и Release push `37848391612` — **Successful**. Для нового задания заново сверять main и открытые PR, не полагаться на этот snapshot. Код #101/#103/#105/#107/#109 не реализовывать повторно без воспроизведения регрессии.
- **#109 exact code head:** `fe98178be0fe1fd6e1b2c4dfa2060da86a8a8d59`; push Build APK `37817541075` и Release `37817541030` — **Successful**, включая Android-тесты меню `+`/dismiss. На merge #109 `73e677ab86d0a80a8c0016dcccd6ee03dcbfa2cb` Build `37821264884` и Release `37821264874` завершились **Successful**. Более поздние повторные main-runs `37822530048`/`37822530081` — **Cancelled**, новой положительной проверки не дают. На #110 final docs head `727a984a5740d82e7fa065e6ab4ad37fccfecaac` Verify handoff `37822753073` — **Successful**; Skipped Build/Release не считать новой проверкой Android.
- Прежнее падение `37818798866` в ActivityScenario `stopCancelsOpenRouterModelPreflightCall` (expected CREATED, stayed RESUMED) относится к старой ветке; успешный #109 не доказывает отсутствие любой lifecycle flaky-проблемы на телефоне. #107 exact head `fd07bb68ec74458f5ec083ecd0f97e0882429fac`: Build `37814838353`, Release `37814838262` — **Successful**, повторно сверены.
- **Сопоставление присланного внешнего отчёта 08-10-2026:** пункты **7a/7b/8d** помечены там открытыми, но закрыты #107 на уровне source/pure/CI: полный comment/review preview, exact head + required checks/recheck, explicit allow-listed ref. Серверная защита main этим не закрыта. #105 доказывает сохранение переданных полей в DB, но не их заполнение во всех producer paths: Worker не передаёт `provider/requested_model/usage` и не пишет итоговое request event; это новый отдельный пробел BILL-02, не повод переделывать миграцию #105.
- **STREAM-01 (#113) исправлен на уровне source/CI:** OpenAI foreground больше не трактует EOF или `[DONE]` без terminal как completed; semantic SSE error и настоящие failed/cancelled/incomplete различаются, partial сохраняется, terminal output без delta учитывается; чтение foreground и resumable заканчивается на принятом terminal, не ждёт EOF. Дубликаты последовательности не могут фальсифицировать terminal. Транспортные JVM/MockWebServer tests, source verifier с 8 negative mutation scenarios, Build/Release и API 34 instrumentation — Successful. API/телефон LIVE отдельно **не проверены**. Существующие Worker metadata/accounting gaps относятся к BILL-02; не считать их устранёнными #113. `request_events` FK cascade и Delete guard ранее подтверждены; orphan-events дефект не выявлен.

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

**Основание и границы задания:** исторический `docs/audits/2026-10-08-live-and-independent-triage.md` и присланный ответ аудитору сверены с merged #101/#103/#105/#107/#109 и базой #110. Исторические P0/P1 не возвращать в работу автоматически. Пользователь поручил проверяющему анализ и планы, а реализацию Android-кода — следующему агенту. Подробный пересылаемый `OpenAIApiChat-implementation-plan-2026-10-08.md` ведётся вне GitHub; коды ниже совпадают с ним. Отдельный ответ исходному аудитору тоже вне GitHub, содержит только его находки и обновляется без PR. Старый смешанный `docs/audits/2026-10-08-external-auditor-response.md` удалён #108. README не трогать.

### 1. Достоверные расходы и завершение запросов — **следующий**

**Приоритет P1; актуальная очередь:** ограниченная часть BILL-01 реализована #112, STREAM-01 реализована #113 и проверена CI. **Полный BILL-01 = PARTIAL:** nullable token counters, типизированные coverage/source/estimated cost и полнота известной части остаются. **Следующее отдельное задание BILL-02:** durable accounting ledger, immutable ownership/credential context, foreground/OpenRouter/Activity recovery/Worker общий recorder и metadata, честные totals; далее BILL-03/BILL-04 по cancel/reconciliation; LIVE-01 после green APK. Владелец подтвердил списания **обоих провайдеров после Stop**; ранее названные **$0.20** — неточная оценка **OpenAI**, не подтверждённая сумма и не расход OpenRouter. Никакой ноль без provider evidence.
Первый шаг: **BILL-02** — написать независимые tests на captured localRequestId vs HTTP/response ID, сохранение accounting после Stop и process reopen, late callback/Delete/clear-history, Worker completion/timeout/auth/404/fatal с partial/metadata и идемпотентность итогов. Начать с узкого persisted request ledger и общего recorder, не возвращать старые события к ложному нулю. **Сделано в BILL-01 #112:** безопасная SQLite v18 migration и корректное отличение provider zero от unknown; многократный OpenRouter tool loop без полной цены оставляет итог unknown; исторические оценки OpenAI показываются как unverified. **Сделано в STREAM-01 #113:** shared Responses SSE reader, terminal outcome policy, EOF/[DONE]/semantic errors/duplicate sequences, fallback final text и немедленное завершение reader с сохранением ownership/partial; без новых foreground timeout. **Не сделано:** durable ledger, Worker metadata и итоговые события; post-cancel provider lookup; nullable counters/coverage, настоящий API/device тест. Не смешивать с UI/Settings. **BILL-02:** canonical localRequestId отдельно от HTTP/response/generation IDs, immutable source/settings/credential reference (не ключ); общий idempotent recorder из foreground/OpenRouter/Activity recovery/Worker, totals без дублей и явный unknown count. Worker/Activity finalizers должны заполнять v17 `provider/requested_model/response_model/usage`; проверить DB reopen. Stop инвалидирует UI, но финансовая identity переживает его; late accounting не возрождает bubbles/active state/удалённый чат.
**BILL-03:** разобрать JSON тела OpenAI cancel; /cancel только для captured background=true, затем конечный retrieve по response ID при необходимости. Usage даёт оценку, не доказанные списанные доллары. **BILL-04:** сохранять каждый OpenRouter generation ID с header/stream до error/Stop, usage каждого tool round; сохранять известную часть при ошибке, читать последний accounting frame, ограниченный generation lookup только для недостающих данных. Реальная отмена upstream зависит от провайдера; HTTP 200/закрытие локального Call не доказывает нулевой расход. Не запускать новую генерацию для сверки, не распределять общий баланс, не запрашивать ключи у пользователя.

### 2. Проверка recovery budget и сохранности камеры

**RISK-01 и защитная часть UI-03:** диагностические тесты раньше косметики; код менять при воспроизводимом нарушении. Activity и Worker начинают новое 5h окно, recovery transport с read/call timeout=0 может не вернуться в наружную deadline-проверку; camera callback удаляет полученный файл при несовпадении source/current chat. Это source findings, end-to-end сценарии ещё не доказаны.
Первый шаг: fake-clock multi-handoff Activity→Worker→Activity/restart и recovery stream без новых строк; сохранять общий origin/remaining budget, terminal partial/accounting и ownership; timeout/fatal не должен стереть единственную persisted partial-копию без фиксации исхода, обычный foreground не ограничивать. Проверить camera RESULT_OK + смена/пересоздание чата: фото не должно попасть в чужой чат или исчезнуть из живого исходного черновика. Повторно использовать attachment ownership contracts; не удалять/переделывать меню #109. В OpenRouter per-Call timeout по оставшемуся 5h бюджету уже существует, не объявлять его отсутствующим. Если тест обнаружит потерю данных или обход deadline, исправление поднять в текущую волну и отметить код/доказательство в отчёте.

### 3. Компактный интерфейс и модель конкретного ответа

**UI-01/UI-02/MODEL-01**, после первого исправленного accounting/status среза. Сохранить цветную нажимаемую строку событий без большой рамки/card, добавить явную кнопку «Закрыть» раскрытой истории. Уменьшить шапку с корректными Android Insets/IME; убрать дублирующий composer badge модели/надпись GitHub, сохранив настройки. Bubble берёт модель конкретного ответа из v17 metadata, не нынешний global picker; unknown не подменять «Astra».
Первый шаг: проверить сохранённые поля всех producer paths (включая Worker BILL-02), pure label fallback для response/requested/legacy и Android ActivityScenario открытие/закрытие событий, маленький экран/клавиатура. **UI-03:** на телефоне проверить `+`/Camera/Photo/Files, отмену и сохранность фото, IME и Drawer свайпы в обе стороны. DrawerLayout уже поддерживает gestures; interception менять только после воспроизведения. Fullscreen и Compose rewrite автоматически не включать.

### 4. Модельные параметры и понятные настройки

**MODEL-02 → SET-01:** reasoning effort у поля ввода; `standard/pro` только при подтверждённой поддержке source+model; `temperature` — отдельный параметр. Категории Settings: API/модель, Ответ/мышление, Инструменты, Файлы, Дополнительно. Все существующие prefs/опции/секреты/guards сохранить.
Первый шаг: инвентаризация controls/prefs и pure capability/serialization matrix по актуальной документации и доступным моделям. Отсутствие сведений не считать поддержкой, не делать silent downgrade (OpenRouter сейчас normalizes xhigh/max→high, auto→medium). Проверить omit/reject неподдерживаемых values до переноса effort; затем узкие UI PR и device smoke. Большой controller/Compose/settings rewrite не входит в ближайшее задание.

### 5. Настоящие API и телефон

**LIVE-01 идёт после каждого подходящего green APK, не ждёт пунктов 3–4.** #103 убрал source-конфликт Claude cache 1h/5m, но живой повтор не доказан. #107 GitHub write и #109 camera capture не подтверждены настоящим аккаунтом/телефоном; CI не доказывает биллинг.
Первый шаг: взять artifact Successful Build #109 или следующего исправленного code head; короткие OpenAI/OpenRouter completion/Stop до и после ID, затем длинный OpenAI/web tools и Claude/cache/web tools при необходимости. Записать provider/model, local/HTTP/response/generation IDs, timestamp/timezone, terminal status, usage/cache/cost source; задержку сайта отмечать pending. Телефон: `+`, camera/photo/files, Android bars/IME, Drawer. GitHub smoke — разрешённое чтение и одно безопасное подтверждение/отмена; реальный merge для smoke не нужен. Ключи остаются в приложении; новых оплачиваемых прогонов без согласования не делать.

### 6. Остальные риски, GitHub и сопровождение

**RISK-02/RISK-03/GH-01/GH-02/DOC-01**, после ближайшей волны или при новой регрессии. Preflight местами читает mutable chat, хотя тело уже snapshot; PDF/JPEG/text читает целиком и base64 может поднять peak heap. main `protected:false`; это владельческий/server вопрос, #107 его не решает. Docs-only app gate требует Verify handoff для любого markdown, workflow запускается только на handoff/tools; strict exact-head gate также расходится с допуском docs после tested code head в правилах выше.
Первый шаг: blocked-preflight chat-switch test на фактическое тело/ownership, memory/time замер допустимых больших файлов и негативный docs-only merge-policy test. Не исправлять предположения; сохранить lossless/EXIF, wire_path/черновики/#101 и #107 guards. Для docs проще отдельный PR/включение handoff до code CI; не разрешать старые CI для изменённого кода. Серверную защиту main согласовать с возможностями тарифа и conditional CI, не менять видимость private repo. Обновлять handoff после среза, отчёт исполнителя — task codes, PR/точные SHA/Successful run IDs, воспроизведение, LIVE и непроверенное; по завершении выбранных исправлений уведомить владельца для повторного аудита. README/Wrapper/gitignore/архив веток остаются отложенными.

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



Это не полный реестр: пять последних объединённых PR #109–#113. Старые доказательства — через GitHub историю и карту контрактов выше. Финальный код #113 проверен, но настоящий API/телефон остаются LIVE.

## История последних рабочих заходов — последние 5 PR

- **#109:** merged `73e677ab86d0a80a8c0016dcccd6ee03dcbfa2cb`; `+` attachment sheet (Camera/Photo/Files), FileProvider camera capture, Android test; final code head `fe98178be0fe1fd6e1b2c4dfa2060da86a8a8d59`, Build `37817541075` / Release `37817541030` — Successful. Real phone capture, gesture and provider attachment handling not yet verified.

- **#110:** merged `ee9ae96899ce823edd2fab4879bdad383adc5797`; docs-only post-109 baseline/priority handoff; final head `727a984a5740d82e7fa065e6ab4ad37fccfecaac`, Verify handoff `37822753073` — Successful. Build/Release Skipped, Android-код не менялся.
- **#111:** merged `5bd4901a42f9298f223a14dd81107fbbeeb04ea4`; docs-only task queue/source review handoff; Verify handoff `37835531788` — Successful, Android-код не менялся.
- **#112:** merged `0690c7b14752e2e7707cdbbe3dd5c49644a8b1b0`; ограниченная часть BILL-01, code head `ff38f8bee80c9d37dc44bf6c92431df264d80a06`, final PR head `e92d30b6b051e9e4610b152f59c97dd61400cf5e`; APK push `37848391691` и Release push `37848391612` — **Successful**, включая instrumentation; Verify handoff `37848396725` — Successful. SQLite v18/unknown vs zero/partial OpenRouter cost. Full BILL-01 PARTIAL; LIVE не было.
- **#113:** merged `601fe4b3f0981440454fcb578745010f80be8789` (2026-10-09 01:28:59 +03:00); STREAM-01 code head `da21cf236de3e06b8aef50b96248a9bd9b957835`; Build APK push `37852576665` — **Successful**, включая Android lifecycle instrumentation; Release push `37852576727` — **Successful**; Verify handoff `37852582803` — **Successful**. Responses SSE terminal/EOF/error classification, shared foreground/recovery reader, output fallback, duplicate terminal guard, MockWebServer tests. Нет реальной LIVE проверки.

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
**Не скрывать строку событий**: вернуть компактную цветную нажимаемую строку **без рамки/card background**, раскрытую историю закрывать отдельной кнопкой «Закрыть». Уменьшить высоту шапки, но сначала учитывать status/navigation bars через Insets; fullscreen без Android status bar не включать автоматически — только после device UX-проверки. Drawer должен открываться свайпом от левого края и закрываться обратным жестом: AndroidX `DrawerLayout` уже умеет это по умолчанию; **сначала проверить на телефоне**, нет ли фактического конфликта gesture navigation. В composer после #109 кнопки `📎`/`🖼` уже заменены на единое `+` (не возвращать обратно); убрать повторяющийся badge модели и надпись GitHub (настройки оставить); effort у поля ввода по мотивам UI Claude, `standard/pro` показывать лишь если поддерживает source+model. В ответных пузырях хранить и отображать model на момент ответа, не текущую глобальную; основа уже есть в SQLite v17 #105, но Worker producers нужно дополнить BILL-02. **Settings IA:** вместо длинного общего диалога сделать компактные логические разделы подключения, мышления, инструментов, файлов и Advanced; перенести частые действия к composer без дублирования, предусмотреть доступность всех старых опций. **Billing:** пользователь сообщил суммарное списание **$0.20 в OpenAI** после отмен; привязки к request нет. В OpenRouter app показывает $0, но сайт ещё не показал активность, списание не подтверждено. При возможности запрашивать реальный usage/cost отменённого response и связывать с конкретным request; если provider не отдаёт данные, честно показывать неизвестный расход, не `$0.0000`. **Первый шаг:** ближайшая волна BILL-01/STREAM-01/BILL-02/03/04, затем instrumented UI/IME/edge swipe smoke; подробности в `docs/audits/2026-10-08-live-and-independent-triage.md` раздел 5.

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

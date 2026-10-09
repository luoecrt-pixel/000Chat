# NEXT_AGENT_HANDOFF

**Last updated:** 2026-10-10 00:35 +03:00
**Следующий рабочий срез:** Отправка, сохранность и повседневный интерфейс — завершить существующий draft PR #136 (APP-04-PHONE), проверить новый APK после test-only коррекции, затем следующий агент объединяет PR по гейту и начинает сохранённый APP-05 F21–F26.
**Текущая работа:** `main c492e74e3aaed8f87752be41e8ad2618d8215afa`; PR [#136](https://github.com/luoecrt-pixel/OpenAIApiChat/pull/136), ветка `agent/app-04-phone-send-ui-diag`. Проверенный исходный head перед этой коррекцией: `d0624e318fc8cc14b96d8879f6d55c7c87201b3c`. Не создавать заменяющий PR и не накладывать F18-патч повторно.
**Последний завершённый прогон:** APK [37988996183](https://github.com/luoecrt-pixel/OpenAIApiChat/actions/runs/37988996183) — Failure: выполнены 34 tests, один сбой в `deliberateHorizontalGestureOpensAndClosesChatDrawer`; Release [37988996250](https://github.com/luoecrt-pixel/OpenAIApiChat/actions/runs/37988996250) и Verify [37989003185](https://github.com/luoecrt-pixel/OpenAIApiChat/actions/runs/37989003185) — Successful, все на `d0624e3`. JVM/compile/lint/build успешны; APK не загружен, поскольку instrumentation не прошла.
**Точная ошибка:** `java.lang.IllegalArgumentException: Targeted input event injection from pid 4815 was not directed at a window owned by uid 10192.` Это отказ OS input injection в тесте, не AssertionError закрытия Drawer и не crash эмулятора. Какое именно чужое окно получило координаты, лог не устанавливает.
**Эта коррекция:** тест посылает полную DOWN/MOVE/UP последовательность через `Activity.dispatchTouchEvent` на main с `getLocalVisibleRect + getLocationInWindow`; сохраняет отрицательный vertical gesture и ожидание полного открытия/закрытия. Это проверяет Activity→Window→View routing, а не системный injector/физический телефон. Производственный код и workflow не меняются. Один новый полный branch `[ci]` нужен из-за изменения instrumentation; его исход ещё не установлен этим текстом.
**Указание владельца:** текущий исправляющий агент оставляет PR открытым и проверки запущенными. Следующий агент дожидается обязательных Successful, объединяет #136 и начинает APP-05. Телефонный smoke/реальные API остаются отдельно отмеченными; они не являются новым запретом начать APP-05 после разрешённого merge. Финансовое расширение отмены отложено. Автосборки после merge в main отключены в #134; запуск агентами в рабочих ветках сохранён.

# START HERE — ОБЯЗАТЕЛЬНО

**Основной документ агента — этот handoff. README на старте не нужен.**

1. Сверь фактические main, открытый PR/head, последние runs и их attempts. Запись «запущено» не означает Success; не исправляй уже закрытый дефект по старому описанию.
2. Перед кодом составь короткую матрицу «требование владельца → наблюдаемый результат → проверка». Сохраняй точное расположение/действие controls: здесь модель у ввода, ⚡ только quick effort, третья кнопка advanced; наличие кнопки не доказывает её поведение.
3. Сначала воспроизведи проблему и установи владельца состояния. Для lifecycle смотри Activity ↔ Executor ↔ Worker ↔ DB, для отправки — весь outbound context, включая исторические вложения.
4. Читай узкие функции/patches и failing часть лога; полный файл нужен лишь для конкретной проверки. Не загружай README или весь MainActivity без причины.
5. Проверь доступность Java/Gradle/SDK/эмулятора в начале. Изменённые runtime-сценарии запускай локально, если окружение работает. Static PASS не заменяет Kotlin compile/Android run; отсутствие среды честно запиши, не называй проверку выполненной.
6. Выполняй пакет внутри одного PR по частям с локальной проверкой каждой части; перед финальным CI интегрируй части и handoff. После падения нужен диагноз и конкретная проверенная правка, а не случайный rerun или отключение assert/защиты.
7. Пользователь не программист: ветки, PR, поддерживаемые запуск/merge по гейту делай сам в разрешённом scope. Не проси повторного согласия на уже порученное. Точные ограничения коннектора проверь по доступным инструментам; телефон/новые платные API требуют отдельного согласования.
8. Main изменяется только через PR, включая docs/workflow. Исключение — прямое «коммить в main». Учитывай чужие новые коммиты; перед записью перечитай head/blob, не force-push поверх работы другого агента.
9. После результата обнови фактический статус и доказательства, завершённый план перенеси в историю. Устаревшие инструкции и дубли удали; обязательные незавершённые задачи и ограничения сохрани.
10. Архитектурный/продуктовый обзор — **каждые 5 merged PR или раньше, если активный план опустел/сменил слой**. Compose, controller extraction, thread-safety и fork — разные слои; не подменяй текущую задачу rewrite.

## CI и merge-правила

- APK/Release: push только в ветки **кроме main**; полный job разрешён `[ci]` в head commit или `CI_AUTO_RUN=true`. В текущем подключении нет нового `workflow_dispatch`/repository-variable write; агент запускает рабочую ветку через `[ci]`. Ручной Run workflow у владельца сохранён, включая main.
- `push.branches-ignore: [main]` в обоих workflow исключает повторную Android-сборку после merge даже при старых `[ci]` в squash body. Не отключай рабочие ветки и не переводи всё в manual-only.
- Смотри event, SHA, attempt и conclusion конкретного run. PR-event Skipped при текущих условиях — ожидаемая строка, не успешная сборка; queued/in_progress/Skipped/Cancelled ≠ Successful. API `fetch_commit_workflow_runs` возвращает только PR-event runs: для push используй repository actions/runs с head_sha и event=push или уже известный run ID.
- Concurrency содержит workflow/ref/event, cancel-in-progress=true. Новый push той же ветки может отменить идущий run, даже docs push без `[ci]`; дождись завершения до записи, если нет поручения намеренно заменить прогон.
- Бюджет: промежуточные коммиты без `[ci]`, одна финальная пара APK/Release на кодовом пакете. Повтор — после установленной ошибки/исправления или доказанного инфраструктурного сбоя. Если код неизменен, transient failure и подходящий rerun доступен — перезапускай только failed job; повтор того же детерминированного падения ничего не проверяет.
- Verify handoff — дешёвый PR workflow для `NEXT_AGENT_HANDOFF.md`/`tools/**`. Docs-коммит отдельно без `[ci]`; если он объединён с необходимой test/code-коррекцией, полный прогон нужен из-за кода/теста.

**Merge-гейт.**

1. Приложение, его тесты или build/test steps workflow менялись: APK и Release push Successful на последнем коммите, меняющем эти пути, включая instrumentation. Docs-only последующие коммиты допустимы после проверки, что код не менялся; Verify на final PR head — Successful. Docs-only PR требует только Verify. Failed/Cancelled/Skipped не засчитывать.
2. Запиши code SHA + head SHA + run IDs + attempts/results; различай локальную проверку, CI, mock API, телефон и реального провайдера. «Тесты прошли» допустимо только для фактически прошедшего harness.
3. Проверь final diff/список файлов, PR body, отсутствие случайной полной перезаписи большого файла. Перечитай head прямо перед merge, не объединяй другой непроверенный SHA.
4. PR status подтверждай по `merged/merged_at`, не по наличию merge_commit_sha/«Able to merge». Force-push после запуска требует обоснования и новой проверки изменённого кода.
5. Scope запуска #134 уже проверен/merged: main исключён, jobs/steps сохранены. Не держи прежнее одноразовое исключение «CI ради выключения merge trigger не нужен» как разрешение обходить гейт будущего code/workflow PR.

## 30-секундная карта проекта

- Локальный multi-chat Android-клиент: OpenAI Responses и отдельный OpenRouter Chat Completions/SSE + local tool loop. GitHub API исполняет приложение, с repo/ref allow-list и подтверждением write/review/merge.
- Ownership: generation → immutable request/UI snapshot → request-aware persisted active response → WorkManager recovery. Поля `active_request_id/active_response_id/active_text/active_status/active_background_enabled/active_last_sequence_number`; устаревший request не финализирует новый.
- SQLite v20: messages attachments/metadata, request_events, независимый request_accounting и openrouter_request_rounds/rounds_sealed. Metadata и активные данные не стирать broad stale UI snapshot.
- MainActivity остаётся монолитом. Pure seams: ResponseEventReducer, ResponseCheckpointPolicy, BackgroundResponseUiOwnershipPolicy, AttachmentUploadCompletionPolicy; persisted boundary ActiveResponsePersistence. Foreground checkpoint cadence 750 ms.
- Нет коротких app read/call timeout, неявного output-token cap или fixed tool-round count. Аварийные границы: 5h response/recovery и 5M accumulated OpenRouter usage tokens. Сквозной durable deadline ещё задача F21.
- Вложения: content:// копируется в private storage; text/image/pdf/zip manifests + wire bytes. Оригиналы без скрытого lossy/downscale; preview отдельно. EXIF strict lossless по #83 при допустимой геометрии, иначе оригинал. Provider/upstream limits нельзя выводить из одного общего числа.
- OpenRouter сохраняет requested/response model, SSE partial/usage, верхний automatic cache_control 1h без конфликтующего nested 5m marker. Manual effort не понижается молча; auto разрешается до HTTP; Anthropic verbosity опускается по F18. Поддержка конкретного upstream — отдельное доказательство.
- OpenAI background recovery и OpenRouter — разные пути; OpenRouter не подключать к Worker автоматически. Секреты не попадают в DB/diagnostics. Неподтверждённая стоимость — unknown, не бесплатный ноль.

## Фактический baseline

| Срез | Подтверждение | Граница |
| --- | --- | --- |
| main после #135 | `c492e74e3aaed8f87752be41e8ad2618d8215afa`; Android baseline #133; SQLite v20 | #134 trigger-only, #135 docs-only |
| #133 final code | `73d9e4357838fbd55f6564b11e3806bb3f65bd65`; APK 37967095171, Release 37967095269, Verify 37967102030 — Successful; merged `8a4d019328c90745df4448bea5c5dc1e1a2d9351` | Телефон выявил новые PHONE-пробелы; старые зелёные тесты их не доказали |
| #136 исходный head этой коррекции | `d0624e318fc8cc14b96d8879f6d55c7c87201b3c`; unit/lint/build Successful; 33/34 instrumentation без сбоя, единственный failed — Drawer input injection | Release/Verify успешны, весь APK workflow Failed; merge запрещён до нового зелёного результата |
| Физический телефон | Владелец подтвердил камеру #128 и распознавание маленькой фотографии после #133 | Большие фото/media_type error, OR→GPT блокировка, thumbnails, жест/нижние controls/picker/логи — причины нового #136; installed SHA и upstream trace тех фото отсутствуют |

Новый код #136 уже внесён в его ветку: typed OpenAI local replay, фото/picker ownership, OR previews, нижние controls, диагностический SHA/error metadata. Прежние source-находки «верхняя row», «blanket OR guard», «нет preview» — описание базы до #136, а не доказательство дефекта в текущем head. Проверять текущий код и сценарий.

Сохранять #133: deep-copied Send snapshot, off-main history preparation, D2, narrow DB writers/terminal claim, request-bound approvals, partial/refusal, model metadata и reasoning transport. Финансовые #112–121 unknown/reported/ledger/rounds не переписывать ради PHONE.

## Подтверждённые lifecycle/recovery контракты

Единая карта доказанности: instrumentation — Android/ActivityScenario; robolectric — Android/SQLite без эмулятора; pure — JVM без Android runtime; static — source/verifier без runtime. Тег применяется к конкретному утверждению, а не ко всему PR. Старые записи — исторический proof; новые проверки и границы ниже.

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

**Дополнено по текущим тестам и завершённым CI, а не по одному факту merge.**

- [pure] **#107:** `GitHubToolSafetyTest` проверяет explicit allowed ref, non-draft/open target, exact head, только Successful push builds, latest failed attempt вместо старого Success и полное confirmation preview. Текущие JVM-тесты прошли на `d0624e3` (APK 37988996183, шаг Test, lint and build); это client policy, не серверная защита/реальная запись.
- [robolectric] **#112:** known zero и unknown не смешиваются; migration v18 не выдумывает provider cost из legacy estimated. `ChatDatabaseCostMigrationTest`, `OpenRouterAccountingTransportTest`, formatter tests; текущий unit step `d0624e3` Successful. Provider-reported mock data не доказывает фактические расходы.
- [robolectric] **#115:** ledger сохраняет immutable captured identity и nullable usage, переживает reopen/v18 migration, idempotent по request ID; late cost после Stop не воскрешает state, Delete/clear блокируют late callback. `ChatDatabaseRequestAccountingTest`, `BackgroundRequestFailureRecorderTest`; текущий unit step `d0624e3` Successful.
- [robolectric] **#121:** v20 round ledger/rounds_sealed, все раунды нужны для known total; повторные usage frames/Stop/late/Delete/reopen не удваивают стоимость. `OpenRouterAccountingTransportTest`, `OpenRouterGenerationAccountingTest`; текущий unit step `d0624e3` Successful, live generation lookup не доказан.
- [robolectric] **#125:** Stop event принадлежит captured request, старый cost не становится ценой нового Stop; terminal claim/Stop mutually exclusive, один recovery winner. `ChatDatabaseActiveFinalizationTest`, `RequestDisplayPolicyTest`; нынешние narrow append/metadata race tests также прошли на `d0624e3`. Pixel UI и billing не следуют из SQLite.
- [pure] **#126:** `ModelRequestPolicyTest` проверяет mode/effort independence и omission unsupported mode/configuration_update. Текущий unit step `d0624e3` Successful. Старый контракт наличия controls не подтверждал нижнее расположение, IME или три разных click outcomes; бывшие UI/normalization указания #126 заменены #133/#136 и уточнением владельца.
- [instrumentation] **#128:** camera-result после switch/recreate остаётся в исходном OpenAI/OR draft; Cancel удаляет только temporary photo. `cameraResultAfterChatSwitchAndRecreateKeepsOpenAiSourceDraftWithoutUploading`, OR counterpart, `cancelledCameraAfterNavigationDeletesOnlyItsTempPhoto`; камера отдельно подтверждена владельцем, mock callback не доказывает весь Camera/picker UX.
- [instrumentation] **#133:** APK 37967095171 на `73d9e435` Successful, включая Android lifecycle; исправленные #59/#60 owner-aware Stop/recreate confirmation-тесты прошли. HTTPS Send→body fixture, Stop/failed preparation/draft, исходный ZIP/copy owner проверены конкретными ActivityScenario; pure/SQLite гарантии перечислены ниже отдельно, общий CI не доказывает все F01–F20 на телефоне.
- [instrumentation] **#136:** на `d0624e3` APK 37988996183 выполнил 34 теста с одним failed Drawer injector, значит остальные 33 завершились без failure/skip: `openAiHistoryWithoutResponseChainNeverSilentlyDropsFileInputs` (async failure, zero HTTP, preserved draft/history), source picker/recreate и `realLargeJpegIsUnchangedAcrossHttpsSendAndFollowingTextHistory`. Это частичный proof конкретных сценариев; весь PR ещё не прошёл обязательный APK-гейт.

| Актуальный контракт APP-04/PHONE | Конкретное доказательство | Ограничение |
| --- | --- | --- |
| Stale snapshot не стирает новый Worker ответ/metadata; Stop/terminal имеет одного победителя | `ChatDatabaseActiveFinalizationTest`: sameLengthStaleUiSnapshotCannotOverwriteCommittedMetadata, terminalClaimPreservesDbRowsAndMetadataAddedAfterStaleSnapshot, concurrentStopAfterTerminalCannotEraseCommittedAssistant; Robolectric unit step `d0624e3` Successful | Это DB race/ownership, не полный набор UI callbacks |
| xhigh/max не понижаются, unresolved auto не уходит в HTTP, Claude verbosity не перекрывает effort | `OpenRouterReasoningPolicyTest`, `OpenRouterChatClientTest.claudeXhighAndMaxSurviveEveryToolRoundWithoutVerbosityOverride`; JVM step `d0624e3` Successful | Wire JSON/tool rounds, не обещание поддержки любым upstream |
| OR history replay в OpenAI использует реальные локальные bytes, typed input без чужих file_id | `OpenAiLocalContextEncoderTest`: realJpegPhotoSurvivesManifestPersistenceAndProviderReplay, mixedLocalContextEncodesAllWithoutForeignIds, missingLocalBytesStopBeforeProducingAnyInput; JVM step `d0624e3` Successful | Encoder, не живой запрос GPT; недоступный файл всё ещё требует понятного отказа |
| OpenAI unavailable/stale history chain не запускает HTTP и не теряет D2/history | `MainActivityLifecycleTest.openAiHistoryWithoutResponseChainNeverSilentlyDropsFileInputs`; instrumentation `d0624e3` passed | Ключ задан, async preflight дождались; это failure path, не доказательство live success replay |
| Валидный большой JPEG (>6 MB) сохраняет wire bytes в Send и следующем текстовом history | `realLargeJpegIsUnchangedAcrossHttpsSendAndFollowingTextHistory`; instrumentation `d0624e3` passed, local HTTPS MockWebServer, decoded bytes сравниваются | Не исходное проблемное фото владельца и не настоящий Claude |
| Picker-result после switch + recreate принадлежит source chat | `externalImagePickerResultAfterChatSwitchAndRecreateBelongsToSourceChat`; instrumentation `d0624e3` passed | Синтетический result; Photo Picker/MiXplorer вручную ещё не проверены |
| Model/effort/advanced в одной composer row ниже header | `compactHeaderKeepsModelPickerAndComposerEffortAccessible`; instrumentation `d0624e3` passed, screen geometry + shared parent/listeners | Пока нет runtime проверки всех click outcomes, IME/малой высоты/large font; не объявлять полную UI-приёмку |
| SSE/HTTP error сохраняет typed status/provider/type/body size; логи редактируют secrets/Base64 | `OpenRouterChatClientTest.streamingProviderErrorPreservesTypedMetadataAndRequestBodySize/httpErrorPreservesStatusProviderAndType`, `AstraDiagnosticsPrivacyTest`; JVM step `d0624e3` Successful | Не live upstream trace и не ручная проверка selection/export/log grouping |
| Refusal/fragmented text сохраняются, rich text не теряет underscores/code/link destinations | ResponseEventReducer/OpenRouterChatClient/RichTextRenderer tests, unit step `d0624e3` Successful | Copy/TXT/UI на конкретном телефоне проверяются отдельно |

**Пока не подтверждено / не повышать тег автоматически.**

- Drawer open/close: на `d0624e3` failed injector; нынешнее window-dispatch исправление ещё ждёт нового CI. Даже успешный Activity window test не подтверждает системную инъекцию/жест на телефоне.
- Source-only старые границы: полный direct OpenAI Stop wiring (#20), отдельный runtime adapter proof ActiveResponsePersistence (#37), reasoning callback (#40), все image/ZIP completion ветви (#42), бесконечно quiet 5h recovery stream/сквозной budget (#78/#84 → F21). Новые tests закрывают названные ветви, не всё семейство.
- Настоящие Claude/OpenAI: проблемное большое фото, живой provider switch/long-running stream, upstream-specific image/effort limits, cache hit/TTL/реальная стоимость, EXIF-интерпретация non-ideal/mirrored фото. Маленькое фото и камера — ограниченные наблюдения владельца.
- Телефон: три click outcomes, gesture/Back/selection/code scroll, IME/large font, настоящие Photo Picker и MiXplorer, previews/reopen, selectable Copy/share/latest logs, установленный build SHA. Читаемая ошибка и отсутствие нового выдуманного лимита важнее «mock accepted».
- GitHub: read подтверждён прежним использованием; реальные write/review/merge/workflow от приложения не испытаны. Branch protection — отдельное серверное ограничение.
- Полный async surface/heap/cleanup/vector-store/read >1 MB ещё не закрыт; APP-05 ниже. Не называть source audit или aggregate green доказательством отсутствия прочих ошибок.

**Как поддерживать этот раздел:** после каждого изменённого контракта запиши утверждение, конкретный test/наблюдение, harness, SHA/run/attempt/result и границу. При новом успешном прогоне обнови также ранее Failed/«ещё ждёт» записи, а не добавляй противоречивую строку сверху. Исторический тег не переносится на изменённый runtime автоматически. Закрытый контракт переоткрывай только при новом доказательстве регрессии.

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

Редакция 11: существующий #136, затем один сохранённый APP-05 F21–F26. Отдельные SEND/UI/DIAG PR, новый финансовый ledger и Compose rewrite не входят в пакет.

### 1. Отправка, сохранность и повседневный интерфейс — **следующий**

**APP-04-PHONE / #136. Первый шаг:** перечитать actual PR head и результаты новой пары push runs после текущего test-only fix. Если снова Failed — забрать named failure/stack/assert, сопоставить harness с кодом и исправить конкретную причину в той же ветке. Не повторять старые «нет API key»/«один MOVE» диагнозы: на d0624e3 OpenAI test passed, остался OS input injection. При зелёном гейте следующий агент снимает draft, мержит #136 и начинает APP-05 по указанию владельца.

**Обязательный scope остаётся критерием приёмки, а не очередью переписывания уже внесённого кода.**

| Блок одного PR | Наблюдаемое поведение / приёмка |
| --- | --- |
| SEND | Большое/историческое фото проходит MIME/body подготовку по настоящим bytes; OR→GPT replay совместимых локальных вложений в том же чате; понятная ошибка недоступного input до очистки draft. Thumbnail composer/bubble/reopen; Фото открывает native photo grid; Files выбирает совместимый handler; picker-result принадлежит source-chat после switch/recreate. |
| UI | Только три controls внизу у ввода: model/provider; ⚡ quick effort; маленькая advanced button. Верхняя model row отсутствует. Quick выбор закрывается после уровня, advanced даёт полную панель. Drawer открывается вправо на chat surface, закрывается влево; vertical scroll, Back, selection/code scroll и IME работают. |
| DIAG | Диагностика сразу под API keys; группы request с временем/model/provider/stage/outcome/reason. Выделение/Copy/share/export, старт на последних событиях и «В конец»; не ломать чтение старого. Git SHA; safe upstream/attachment descriptors без credentials/base64/body. |

**SEND — сохраняемые точные условия.**

- Проверяй все outbound messages/content, включая старые фото следующего текстового Send. Real decodable JPEG 6–7 MB, маленький image, persistence/reopen/mixed history; по каждому source/wire MIME, bytes/Base64 length, dimensions/hash/index. HTTPS mock декодирует payload и сравнивает с оригиналом. JPEG signature из четырёх байт не изображение.
- media_type enum error не доказательство лимита размера. Сохрани provider/code/type, local request/round ID и стадию, sanitized attachment descriptors; никаких произвольных raw_error/full body в лог. Большое mock image не доказывает acceptance реальным Claude.
- Не делать silent text-only fallback, удаления старых attachments, foreign file IDs/stale previous_response_id, lossy/downscale, публичной загрузки или оплачиваемого automatic retry. При недоступных bytes остановить до расходования draft/HTTP с конкретной причиной; уменьшенная wire-copy только по явному выбору пользователя, оригинал сохранить.
- OR↔OpenAI replay: typed local context целевого API; проверить history + composer, stale response chain и full-history fallback. Доступное локальное фото не требует нового чата.
- Preview bounded и отделён от wire; absence preview не удаляет attachment. Photo через AndroidX PickMultipleVisualMedia/ImageOnly + fallback; Files chooser/preferred **совместимый** installed handler без выдуманного MiXplorer package. content:// сразу private copy; GET_CONTENT не требует persistable grant.
- Source-chat/provider/operation захватывается до picker и переживает recreation; Cancel очищает лишь свои temporary bytes; текущий чужой draft D2 не меняется.

**UI/DIAG — сохраняемые точные условия.**

- Model/quick/advanced только composerOptions у ввода, prefs/keys не сбрасывать. Effort/compatible mode/summary/verbosity/max output/format/schema в advanced; ручные xhigh/max и resolved auto из #133 сохранить. Новый выбор меняет следующий, не captured активный request. Temperature не effort.
- Header: title/chats/new-chat/gear; Drawer без duplicate Settings footer. Проверяй именно geometry и три click outcomes; small-height/IME/large font отдельно. Настоящее движение касаний проходит View dispatch; helper openDrawer вместо swipe не доказывает gesture.
- Logs: bounded tail/rotation, main без большой I/O; выделение, исходный Copy/export, latest scroll без автопрыжка при чтении/selection. Error persist не зависит только от visible UI gate; новая financial DB/tracing framework не нужна.
- Устройство: установленный Git SHA должен совпасть с tested code; local build честно local-unknown. Один итоговый APK владельцу для PHONE smoke; результаты и неизвестности записать, не смешивать mock/phone/live.

**Не переоткрывать без новой регрессии:** #133 deep snapshot/D2, registry одной upload/download operation, serialized source draft, ZIP Cancel, file identity/cache, narrow DB claims/append, Stop/terminal winner, recovery reconciliation/text+cursor, Edit/Delete/Repeat, owner approvals, refusal/OR deltas, model metadata и Copy/TXT. Прежняя верхняя model row/full panel из ⚡ — отменённое ТЗ, не контракт.

### 2. Долгие операции, ресурсы и инструменты — следующим общим пакетом

**APP-05:** один следующий пакет F21–F26 после успешного #136 и разрешённого merge. По последнему указанию владельца начать его затем; телефонный smoke записать как отдельную остающуюся проверку, а не вводить новую паузу. Не расходовать Android CI лишь на обзор; отдельное изменение отделять только при конкретном независимом schema/transport риске.

**Первый шаг:** воспроизвести F21 fake clock/blocked stream, F22 synthetic stream/heap, F23 operation cleanup и F24 delayed dialog fixture; затем выбрать узкие ownership/budget изменения. Не ждать 5 часов в CI и не вводить короткий response timeout.

| Задача | Установлено | Проверяемый результат |
| --- | --- | --- |
| F21 · P1: сквозной 5h recovery budget | SOURCE: новый clock у Activity/Worker, quiet stream не прерывается deadline | Один durable remaining budget через lifecycle/process hops; fake clock + blocked stream/cancellation |
| F22 · P2: большие файлы/heap | SOURCE: raw copy без byte/cancel budget, whole-file text/Base64; OOM ещё не измерен | Synthetic streams/heap measurement → bounded I/O и понятные ограничения без silent compression/truncation |
| F23 · P2: temporary/partial files | SOURCE: ZIP/copy/decode/download exceptions оставляют свои незавершённые файлы | Failure/Cancel/retry cleanup operation paths, READY/referenced bytes сохраняются |
| F24 · P2: Vector Store | SOURCE: глобальный dialog callback owner, unlimited control call, limit=100/delete error скрыт | Dialog/store identity, finite metadata-call budget, Cancel/error, pagination/явный неполный список |
| F25 · P2: GitHub getFile | SOURCE/API: encoding=none/decode failure могут дать ok=true/empty | Bounded разрешённый raw/blob или понятный unsupported; настоящий пустой файл отличается |
| F26 · P2: docs gate | SOURCE: client требует Verify для любого Markdown, workflow только handoff/tools | Согласовать client gate/trigger; server branch protection отдельно у владельца/доступного тарифа |

**Не делать сейчас:** полный financial reconciliation отмены, новые автогенерации/retries, controller/Compose rewrite, инфраструктуру product radar. «Расход неизвестен» допустим. Серверную защиту не обещать без проверки возможностей аккаунта.

## Репозиторий / branches

Репозиторий `luoecrt-pixel/OpenAIApiChat`; main только через PR. Продолжай actual open branch, не создавай stacked копию старого snapshot. Перед записью сверяй head и blob SHA; поддерживаемый lease при ref update, без blind force-push. Удаление старых веток не нужно для этой задачи. В текущем коннекторе можно читать push runs/jobs/logs и rerun существующий failed job; создание нового dispatch/repository variable недоступно.

## Контекстный режим для агента

Существующая запись — гипотеза до сверки с current source/test/run. Историю ошибок и отменённое UI-ТЗ не выдавай за новый backlog. Источники доказательств не взаимозаменяемы: exact test assertion → выполненный harness → конкретный SHA/attempt; соседний test и общий summary не расширяют этот claim. Вне репозитория могут быть прежние планы/отчёты, но обязательный APP-05 scope сохранён здесь.

## Как проверять новую работу

**До первого изменения.**

- Выпиши точные acceptance criteria с владельцем состояния/путём и негативным случаем. Для UI нужны положение и click/gesture outcome, для attachments — реальные декодируемые bytes и full historical payload, для async — matching source/request/generation + D2.
- Environment preflight: рабочие JDK 17, Gradle 8.9, SDK 35/build-tools 35, adb и API34 emulator/KVM для instrumentation. Выполни `java -version`, `gradle --version`, `adb devices` и доступные targeted команды, не ограничивайся наличием executable. В этой среде 2026-10-10 launcher Java падает с missing libjli.so, Gradle/adb отсутствуют; настройка в writable workspace разрешена, если доступна, иначе честно «Android/Kotlin локально не запускались», доступные verifiers + один обоснованный CI.
- Проверяй части пакета по мере изменения: изменённые JVM tests, compile Debug/AndroidTest, затем затронутые instrumentation до финального полного CI, если среда работает. Не откладывай все runtime дефекты на Actions.
- Test fixture должен доходить до границы: задан isolated test API key, разрешённый owner, правильные prefs, controlled local HTTPS client (cleartext production не ослаблять), restore в finally, zero real paid calls. Async preflight/animation ждать по условию с bounded deadline, не fixed sleep вместо результата.
- Жест: полная DOWN/несколько MOVE/UP, координаты того пространства, куда событие отправляется, после layout settle. Activity window dispatch проверяет View routing; системная injection дополнительно требует focus/IME/screen ownership. Это разные proof, именуй их честно. Assert actual open/fully closed/negative vertical; не вызывай openDrawer/closeDrawer как замену действия.
- Новые test seams делай test-visible и узкими; существующие reflection fixtures не переписывай автоматически. Не добавляй fragile name-mangled private access. ContentProvider в androidTest уже приводил к Kotlin Intrinsics failure; пользуйся инжектируемым AttachmentInputStreamSource.

**После изменения.**

- Проверь diff, старые helpers/symbols и обработку failure/Cancel/Stop/delete/recreate. Не расширяй scope ради «поправить заодно».
- Нужно реально запустить static verifier и любой новый/изменённый verifier-скрипт, дождаться вывода: чтение кода вместо запуска не считается проверкой. Source/static проверяют свой invariant; не объявляй compile, UI или API green на их основании.
- Handoff: `python3 tools/verify_handoff.py`, затем Successful Verify на PR. Уровни заголовков/план/contract tag должны сохранять schema, а verifier проверять инварианты, не конкретный номер PR/текущий active пункт.
- Новый verifier/lint: намеренно воспроизвести целевую поломку; позитивный и негативный кейс обязательны оба. Как минимум три релевантные mutations именно нового правила, затем restore/PASS; syntax error не substitute. Mutation harness хранить/запускать в CI.
- Финальный CI один раз на собранном пакете + handoff; ожидай результат/следующий агент получает точные run links и обязан проверить их, когда поручено оставить прогон. Никакого «готово/исправлено полностью» до pass соответствующих assertions.

**Если проверка упала.**

1. Сверь SHA/event/attempt и failing step. Возьми named test, одну дословную failing line и короткий stack/assert; различай compile/test fixture/product/infrastructure. Warning/Skipped соседнего run не причина Failed.
2. Установи, дошёл ли тест до целевой границы: key/owner/lifecycle/async settle/window coords/fixture/TLS. «Это тест» без доказательства не разрешает удалить assert или guard.
3. Исправь установленную причину, прогоняй changed scenario и compile AndroidTest локально при рабочей среде; проверь, что исходный негативный случай ещё ловится. Пару повторяющихся failures разбирай вместе, чтобы не тратить отдельный full run на каждую строку.
4. Повтор только после диагноза; тот же SHA rerun уместен при установленном transient infrastructure сбое. Если менялся тест/код — новая пара на новом code SHA, прежний Release не покрывает новый тест.
5. После pass обнови исходный Failed/ожидание в top/baseline/contracts/PR body; contracts без exact evidence не повышать. Проверка на телефоне/платном API отдельно, без выдуманного завершения.

### Командная шпаргалка

В рабочем checkout из корня (Gradle установлен отдельно; wrapper отсутствует):

- Handoff: `python3 tools/verify_handoff.py`
- Source: `python3 tools/verify_v4_17_0.py`
- Existing mutation matrix: `python3 tools/test_verify_v4_17_0_mutations.py`
- Targeted JVM: `gradle --no-daemon :app:testDebugUnitTest --tests 'com.example.openaichat.<ChangedTest>'`
- Compile app/test APK: `gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest`
- Targeted Android (подключён emulator): `gradle --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.openaichat.MainActivityLifecycleTest#<changedMethod>`
- Full Debug: `gradle --no-daemon --stacktrace test lintDebug assembleDebug`, затем `gradle --no-daemon --stacktrace connectedDebugAndroidTest`
- Full Release: `gradle --no-daemon --stacktrace test lintRelease assembleRelease bundleRelease`

Провайдерские лимиты и capabilities сверяй с официальным актуальным контрактом при изменении wire builder; offline mocks не доказывают acceptance/биллинг.

## История последних рабочих заходов — последние 5 PR

Последние пять merged PR на проверенной базе #135, до текущего code PR #136. CI proof относится к соответствующему code/head SHA; phone proof отдельно.

- **#131:** широкий docs audit revision 8, merge `18c9cd23d7dbda0f09f5543bac93d66e83dc806e`, head `b22e6e434ee06fa7f1e7c6bd4c37ca11ba8df36f`; Verify `37935958150` — Successful.
- **#132:** docs revision 9, merge `c9069333050d85bc1db6105c1c7ad4d9dd68c02d`, head `debd9ee6b77bf9bc66c3e638bbb0c63de2a11fc8`; DB writers/preflight/Repeat design уточнён без Android-кода.
- **#133:** APP-04 A/B/C + F18 + FIN133, merge `8a4d019328c90745df4448bea5c5dc1e1a2d9351`. Final-code `73d9e4357838fbd55f6564b11e3806bb3f65bd65`: APK `37967095171`, Release `37967095269`, Verify `37967102030` — Successful. Ошибки прежних тестовых прогонов исправлены; source guards/HTTPS policy не ослаблены. Phone acceptance остаётся частичной, новые находки включены в APP-04-PHONE.
- **#134:** main push-trigger exclusion APK/Release при сохранённом branch [ci]/manual dispatch, merge `a5a99f3cd1c6dba60cba0235bb9995df25fe4195`, head `883d76ccc07b223b9f8a538d57d2f6e0343e390b`; Verify `37972109767` — Successful. YAML/routes/jobs неизменность проверены, Android CI ради trigger-only правки не запускался.

- **#135:** docs APP-04-PHONE revision 10, merge `c492e74e3aaed8f87752be41e8ad2618d8215afa`, head `54948e341c1c096e928c369b115a218f835f04a2`; Verify `37980410307` — Successful. Требования телефона объединены в SEND/UI/DIAG, прежний APP-05 F21–F26 сохранён.

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
**Системный промпт реализован в #98 (v1)**: глобальное поле, отделённое от существующего Developer instructions; OpenAI получает типизированные system/developer instructions, OpenRouter — отдельную пользовательскую часть в system text вместе с app контекстом. Не подтверждено живым API/UI. **«Градус» = temperature (sampling/randomness), а не reasoning effort**: это два независимых рычага; `top_p`, `top_k`, reasoning effort/summary, verbosity, tool-choice и structured output тоже зависят от API source + model, универсального набора нет. Сейчас `temperature`/`top_p` не экспонируются; старое silent xhigh/max → high устранено #133, manual/auto policy сохраняется. Совместимость конкретных моделей/upstream не выводить из одного общего списка уровней. OpenRouter model endpoint отдаёт `supported_parameters`, но реальная совместимость также зависит от upstream provider. Первый шаг: описать pure `ModelCapabilities`/`GenerationSettings` contract для обоих request builders, затем regression matrix для unsupported/omitted parameters, invalid ranges и неявной смены effort. Дальнейшие ползунки UI — только после этого; per-chat profiles — отдельная задача.

**Черновик идей — без фильтра, до следующего обзора**
Сюда можно писать сырое: без доказанной ценности и без первого шага — это специально разрешено, чтобы не отсеивать идею раньше, чем она успела оформиться. Максимум 5 строк. На каждом архитектурном/продуктовом обзоре: по каждой старой записи — либо дожать её до формата Product radar (с «Первый шаг:») и перенести выше, либо вычеркнуть одной строкой почему; добавить не больше одной новой записи, обязательно вдохновлённой взглядом наружу — как эту же проблему решает другой multi-chat/Responses API клиент, либо какая уже существующая pure-архитектура (`ResponseEventReducer`, `ActiveResponsePersistence`, `AttachmentUploadCompletionPolicy`) недоиспользуется и что даст её переиспользование иначе.

(пусто — заполнится на следующем обзоре)

### Что сознательно не тянуть сейчас
- MCP и Computer Use — пока нет доказанной продуктовой потребности и это резко увеличивает execution surface.
- Полный Compose rewrite — инфраструктурная стадия, не пользовательская функция.
- «AI сам организует чаты/саммари/теги» — выглядит эффектно, но пока не доказано, что это важнее поиска, обратимости и прозрачности контекста.
- Синхронизация между устройствами — отдельная security/backend задача, не естественное продолжение текущей local-first архитектуры.

## Документация

Операционная истина: top/baseline — текущий PR/CI; подтверждённые контракты — proof/границы; активный план — только незавершённое; история — последние 5 merged PR; Product radar — идеи без обещания реализации. Не повторяй один scope в каждом разделе и не сохраняй отменённые требования как активные.

README устарел (v15/baseline #42); **по просьбе владельца не редактировать README**, включая косметику. Исторический `docs/audits/2026-10-08-live-and-independent-triage.md` не переопределяет нынешний PHONE/APP-05. Пересылаемый отчёт независимому аудитору ведётся отдельно от репозитория. Архивы notes/отсутствие wrapper не важнее data-loss/runtime bugs.

Жизненный цикл: plan → implementation → check → merge → history. Контракты обновляются вместе с кодом: actual assertion/harness/SHA/run + что не доказано. Единственный новый текущий пункт не дублировать в radar; согласованный APP-05 не удалять при чистке.

## Короткий отчёт пользователю

Достаточно 3–6 строк: что изменено и почему; что реально прошло (SHA/run/результат); что осталось; ссылка на PR/актуальный handoff. Если прогон оставлен running — скажи прямо, PR открыт/merge не выполнялся. Не выдавай компиляцию или 33 passed/1 failed за полностью проверенное приложение.


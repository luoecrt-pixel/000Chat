# NEXT_AGENT_HANDOFF

**Last updated:** 2026-10-10 08:50 +03:00
**Следующий рабочий срез:** Проверка на телефоне и выбор следующего среза — подтверждение реального поведения APP-04/APP-05, затем обязательный обзор архитектуры и приоритетов.
**Текущая работа:** PR [#137](https://github.com/luoecrt-pixel/OpenAIApiChat/pull/137) APP-05 F21–F26 **merged 2026-10-10**, проверенный squash merge `e330497967503ef781767ca49e0f2668a805dda6` = текущий `main` при создании docs-only ветки `agent/app-05-postmerge-handoff`. Финальный кодовый SHA до squash `fe0b5b2597226f5690b502dc462474d07a25292b`; docs-only head #137 `a3d3f0df7af6b76b7c59b8dfc55b2ffe567aa6ab` прошёл Verify `38028778105`. PR #138 (этот файл) только корректирует handoff после merge; Verify нужен на его финальном head. Следующий агент перечитывает actual main/PR state, а не предполагает постоянство SHA.
**Точные APP-05 проверки:** [APK push 37998337416](https://github.com/luoecrt-pixel/OpenAIApiChat/actions/runs/37998337416) **Successful**, включая source verifier, mutation matrix, JVM unit, lint, assembleDebug, Android lifecycle instrumentation и Upload APK; [Release push 37998337154](https://github.com/luoecrt-pixel/OpenAIApiChat/actions/runs/37998337154) **Successful**, включая release build и Upload release artifacts; [Verify PR 37998347081](https://github.com/luoecrt-pixel/OpenAIApiChat/actions/runs/37998347081) **Successful** — все на `fe0b5b2`. Артефакты: debug `11648259445` и release `11647807945`. Дополнительный Verify `38028778105` Successful на итоговом docs-only head PR #137 `a3d3f0d`; PR #137 merged как `e3304979`. Skipped PR-event APK/Release не считать сборкой.
**APP-05 итог:** F21 durable 5h remaining budget от persisted `started_at` через initial Send/Activity resumed/Worker SSE, включая blocked-socket timeout; F22 bounded/cancellable attachment copy (25 MiB для OpenRouter, 50 MiB для VS upload), F23 очистка operation-owned partial/ZIP/upload при exception; F24 Vector Store dialog/store ownership, до 3 страниц × 100, максимум 10 filename-lookups и видимое incomplete; F25 GitHub `get_file` fail-closed при non-base64, bad UTF-8, absent/oversized content (только UTF-8 base64 ≤256 KiB, отображение до 40000 знаков); F26 Verify при root/nested Markdown. Тесты подтверждают названные pure/Robolectric/ActivityScenario границы, а не всё семейство async/heap/live API.
**Устранённые старые failures:** на `ce55889b` verifier ожидал неизменённый UI gate, `cd8aa0d` Kotlin не нашёл импорт Base64 writer, `9bc2d66` lint поймал неправильный отступ; новые APK/Release на `fe0b5b2` все Successful.
**Оставшиеся границы:** физический телефон и исходные большие фотографии, реальные Claude/OpenAI, 5 часов реального SSE, MiXplorer/Photo Picker/IME/gesture, Vector Store provider/dialog races и биллинг не проверены. Платных вызовов и доступа к телефону не делать без разрешения владельца; README не менять. По #134 автосборка Android на push в main выключена; ручной workflow и branch [ci] сохранены. GitHub Actions не отменять вручную.

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
- Verify handoff после F26 запускается для корневых/вложенных `*.md` и `tools/**` и согласован с клиентским docs-only merge gate; успешный Verify нужен на final PR head. Docs-only коммит без `[ci]`, новые code/test изменения потребуют новую пару push APK/Release.

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
- Нет общего короткого timeout для самой генерации, неявного output-token cap или fixed tool-round count. APP-05 F21 ввёл persisted 5h response deadline для OpenAI initial/resumed/Worker SSE; Vector Store metadata отдельно использует короткий 12s call timeout. OpenRouter имеет 5h cycle и 5M accumulated usage tokens; fake-clock proof не заменяет live 5h.
- Вложения: content:// копируется в private storage; text/image/pdf/zip manifests + wire bytes. Оригиналы без скрытого lossy/downscale; preview отдельно. EXIF strict lossless по #83 при допустимой геометрии, иначе оригинал. Provider/upstream limits нельзя выводить из одного общего числа.
- OpenRouter сохраняет requested/response model, SSE partial/usage, верхний automatic cache_control 1h без конфликтующего nested 5m marker. Manual effort не понижается молча; auto разрешается до HTTP; Anthropic verbosity опускается по F18. Поддержка конкретного upstream — отдельное доказательство.
- OpenAI background recovery и OpenRouter — разные пути; OpenRouter не подключать к Worker автоматически. Секреты не попадают в DB/diagnostics. Неподтверждённая стоимость — unknown, не бесплатный ноль.

## Фактический baseline

| Срез | Подтверждение | Граница |
| --- | --- | --- |
| main после APP-05 #137 | `e330497967503ef781767ca49e0f2668a805dda6` — подтверждённый squash merge #137, SQLite v20 | Новый docs-only #138 не меняет Android-код; фактический main на каждом новом заходе сверять |
| #136 APP-04 final | `613bff2155838b5b1988df4b478d3bf601a83882`; APK `37994461394`, Release `37994461425`, Verify `37994465824` — Successful; merge `0308fd67` | Android emulator/HTTPS mock, не физический swipe, live provider или проблемное исходное фото |
| #137 APP-05 final code | `fe0b5b2597226f5690b502dc462474d07a25292b`; push APK `37998337416` включая instrumentation, push Release `37998337154` — Successful; final docs-only head `a3d3f0d`/Verify `38028778105` — Successful; merge `e3304979` | Тесты не покрывают реальную 5h сессию, весь heap/физический телефон |
| APK/Release artifacts | Debug artifact `11648259445`, Release artifact `11647807945` в соответствующих runs | CI upload, не доказательство установки на телефон; срок хранения artifacts конечен |
| Физический телефон | Ранее владелец подтвердил камеру и распознавание маленького фото | Большое реальное фото, OR→GPT, picker, drawer/IME, diagnostics, VS и live API ещё не приняты вручную |

Ошибки промежуточных коммитов `d0624e3`, `ce55889b`, `cd8aa0d`, `9bc2d66` — история исправленных failures, не действующие блокеры финального кода. Везде сохраняй distinction: exact test assertion → harness → SHA/run → scope of proof.

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
- [instrumentation] **#136:** финальный APK `37994461394` на `613bff2` Successful, включая Android lifecycle: локальный typed attachment replay и failure preflight без потери draft/history, picker source-chat после recreate, реальные bytes большого JPEG через локальный HTTPS mock, Drawer DOWN/MOVE/UP через Activity dispatch. Это не системная инъекция касаний на физическом телефоне или реальный Claude/GPT.
- [pure] **#137:** `ResponseDeadlinePolicyTest` (fake-clock и заблокированный MockWebServer), `BoundedAttachmentCopyTest` (quota/cancel/partial), `GitHubFileContentPolicyTest` (real empty vs bad encoding), `VectorStorePagePolicyTest` (cursor/owner) прошли JVM на `fe0b5b2` (APK `37998337416`, Release `37998337154` Successful); отдельно Robolectric `OpenRouterZipCleanupTest` проверяет сохранение READY при overflow, и Android lifecycle regression прошёл. Тег pure относится к перечисленным JVM tests, не к реальному provider/UI.

| Актуальный контракт APP-04/PHONE | Конкретное доказательство | Ограничение |
| --- | --- | --- |
| Stale snapshot не стирает новый Worker ответ/metadata; Stop/terminal имеет одного победителя | `ChatDatabaseActiveFinalizationTest`: sameLengthStaleUiSnapshotCannotOverwriteCommittedMetadata, terminalClaimPreservesDbRowsAndMetadataAddedAfterStaleSnapshot, concurrentStopAfterTerminalCannotEraseCommittedAssistant; Robolectric unit step `d0624e3` Successful | Это DB race/ownership, не полный набор UI callbacks |
| xhigh/max не понижаются, unresolved auto не уходит в HTTP, Claude verbosity не перекрывает effort | `OpenRouterReasoningPolicyTest`, `OpenRouterChatClientTest.claudeXhighAndMaxSurviveEveryToolRoundWithoutVerbosityOverride`; JVM step `d0624e3` Successful | Wire JSON/tool rounds, не обещание поддержки любым upstream |
| OR history replay в OpenAI использует реальные локальные bytes, typed input без чужих file_id | `OpenAiLocalContextEncoderTest`: realJpegPhotoSurvivesManifestPersistenceAndProviderReplay, mixedLocalContextEncodesAllWithoutForeignIds, missingLocalBytesStopBeforeProducingAnyInput; JVM step `d0624e3` Successful | Encoder, не живой запрос GPT; недоступный файл всё ещё требует понятного отказа |
| OpenAI unavailable/stale history chain не запускает HTTP и не теряет D2/history | `MainActivityLifecycleTest.openAiHistoryWithoutResponseChainNeverSilentlyDropsFileInputs`; instrumentation `613bff2` Successful | Ключ задан, async preflight дождались; это failure path, не доказательство live success replay |
| Валидный большой JPEG (>6 MB) сохраняет wire bytes в Send и следующем текстовом history | `realLargeJpegIsUnchangedAcrossHttpsSendAndFollowingTextHistory`; instrumentation `613bff2` Successful, local HTTPS MockWebServer, decoded bytes сравниваются | Не исходное проблемное фото владельца и не настоящий Claude |
| Picker-result после switch + recreate принадлежит source chat | `externalImagePickerResultAfterChatSwitchAndRecreateBelongsToSourceChat`; instrumentation `613bff2` Successful | Синтетический result; Photo Picker/MiXplorer вручную ещё не проверены |
| Model/effort/advanced в одной composer row ниже header | `compactHeaderKeepsModelPickerAndComposerEffortAccessible`; instrumentation `613bff2` Successful, screen geometry + shared parent/listeners | Пока нет runtime проверки всех click outcomes, IME/малой высоты/large font; не объявлять полную UI-приёмку |
| SSE/HTTP error сохраняет typed status/provider/type/body size; логи редактируют secrets/Base64 | `OpenRouterChatClientTest.streamingProviderErrorPreservesTypedMetadataAndRequestBodySize/httpErrorPreservesStatusProviderAndType`, `AstraDiagnosticsPrivacyTest`; JVM step `d0624e3` Successful | Не live upstream trace и не ручная проверка selection/export/log grouping |
| Refusal/fragmented text сохраняются, rich text не теряет underscores/code/link destinations | ResponseEventReducer/OpenRouterChatClient/RichTextRenderer tests, unit step `d0624e3` Successful | Copy/TXT/UI на конкретном телефоне проверяются отдельно |

| Контракт APP-05 | Тест/проверка на `fe0b5b2` | Не доказано |
| --- | --- | --- |
| F21 · durable deadline | `ResponseDeadlinePolicyTest` fake clock, MockWebServer no-response timeout, expired-before-network (JVM); production Activity/Worker compile | Device reboot, реальная 5h модель/биллинг |
| F22–F23 · bounded copy/cleanup | `BoundedAttachmentCopyTest` oversized/cancel/error/exact limit (JVM), `OpenRouterZipCleanupTest` second ZIP entry >25MiB и unrelated READY сохранён (Robolectric) | Реальные content providers, полный heap или все возможные tempfile paths |
| F24 · Vector Store | `VectorStorePagePolicyTest` page/more/cursor/malformed + production owner predicate (JVM), существующий Android UI gate regression | Late HTTP callbacks с реальным VS dialog, >300 файлов, реальные server-side cancellation semantics |
| F25 · GitHub file decode | `GitHubFileContentPolicyTest` empty/invalid UTF-8/base64/size/encoding=none/truncation (JVM) | Не raw/blob fallback, не живая `get_file` операция для большого бинарного файла |
| F26 · docs gate | `*.md`/`**/*.md` trigger и `GitHubToolSafetyTest` nested docs `required()`; Verify PR success | Не server branch protection и не эксперимент на отдельном nested-Markdown-only PR |

**Пока не подтверждено / не повышать тег автоматически.**

- Drawer open/close: финальный Android lifecycle `37994461394` Successful для window-dispatch DOWN/MOVE/UP; не проверены OS injection и физический свайп на телефоне.
- Source-only/частичные границы: direct OpenAI Stop wiring (#20), adapter proof ActiveResponsePersistence (#37), reasoning callback (#40), все image/ZIP completion ветви (#42). F21 проверен fake-clock и quiet socket, не полным пятичасовым процессным restart или live provider. F22/F23 не гарантируют отсутствие любых heap/cleanup defects.
- Настоящие Claude/OpenAI: проблемное большое фото, живой provider switch/long-running stream, upstream-specific image/effort limits, cache hit/TTL/реальная стоимость, EXIF-интерпретация non-ideal/mirrored фото. Маленькое фото и камера — ограниченные наблюдения владельца.
- Телефон: три click outcomes, gesture/Back/selection/code scroll, IME/large font, настоящие Photo Picker и MiXplorer, previews/reopen, selectable Copy/share/latest logs, установленный build SHA. Читаемая ошибка и отсутствие нового выдуманного лимита важнее «mock accepted».
- GitHub: read подтверждён прежним использованием; реальные write/review/merge/workflow от приложения не испытаны. Branch protection — отдельное серверное ограничение.
- F21–F26 выполнены в проверенном CI, но не вся async surface/heap/cleanup: VS late dialog race проверен pure predicate, ZIP failure — Robolectric, реальный GitHub binary >256 KiB явно unsupported, live VS/provider/физический телефон отсутствуют. Не называть aggregate green подтверждением иных ветвей.

**Как поддерживать этот раздел:** после каждого изменённого контракта запиши утверждение, конкретный test/наблюдение, harness, SHA/run/attempt/result и границу. При новом успешном прогоне обнови также ранее Failed/«ещё ждёт» записи, а не добавляй противоречивую строку сверху. Исторический тег не переносится на изменённый runtime автоматически. Закрытый контракт переоткрывай только при новом доказательстве регрессии.

## АРХИТЕКТУРНЫЕ ПРИНЦИПЫ — стабильные правила

- `ResponseEventReducer` — response semantics; `ResumableResponseStreamClient` — transport + sequence cursor; `ActiveResponsePersistence` — persisted active-response boundary. `ResponseCheckpointPolicy`, `BackgroundResponseUiOwnershipPolicy` и `AttachmentUploadCompletionPolicy` остаются pure decision seams.
- Async callbacks должны сначала захватывать immutable ownership data; UI mutation выполняется через main-thread lifecycle gate.
- `chatId + generation` недостаточны там, где callback меняет конкретный assistant item; для этого нужен immutable `chatId + generation + assistantItemId` snapshot. Tool label — отдельный global Activity state.
- Composer/upload mutable lists и flags сейчас преимущественно UI-thread-owned. Не добавлять synchronized/Concurrent коллекции без доказанного cross-thread access.
- Gate, отбрасывающий UI-мутацию в stopped Activity, требует реконсиляции состояния при возврате (`onStart()`), иначе мутация теряется (пример: #75, `refreshAttachChips()`).
- Не вводить коротких app-side таймаутов генерации, fixed tool rounds или скрытых `max_output_tokens`. Аварийные ограничения: 5h response/recovery (APP-05 persisted deadline на OpenAI SSE), 5M токенов на OpenRouter cycle. Metadata Vector Store имеет отдельный короткий timeout, вложения — явные byte limits; не путать с лимитами модели.
- Persistence extraction должна начинаться с pure snapshot/codec или одной доказанной transactional operation, а не с generic controller framework.
- Compose — следующий UI-слой только после state/controller extraction; не смешивать Compose migration с upload/persistence refactors.

## АКТИВНЫЙ ПЛАН — только незавершённое

Редакция 14: #136 и #137 merged, APP-05 F21–F26 получил exact-code APK/Release/Verify. Physical phone/provider smoke всё ещё не пройден. Этот docs-only #138 уточняет финальный handoff, не меняя scope и runtime. Compose rewrite, финансовый ledger и autoretry не включены.

### 1. Проверка на телефоне и выбор следующего среза — **следующий**

**Первый шаг:** убедиться, что docs-only PR #138 завершился успешным Verify и merged, затем предложить владельцу установить debug APK из [run 37998337416](https://github.com/luoecrt-pixel/OpenAIApiChat/actions/runs/37998337416), artifact `11648259445`. В диагностике установленного APK ожидать именно `BuildConfig.BUILD_GIT_SHA = fe0b5b2597226f5690b502dc462474d07a25292b` (кодовая сборка), **не** squash merge SHA `e3304979`. Если артефакт истёк — не выдумывать ссылку.

| Приоритет | Ручная/следующая проверка | CI-подтверждённая граница |
| --- | --- | --- |
| P1 телефонные вложения | Native Photo Picker и Files/MiXplorer, оригинальное большое JPEG, preview/reopen, историческое OR→OpenAI, сохранность D2 при сбое | Android mock HTTPS bytes/picker ownership на `613bff2`; реальный Claude/OpenAI без разрешения не вызывать |
| P1 UI/diagnostics | Нижние model, ⚡ quick effort и advanced отдельно; drawer gesture vs scroll/Back/IME/large font; Copy/share logs и Build SHA | Activity window dispatch/geometry тесты и JVM redaction, не полноценный phone UX |
| P1 APP-05 long request | persisted 5h deadline, background/resume, Stop/cancel/error без воскрешения stale request | Fake clock/blocked socket, не 5h live upstream |
| P2 attachments/VS | Partial temp cleanup при Cancel/overflow; выбранный Vector Store, поздние dialog callbacks и >100 files, явное incomplete | JVM/Robolectric ownership/parser/ZIP; real VS server и delayed dialog UI ещё без proof |
| P2 GitHub/docs | Пустой файл ≠ ошибка encoding/oversize; docs-only Markdown PR должен иметь Verify | GitHub decoder JVM и workflow trigger, не live GitHub операции или server branch protection |

После получения наблюдений выполнить **архитектурный/продуктовый обзор** (активный план сменил слой, триггер из START HERE). Выбрать ровно одну следующую задачу на основе воспроизводимого defect или ценности Product radar, записать owner/negative scenario/test matrix; не делать новый rewrite на всякий случай. Если телефон/провайдер недоступны — указать blocked/not verified, не выдавать за success. Платные API и destructive GitHub writes требуют отдельного разрешения владельца.

## Репозиторий / branches

Репозиторий `luoecrt-pixel/OpenAIApiChat`; main только через PR. Продолжай actual open branch, не создавай stacked копию старого snapshot. Перед записью сверяй head и blob SHA; поддерживаемый lease при ref update, без blind force-push. Удаление старых веток не нужно для этой задачи. В текущем коннекторе можно читать push runs/jobs/logs и rerun существующий failed job; создание нового dispatch/repository variable недоступно.

## Контекстный режим для агента

Существующая запись — гипотеза до сверки с current source/test/run. Историю ошибок и отменённое UI-ТЗ не выдавай за новый backlog. Источники доказательств не взаимозаменяемы: exact test assertion → выполненный harness → конкретный SHA/attempt; соседний test и общий summary не расширяют claim. Выполненный APP-05 F21–F26 и его ограничения сохранены выше как proof, а не незавершённый backlog.

## Как проверять новую работу

**До первого изменения.**

- Выпиши точные acceptance criteria с владельцем состояния/путём и негативным случаем. Для UI нужны положение и click/gesture outcome, для attachments — реальные декодируемые bytes и full historical payload, для async — matching source/request/generation + D2.
- Environment preflight: рабочие JDK 17, Gradle 8.9, SDK 35/build-tools 35, adb и API34 emulator/KVM для instrumentation. Выполни `java -version`, `gradle --version`, `adb devices` и доступные targeted команды, не ограничивайся наличием executable. В одном старом окружении Java launcher падал с missing libjli.so и Gradle/adb отсутствовали; в новом окружении заново проверь каждую команду. Если local Android невозможен, честно разграничь локальный verifier, remote CI и ручной телефон.
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

Окно последних пяти рабочих PR при планируемом merge docs-only #138: #134–#138. Для #134–#137 статус merged подтверждён, #138 — этот документационный PR; перед следующей работой убедиться, что #138 merged, и прочитать его фактический merge SHA в GitHub. Никакой кодовой Android-сборки для docs-only #138 не требуется, только Verify на финальном head.

- **#134:** отключение main push APK/Release при сохранении branch [ci]/manual; merge `a5a99f3cd1c6dba60cba0235bb9995df25fe4195`, Verify `37972109767` Successful, YAML-only.
- **#135:** docs PHONE plan, merge `c492e74e3aaed8f87752be41e8ad2618d8215afa`, Verify `37980410307` Successful.
- **#136:** APP-04-PHONE final `613bff2155838b5b1988df4b478d3bf601a83882`, APK `37994461394`, Release `37994461425`, Verify `37994465824` Successful; merge `0308fd67b4445d886c966fb5836d9f45db2de4ff`. Manual phone/live upstream отдельно.
- **#137:** APP-05 F21–F26, final code `fe0b5b2597226f5690b502dc462474d07a25292b`, APK `37998337416`, Release `37998337154` Successful; final docs head `a3d3f0d`, Verify `38028778105` Successful; merge `e330497967503ef781767ca49e0f2668a805dda6`. Оставшиеся phone/live/heap границы перечислены в baseline/контракте.
- **#138:** post-merge handoff reconciliation (только `NEXT_AGENT_HANDOFF.md`), вносит подтверждённый merge #137, обновляет history и следующий срез; Verify/merge state надо получить из фактического PR, не придумывать наперёд.

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

Операционная истина: top/baseline — проверенный SHA и результат workflow; контракты — assertion/harness/proof/границы; активный план — только незавершённое; история — последние 5 merged PR; Product radar — идеи без обещания реализации. Не повторяй один scope в каждом разделе и не сохраняй отменённые требования как активные.

README устарел (v15/baseline #42); **по просьбе владельца не редактировать README**, включая косметику. Исторический `docs/audits/2026-10-08-live-and-independent-triage.md` не переопределяет завершённые code scopes #136/#137 и оставшуюся PHONE acceptance. Пересылаемый отчёт независимому аудитору ведётся отдельно от репозитория. Архивы notes/отсутствие wrapper не важнее data-loss/runtime bugs.

Жизненный цикл: plan → implementation → check → merge → history. Контракты обновляются вместе с кодом: actual assertion/harness/SHA/run + что не доказано. Единственный новый пункт не дублировать в radar; завершённые F21–F26 оставить с точным CI proof и границами, не копировать в активный backlog.

## Короткий отчёт пользователю

Достаточно 3–6 строк: что изменено и почему; что реально прошло (SHA/run/результат); что осталось; ссылка на PR/актуальный handoff. Если прогон оставлен running — скажи прямо, PR открыт/merge не выполнялся. Не выдавай компиляцию или 33 passed/1 failed за полностью проверенное приложение.


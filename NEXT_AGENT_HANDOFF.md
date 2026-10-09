# NEXT_AGENT_HANDOFF

**Last updated:** 2026-10-09 23:40 +03:00
**Следующий рабочий срез:** Отправка, сохранность и повседневный интерфейс — APP-04-PHONE: один последующий кодовый PR, три блока SEND/UI/DIAG по проверке владельца после merge #133. Модель и два отдельных управления мышлением только внизу; исправить доставку/перенос фото, реальные жесты, picker и диагностику. APP-05 после этого; расширение финансового учёта отмены отложено.
**Внесённый код и приёмка:** APP-04 #133 уже merged (`8a4d019328c90745df4448bea5c5dc1e1a2d9351`), final-code `73d9e4357838fbd55f6564b11e3806bb3f65bd65`: APK `37967095171`, Release `37967095269`, Verify `37967102030` — Successful. Тесты/CI не закрыли выявленные на телефоне сценарии. CI #134 merged; проверенная база main после docs #135 `c492e74e3aaed8f87752be41e8ad2618d8215afa`, на текущей базе #135 открыт draft PR #136, SQLite v20.
**Коррекция прежнего ТЗ:** требование model row сверху и полной панели из ⚡ было неверно закреплено этим handoff. Его заменяет нижняя группа из трёх отдельных controls: модель → выбор provider/model; ⚡ → быстрый effort; дополнительная кнопка → полные настройки мышления/ответа. Верхнюю model row удалить, место над чатом ей не занимать.

**Что сохранять из #133:** immutable/deep-copied Send snapshot, off-main историческую подготовку вложений, ownership/Stop/draft D2, узкие DB writers, request-bound GitHub approvals, refusal/streaming, identity файлов, model metadata и reasoning transport policy. Реальные 2×2 image fixtures и Send→локальный HTTPS MockWebServer доказали bounded MIME/body paths, но не большие JPEG или ответ настоящего Claude. OpenAI history guard предотвратил молчаливую потерю файлов; теперь нужен совместимый перенос по доступным локальным байтам, а не постоянная блокировка чата. Не удалять guard без такого замещения. Camera #128 подтверждена владельцем.
**Граница этого аудита:** прочитан текущий код и два доступных скриншота; исходные большие фото, точный installed SHA и OpenRouter upstream trace отсутствуют. Kotlin/Android/платные API этим проверяющим агентом не запускались. UI-нарушения, отсутствие thumbnails, blanket provider guard и потеря error metadata установлены по source; точная причина upstream media_type и открытия Drawer ещё требует regression/trace.

**PR #136, повтор APK после 34-test прогона:** ветка `agent/app-04-phone-send-ui-diag`, предыдущий head `56e711188e6aacf6d625bfab0bb5db19ccae397c`. Release `37985487769` — Successful, Verify `37985497430` — Successful; APK `37985488028` — Failed: 34 tests завершены, 2 failures (OpenAI unavailable local history и Drawer close). Эмулятор не падал. В этом исправлении меняются только instrumentation tests и handoff: OpenAI test задаёт isolated API key, ждёт async local preflight и проверяет отсутствие HTTP/потери draft; gesture test вводит полный touchscreen swipe через окно, реальные bounds и ожидание полного закрытия. Assertions не отключены; production guards/код приложения не меняются. Новый полный [ci] запускается на head этого исправления. **Merge здесь не выполнять:** по указанию владельца следующий агент сверяет новый APK/Release/Verify на matching SHA, объединяет #136 после Successful и затем приступает к APP-05. Failed/Skipped/Running не успех. Результаты нового прогона ещё не установлены.

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

**CI.** Автоматические `Build Android APK` и `Build Android Release` по push разрешены только в ветках **кроме main**. В рабочей ветке агента полный прогон идёт по `[ci]` в сообщении head-коммита или `CI_AUTO_RUN=true`; без них job = **Skipped**. Ручной `workflow_dispatch` доступен на любой выбранной ветке, включая main. Точное условие — в `build.yml`/`release.yml`.
- Коннектор не умеет `workflow_dispatch` и repository variables: рычаг агента — `[ci]` в коммите ветки PR. Ставь, когда прогон нужен; перед повтором назови причину прошлого падения строкой из лога. Пользователь может запустить вручную: Actions → Run workflow → ветка.
- Смотри строки `(push)` у Build и Release. `(pull_request)` Skipped — норма; счётчик «4/4» с галочкой может значить «всё пропущено». Skipped, Cancelled, queued, in_progress ≠ green.
- Concurrency group включает имя workflow, ref и событие (иначе Build и Release с одним `[ci]` отменяют друг друга); новый push в ветку отменяет идущий прогон — не пушь, пока он идёт.
- После merge push в main **вообще не запускает** APK/Release благодаря `push.branches-ignore: [main]`, даже если squash commit body содержит старые `[ci]`. Не отключай push в рабочих ветках: коннектор не умеет `workflow_dispatch`, агенты сохраняют запуск по `[ci]` в своей ветке PR. Для явной проверки main пользователь может выбрать main через Actions → Run workflow.
- `Verify handoff` — дешёвый workflow (без Android) на PR, меняющие `NEXT_AGENT_HANDOFF.md` или `tools/**`; docs-коммиты без `[ci]`.
- **Бюджет Actions ограничен:** подготовь весь согласованный пакет и выполни доступные локальные проверки до `[ci]`. План — одна обязательная пара APK/Release на окончательном коде выбранного пакета; промежуточные коммиты без `[ci]`. Handoff подготовь в том же кодовом PR до полного CI. Повтор — только после разобранного падения или изменения кода/workflow. Не расширяй задачу ради новых проверок; required merge-гейт сохраняется.

**Merge-гейт.**
1. Код приложения, его тесты или шаги сборки/проверок workflow: оба `(push)` — Build и Release — Successful на последнем изменяющем их коммите (включая instrumentation); после него допустимы docs-коммиты без `[ci]`, но `Verify handoff` на финальном head должен быть Successful. Docs-only PR: достаточно Successful `Verify handoff`.
   **Конфигурационная правка запуска 2026-10-09 по запросу владельца:** только исключение main из push-trigger двух workflow и обновление этих инструкций; код приложения, jobs/steps и условия `[ci]` не меняются. Для этой правки достаточно проверки YAML, матрицы событий/веток и неизменности jobs/steps плюс Successful Verify handoff; полные Android-сборки ради выключения дублирующего запуска не требуются. Изменения кода или steps этим исключением не покрываются.
2. Описание PR точно перечисляет изменённые файлы, а размер diff соответствует задаче: неожиданно большой diff у большого файла (`MainActivity.kt`) — признак его полной перезаписи.
3. Не мержи, если обязательный run красный, Cancelled или не запускался (Skipped), даже при «Able to merge». Force-push после старта проверок — только с сообщением пользователю и перезапуском проверок.
4. Статус PR — по `merged`/`merged_at`; `merge_commit_sha` у closed PR ничего не доказывает.
5. В отчёте: PR, merge SHA, run ID и результат каждого обязательного run (сверены со страницей GitHub); отдельно — что не проверено.

**Причина фильтра main:** squash merge #133 включил старые `[ci]` в сообщение коммита и повторно запустил APK `37969088883`/Release `37969088862` на `8a4d019`, хотя код уже прошёл `37967095171`/`37967095269` на `73d9e435`. Владелец попросил исключить лишний автозапуск, сохранив работу агентов. Полное отключение push или режим «только вручную» не применять; дешёвый Verify handoff остаётся автоматическим.

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

**OpenRouter/Claude (#47–#57, #76, #78, #80, #89, #90, #91):** отдельный Chat Completions client с синхронным tool loop; с #89 transport-level SSE streaming для одного round; без фиксированного лимита раундов и без app-side read/call таймаутов (#80), только аварийный потолок 5 ч / 5M токенов на ответ (#78); `max_tokens` необязателен; partial output при ошибке; usage/cache telemetry (#76); #90 добавляет отдельный каталог source/model и сохраняет requested/response model telemetry + session-scoped request events; репозиторий и ветки GitHub добавляются в system prompt (#50); `tool_trace` восстанавливается из SQLite после reopen только с #105 (ранние несохранённые traces не восстановить); `reasoning.effort` исторически понижался на границе (#53); в #133 F18 заменено явной transport policy: manual effort не понижается скрыто, auto разрешается до HTTP, совместимость конкретного upstream отдельно проверяется; запись файла в GitHub принимает обычный UTF-8 `content`, base64 оставлен для совместимости (#52); composer не уходит под клавиатуру (#51). GitHub API вызывает только приложение: allow-list репозитория/веток, write/merge/review/workflow только после подтверждения пользователя, audit log. Prompt caching Anthropic: plain system text + только top-level automatic `cache_control`, TTL 1 час (#103, без прежнего конфликтующего системного marker 5m) (поле `cache_key` в настройках — это OpenAI `prompt_cache_key`, к нему не относится). К OpenAI background recovery OpenRouter не подключён (намеренно). #91 добавляет user-facing session telemetry и Drawer поверх уже сохранённых request_events; UI runtime на реальном устройстве отдельно не подтверждён.

**Вложения (#57, #83):** `content://` сразу копируется в private storage; text/image/pdf/zip готовятся локально; история хранит attachment manifest; OpenAI File API path отдельный и не затронут. Модель может создавать локальные текстовые файлы (UTF-8). Картинки уходят без lossy-сжатия и downscale: EXIF-поворот 3/6/8 — только strict lossless (`ajpegtran:0.1.14`) при идеальной MCU-геометрии, иначе исходные байты; старые manifests с PNG-wire обновляются при чтении. Anthropic принимает до 10 MB base64 на изображение и 32 MB на запрос; через OpenRouter лимиты зависят от provider/model.

**APP-04 после #133:** код пакета уже в main, обязательные CI на final-code SHA Successful (см. верх документа). F18-патч интегрирован; не накладывать его повторно и не возвращать silent xhigh/max → high. Телефонная приёмка выявила оставшиеся SEND/UI/DIAG-пробелы; ближайшая работа — APP-04-PHONE ниже. Платные API вызовы автоматически не выполнять.

## Фактический baseline

- **Проверено 2026-10-09:** main `c492e74e3aaed8f87752be41e8ad2618d8215afa` после docs #135; открыт draft PR #136, исходный head `56e711188e6aacf6d625bfab0bb5db19ccae397c`. Последний merged Android-код — #133; #134 менял только push triggers/инструкции, #135 — только handoff. SQLite v20; перед реализацией заново сверить HEAD/open PR.
- **#133:** merge `8a4d019328c90745df4448bea5c5dc1e1a2d9351`, final-code `73d9e4357838fbd55f6564b11e3806bb3f65bd65`; APK `37967095171`, Release `37967095269`, Verify `37967102030` — Successful, APK выполнил Android instrumentation. Последующие docs-коммиты не меняли Android-код. #134 head `883d76ccc07b223b9f8a538d57d2f6e0343e390b`, Verify `37972109767` — Successful. Не выдавать Skipped PR-runs за сборки.
- **Прежние рабочие proof:** #125 final-code `6aa069be89500a86bf4907cc90205e640af628e9`, APK `37908225095`, Release `37908225079`, Verify `37908229012`; #126 `a2d8de607606c7ec4213d73705b39393d0b83cf1`, APK `37911721448`, Release `37911721472`, Verify `37911728484`; #128 `1676c5c2964478a3bb0379b1c01dbe2b04db1c75`, APK `37917519700`, Release `37917519729`, Verify `37917524650` — Successful. Камеру владелец подтвердил; это не доказательство всей доставки фото.
- **Новые PHONE-наблюдения после #133:** маленькое фото распознаётся, большие около 6.3/6.4 MB — нет; следующий текст тоже может получить `messages.2.content.1.image.source.base64.media_type` enum error. Model picker всё ещё сверху, Drawer не открывается свайпом, ⚡ мешает быстрому переключению, OR thumbnail нет, Фото открывает Files, диагностика неудобна. Переход OR → GPT с вложениями блокирует Send. В доступных скриншотах нет отдельного GPT warning и оригинальных больших image bytes.
- **Новые SOURCE-находки:** MainActivity помещает modelBadge в root под header; showQuickEffortPicker открывает полную панель; renderUserAttachments игнорирует OR attachments, composer chip не содержит thumbnail; chooseImage использует ACTION_OPEN_DOCUMENT; handleActivityResult отвергает picker result другого чата; sendMessage содержит blanket OR-attachment history guard. buildOpenRouterMessagesSnapshot повторно включает старые изображения; SSE error handler сохраняет только message, теряя provider/code/raw metadata. Diagnostics спрятана в advanced Settings, AlertDialog текст не selectable; versionName 4.17.0/versionCode 64 не идентифицируют проверенный Git SHA.
- **Старый широкий аудит редакций 8/9:** разобраны production/test sources, SQLite design traces и F01–F26. Это исторический реестр рисков, а не 26 текущих доказанных неисправностей: #133 изменил соответствующий код. Сохранять результаты и проверять существующие regressions при затрагивании путей; не переписывать всё заново по старому source description.
- #101 attachments/wire_path/drafts/fingerprint, #103 Claude caching, #105 persisted metadata, #107 GitHub guards, #109 меню +, #128 camera ownership и #133 не переделывать без регрессии. #112–121 сохранили unknown/reported, ledger и round accounting; их финансовое расширение не ведёт очередь. Серверная branch protection отдельно от client guards.

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
**Уточнение доказательств #59/#60 на APP-04:** эти теги относятся к успешной исторической версии. На PR #133 SHA `c077f1d7` оба `MainActivityLifecycleTest` confirmation-сценария **упали**: в тестовых потоках отсутствовал обязательный owner. Test-contract обновлён, но успешного instrumentation на новой версии кода пока нет; после CI обновить статус и exact SHA.
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

**Редакция плана 10:** ближайший APP-04-PHONE — один PR с тремя связанными блоками ниже. #133 уже merged, его не «заканчивать» и не создавать отдельные PR для каждой кнопки. APP-05 F21–F26 остаётся одним будущим пакетом. Подробный передаваемый исполнителю план ведётся вне GitHub; этот handoff содержит весь обязательный scope. Цена отмены отложена, неизвестная сумма допустима.

### 1. Отправка, сохранность и повседневный интерфейс — **следующий**

**APP-04-PHONE:** исправить ежедневные PHONE-сценарии и необходимые диагностические пробелы одним кодовым PR, без миграции v20, нового ledger, Compose/controller rewrite или новых платных retries. Три блока — внутренние части одного PR. Цель бюджета: одна финальная пара APK/Release; повтор допустим после разобранного падения или изменения кода.

**Первый шаг:** сверить main/open PR/installed build identity; прочитать sendMessage/buildOpenRouterMessagesSnapshot/OpenRouter SSE error handler, renderUserAttachments/refreshAttachChips, chooseImage/chooseAny/handleActivityResult, buildUi/showQuickEffortPicker/showSettings/AstraDiagnostics. Записать падающие regressions с валидным большим JPEG, фактическим переходом провайдера, координатами нижних кнопок и MotionEvent Drawer. Воспроизведение mock/эмулятором не объявлять live provider proof.

| Один PR, три блока | Обязательный результат |
| --- | --- |
| **SEND** — доставка и работа с файлами (F01/F03/F08 + previews/pickers) | Большое/историческое фото проверено по wire/body; OR → GPT в том же чате передаёт доступные локальные вложения совместимым форматом; thumbnail до/после Send/reopen; Фото через Photo Picker, Файлы с совместимым выбранным менеджером; result остаётся у source-chat. |
| **UI** — свободное место и простые действия (F18/F19) | Три разных controls внизу у ввода: модель, быстрый effort, дополнительные reasoning/response settings. Model row сверху отсутствует. Drawer реально открывается вправо и закрывается влево; IME/scroll/Back не сломаны. |
| **DIAG** — объяснимые ошибки и доступные логи | Кнопка диагностики сразу под API keys, readable request groups, selectable/copy/share, открытие на последних событиях; build SHA и sanitized upstream error/attachment descriptors. Логи показывают причину/этап, а не только длинный exception message. |

**SEND — точная приёмка.**
- **Размер ещё не доказанная причина.** Телефон показывает media_type enum error, а не image_too_large; в коде MIME уже определяется по wire magic. Проверить весь outbound messages array, включая старые изображения: новый текст снова отправляет их. Для каждого image нужны source/wire MIME, размеры байтов/Base64, dimensions/hash и messages/content index. Валидные JPEG около 6–7 MB и маленькое около 143 KB проверить после persistence/reopen, в смешанной истории и следующем текстовом запросе. Mock HTTPS body декодируется обратно и совпадает с исходным hash; 4-байтовая «JPEG-сигнатура» не fixture.
- Из SSE error сохранить code/error_type и metadata.provider_name/provider_code/raw (при наличии), request/round ID и стадию; redact credentials, не логировать full body/base64. Передача маленькой fixture в MockWebServer не доказывает большого фото настоящему Claude. По официальным документам прямой Claude допускает 10 MB base64/image, Bedrock/Google — 5 MB; OR routing/лимиты конкретного upstream не установлены. Не вводить универсальный предел 5 MB и не объяснять enum error одним размером без trace.
- Неверный/неподдерживаемый input останавливается до потребления draft/HTTP с понятной причиной и конкретным вложением. Корректный payload, отклонённый upstream, фиксируется как upstream failure. При реальном лимите сохранять оригинал; уменьшенная wire-копия только по явному выбору пользователя, без скрытого lossy/downscale, публичной загрузки или оплачиваемого автоматического retry. Не удалять старые фото из контекста молча и не повторять ту же известную невалидную историю бесконечно.
- **Смена provider:** нынешний запрет sendMessage не удалять без замены. Из локальных manifests/bytes строить typed context целевого API: изображения и поддерживаемые файлы OpenAI/OR, без чужих file_id и stale previous_response_id. Обычная смена OR → GPT с доступным фото не требует нового чата; проверить composer и исторические вложения, обратный переход и OpenAI full-history fallback. Для реально недоступного/unsupported файла — действие/объяснение до очистки draft, без тихого text-only fallback. Исходные metadata/messages и новый draft D2 сохраняются.
- **Preview:** использовать bounded thumbnail loader и attachment identity для OR manifests в composer и user bubble, включая Send/reopen; не применять preview downscale к wire. Отсутствие thumbnail не удаляет вложение; кнопка просмотра доступна.
- **Фото:** AndroidX PickMultipleVisualMedia/ImageOnly, native photo grid с совместимым fallback. ComponentActivity и activity-ktx 1.10.1 уже имеются, смена Activity base/обновление dependencies ради этого не нужны. Сохранить Camera/Photo/Files menu и multiselect.
- **Файлы/MiXplorer:** chooser/preferred compatible file handler через Android intents; не угадывать package. MiXplorer используется, если установленная версия поддерживает запрос, иначе доступный выбор/fallback. Сразу копировать content:// в private storage; для GET_CONTENT нельзя требовать persistable grant. Photo Picker и Files — разные действия.
- **Владелец picker:** source-chat/provider/operation захватывается до внешнего окна и переживает recreation. Возврат после switch сохраняет result в исходном draft, не в текущем; current chat и его D2 не меняются. Cancel убирает только temporary bytes своей операции. Старый early-return «повторите выбор» не является приёмкой.

**UI — точная компоновка вместо прежнего верхнего row/полной панели из ⚡.**
- В composerOptions непосредственно у ввода, над клавиатурой: **model/provider picker**, **⚡ quick effort**, **отдельная маленькая кнопка дополнительных настроек мышления и ответа**. Быстрый effort — только одноразовый выбор уровня с немедленным сохранением/закрытием; full panel открывается третьей кнопкой. Модель убрать из верхнего header/root row и общих Settings, controls не дублировать.
- Дополнительная панель переиспользует уже имеющиеся effort-compatible mode/summary/verbosity/max output/format/schema; keys/prefs/секреты не переименовывать и не сбрасывать. Сохранить #133 ручные xhigh/max без скрытого понижения, явный resolved auto и provider-specific omission. Неподдерживаемое не отправлять; выбор меняет следующий request, не captured active request. Temperature отдельно от reasoning.
- Header: title, chats/new chat и основная gear; Drawer без Settings footer. Открытие вправо должно быть доступно реальным горизонтальным движением в области чата, не требовать попасть в микроскопический край. Закрытие влево и кнопка остаются; вертикальный scroll, selection, горизонтальный code scroll, Android Back и IME не перехватывать без основания. Точная причина телефонного gesture failure пока не доказана.
- Tests проверяют screen bounds controls ниже chat/history и доступность при IME/малой высоте/крупном шрифте; отсутствие model control в header; три разных click outcomes. Для Drawer — реальные pointer/MotionEvent swipe open/close плюс отрицательный vertical-scroll/Back кейс. Старый test наличия кнопок compactHeaderKeepsModelPickerAndComposerEffortAccessible этого не доказывал.
- Сохранить компактную цветную строку, bounded expanded history/Close и request-specific model labels из #133; не вернуть Astra в каждый bubble.

**DIAG — работающий просмотр и доказательства.**
- В общем Settings диагностику поставить сразу под OpenAI/OpenRouter API keys, до GitHub/tools/прочих категорий; не прятать в advanced section.
- Группировать по request: время, model/provider, стадия, исход, короткая причина; ниже читабельные поля и свернутые технические детали. Log text selectable; Copy selected/request/all и share/export берут исходный текст. При открытии показать последнюю запись (scroll в конец) и кнопку «В конец», не перематывать просмотр во время чтения старого или selection.
- Сохранить rotation/bounded tail, читать/форматировать без большого I/O на UI. Старые log lines остаются читаемыми. Секреты/API tokens/полная Base64 не попадают в diagnostics; проверка redaction обязательна.
- Добавить идентификатор Git/build SHA в BuildConfig/diagnostics, получать из CI GITHUB_SHA, local build помечать честно. versionName 4.17.0 сам по себе не подтверждает установленный commit. Поля error/attachment описаны в SEND; persist/log не зависит только от visible/current UI gate. Не создавать новую финансовую DB или общий tracing framework.

**Что не переоткрывать без новой регрессии (#133 / прежние F01–F20).** Snapshot/ownership/draft D2, одна upload/download operation, сериализованный source draft, ZIP Cancel, cache identity, narrow DB claim/append, Stop/terminal winner, recovery reconciliation/text+cursor, Edit/Delete/Repeat, GitHub owner/approval, refusal/OR deltas, model metadata и Copy/TXT/URL/code сохраняются. Проверить существующие contracts при затрагивании путей; исходное source description редакции 9 не доказательство, что дефект ещё есть. Новые регрессии SEND/UI/DIAG дополняют эти proof и меняют именно постоянный provider guard и неверную UI-приёмку.

**Уточнение владельца об Actions после merge (2026-10-09):** речь шла только о повторном автоматическом запуске Android Actions после merge, а не об автоскачивании и не о продолжении ответа. Это устранено merged PR #134: `build.yml` и `release.yml` исключают `main` из push; рабочие ветки сохраняют запуск по `[ci]`, а `Verify handoff` остаётся автоматическим по соответствующим изменениям. Не реализовывать дополнительную автоматику по старой двусмысленной формулировке.

**Merge и короткая телефонная приёмка:** targeted offline/Android regressions готовы до [ci]; финальные APK + Release Successful на последнем кодовом SHA и Verify на final head. Handoff/описание PR прямо перечисляют исправленное/непроверенное. Один итоговый APK: владелец проверяет нижние три кнопки/gesture, Фото/MiXplorer/thumbs и тот же большой image, смену OR → GPT. Без реального upstream trace точную причину enum error не объявлять устранённой по одному mock. Платные API/реальная камера этим аудитором не запускались. APP-05 начинать после этого пакета и короткого владельческого smoke, а не автоматически по зелёному CI.

### 2. Долгие операции, ресурсы и инструменты — следующим общим пакетом

**APP-05:** один следующий пакет F21–F26, после APP-04-PHONE и короткой проверки того же APK владельцем. Не запускать Android CI для одного лишь обзора этих рисков и не смешивать их с ближайшим пакетом. Если измерения покажут независимое опасное изменение схемы/transport, отделить только его по конкретной причине, не дробить строки автоматически.

**Первый шаг:** fake-clock/blocked-stream regression для сквозного deadline, synthetic input для copy/cleanup и delayed Vector Store dialog fixture. Сначала воспроизвести конкретный предел/потерю, затем определить минимальные budgets и ownership; не ждать пять часов в CI и не вводить короткий timeout самого ответа.

| Найденный путь | Доказательство и место | Проверяемый результат |
| --- | --- | --- |
| F21 · P1 на уровне аварийного контракта: Пятчасовой recovery budget не сквозной | SOURCE. Recovery clock начинается заново на каждом Activity/Worker; quiet stream не прерывается по deadline. | Один durable remaining budget; fake clock/blocked stream, без короткого timeout ответа. |
| F22 · P2, OOM/ANR ещё не измерены: Копирование больших файлов и heap | SOURCE. Raw copy без byte/cancel budget, whole-file text/Base64; OOM ещё не измерен. | Synthetic stream/heap measurement → bounded I/O/понятный budget, без silent compression/truncation. |
| F23 · P2: Незавершённые файлы остаются на диске | SOURCE, новое. ZIP/copy/decode/download exceptions оставляют temporary/partial files. | Cleanup только своих operation paths; failure/retry не растят мусор и не удаляют READY. |
| F24 · P2: Vector Store: старый callback, зависание и скрытая ошибка | SOURCE, новое. VS callbacks используют глобальный dialog view; unlimited metadata call, limit=100, delete error скрыт. | Dialog/store owner, finite control-call budget, Cancel/errors, pagination или явный неполный список. |
| F25 · P2: GitHub read может сообщить успех с пустым файлом | SOURCE + API contract, новое. GitHub getFile возвращает ok=true/empty при encoding=none или decode failure. | Bounded raw/blob по разрешённой identity либо явный unsupported, отличать настоящий пустой файл. |
| F26 · P2, CI/config отдельно: Docs gate и серверная защита | SOURCE + GitHub snapshot. Любой Markdown требует Verify в client gate, workflow покрывает только handoff/tools; main не protected. | Согласовать trigger/gate в APP-05; server protection отдельно у владельца/тарифа. |

**Живые проверки:** использовать итоговый APK APP-04 для одного короткого списка: Claude фото/файл; реальная модель pending/error/restored; Repeat; Edit/Delete при запросе; background→return; нижние три controls/Drawer/IME; Copy/Save. Камера уже подтверждена владельцем, остальные результаты фиксировать по отдельности. Настоящие API/cache/long responses/web/GitHub и фактические суммы не доказываются CI. Новые платные вызовы без разрешения не выполнять; текущих сообщений/скриншотов достаточно для offline regressions.

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

### 9. Повседневный интерфейс и доставка вложений — уточнение владельца 2026-10-09

Каноническое ТЗ — APP-04-PHONE выше: модель, быстрый effort и дополнительные настройки — три отдельных нижних controls у ввода; верхней model row нет. Одна основная gear, Drawer без Settings с реальными жестами; OR thumbnails, native Photo Picker, совместимый Files handler и диагностика сразу под API keys с readable/selectable logs, открывающимися в конце. Delivery/provider switch и сохранность выше цены отмены. **Первый шаг:** выполнить три блока одним кодовым PR с body/geometry/gesture/log regressions; это не очередь из отдельных PR.

**Черновик идей — без фильтра, до следующего обзора**
Сюда можно писать сырое: без доказанной ценности и без первого шага — это специально разрешено, чтобы не отсеивать идею раньше, чем она успела оформиться. Максимум 5 строк. На каждом архитектурном/продуктовом обзоре: по каждой старой записи — либо дожать её до формата Product radar (с «Первый шаг:») и перенести выше, либо вычеркнуть одной строкой почему; добавить не больше одной новой записи, обязательно вдохновлённой взглядом наружу — как эту же проблему решает другой multi-chat/Responses API клиент, либо какая уже существующая pure-архитектура (`ResponseEventReducer`, `ActiveResponsePersistence`, `AttachmentUploadCompletionPolicy`) недоиспользуется и что даст её переиспользование иначе.

(пусто — заполнится на следующем обзоре)

### Что сознательно не тянуть сейчас
- MCP и Computer Use — пока нет доказанной продуктовой потребности и это резко увеличивает execution surface.
- Полный Compose rewrite — инфраструктурная стадия, не пользовательская функция.
- «AI сам организует чаты/саммари/теги» — выглядит эффектно, но пока не доказано, что это важнее поиска, обратимости и прозрачности контекста.
- Синхронизация между устройствами — отдельная security/backend задача, не естественное продолжение текущей local-first архитектуры.

## Документация

**Актуальный план:** редакция 10, APP-04-PHONE одним PR SEND/UI/DIAG, затем APP-05 F21–F26 одним будущим пакетом. #133 завершён/merged; прежние планы редакции 9/FIN133 остаются историей. Не выдавать старое ТЗ model row сверху/полная панель из ⚡ за текущий запрос. Отдельный план исполнителю APP-04-PHONE ведётся вне GitHub; старые APP-04-FILE/SEND/LIFE/CONTEXT/MODEL/UI не продолжаются как шесть PR. `docs/audits/2026-10-08-live-and-independent-triage.md` — исторический аудит, его прежняя очередь/компоновка не переопределяет уточнения владельца 2026-10-09. **Пересылаемый отчёт независимому аудитору ведётся отдельно, не в репозитории**, без пользовательских UX-находок. README сейчас устарел (DB v15, baseline #42), не использовать как источник фактов. **По просьбе пользователя не трогать README в обозримом будущем**, включая чистку и переоформление: P0/P1 важнее. Отсутствие .gitignore/wrapper и архив notes — P3, не важнее риска потери данных.

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


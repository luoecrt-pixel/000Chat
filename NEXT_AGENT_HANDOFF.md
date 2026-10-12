# NEXT_AGENT_HANDOFF

**Last updated:** 2026-10-12 10:39 +03:00
**Следующий рабочий срез:** Восстановить GPT: instructions и полный контекст — OA-1 подтверждён HTTP 400 из лога владельца; затем OA-3/chain eligibility. Новые UI-задачи и критерии находятся в активном плане ниже; эта ревизия только документирует, исправления выполнит передаваемый владельцем агент.
**Подтверждённый статус:** Интеграция релиза #141 и полный handoff #142 merged. OpenAI audit [#143](https://github.com/luoecrt-pixel/000Chat/pull/143) **merged 2026-10-12 09:39:38 +03:00** как `61597f4053b44a1686135e9ae77818eea9429f83`, tree `4f35fe316dda2495a9566e89e15ed619bf784625`; это проверенная база текущей docs-only ревизии. Открытых PR на старте нет. Код 4.17.0 (`versionCode=64`, SQLite v22) прежний; ZIP не применять повторно. Final head/Verify/merge текущей ревизии записываются в её PR, без self-referential коммита.
**Идентичность сборки:** последняя code/test редакция — `cceacdd8c392b2a69338f2313c6db137d210f307`; финальный docs head #141 — `4f1e96f5c1327fab97a0fd595e546460f5c0d205`; squash/main — `78b6be313f82782999c95579466116f4e572070f`. Эти три SHA имеют разные роли. Код/build/tests текущего main совпадают с проверенным code SHA; после него изменяются только Markdown-документы. В установленном APK ожидается `BuildConfig.BUILD_GIT_SHA = cceacdd8c392b2a69338f2313c6db137d210f307`.
**CI подтверждён:** [Debug push 38175698456](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698456) и [Release push 38175698458](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698458) **Successful** на code SHA, attempt 1; Debug завершил **39/39 instrumentation tests** и загрузил APK. [Финальный Verify #141 38176574925](https://github.com/luoecrt-pixel/000Chat/actions/runs/38176574925) **Successful** на docs head, attempt 1. [Финальный Verify #142 38210797617](https://github.com/luoecrt-pixel/000Chat/actions/runs/38210797617) **Successful** на `9800c030c84bc900f592e53130c0a82d7f5e8283`, attempt 1; #142 merged. [Финальный Verify #143 38213463219](https://github.com/luoecrt-pixel/000Chat/actions/runs/38213463219) **Successful** на `e63c881696bbdb817948862b6608baf25ee0e25b`, attempt 1; #143 merged. Текущий docs-only PR требует собственного final-head Verify, без Android rebuild.
**Артефакты доступны:** Debug `11716107416`, Release `11714939668`; API подтвердил `expired=false`. Debug ZIP истекает **2026-10-17 00:27:30 +03:00**, Release ZIP — **2026-10-17 00:21:00 +03:00**. Полные ссылки и archive SHA-256 — в baseline. Debug APK содержит блок подписи; Release APK/AAB **unsigned**, release signing config отсутствует.
**Граница релиза:** завершены интеграция кода, CI, merge и создание артефактов. При проверке GitHub Releases список пуст: отдельный опубликованный GitHub Release не создан; публикация в магазине не подтверждена. Production-подпись и канал публикации требуют выбранного владельцем следующего поручения.
**Закрытые дефекты:** локальный request ID сохраняет terminal assistant независимо от HTTP `x-request-id`; типизированный JSON/SSE не превращает null/объекты в текст; сохранены большие TEXT/оригиналы вложений/signed protocol; исправлены file caches, уведомления и rich renderer. Научный HTML выдаётся из памяти через точный локальный HTTPS URL: прежний `loadDataWithBaseURL` блокировался как `data:` main frame. Ошибочный тест HTML-like LaTeX разделён на безопасное оформление, запрет script execution и настоящий unsupported-command fallback; последние проверки прошли.
**Что остаётся:** владелец прислал 10 изображений интерфейса и реальный GPT error log; это новые наблюдения, а не полная ручная приёмка. Исходное большое фото, live-success Claude/OpenAI/OR, реальная 5h сессия, биллинг и системные picker/IME/gesture по-прежнему не приняты полностью; новых действий на телефоне агент не выполнял. Платных API-вызовов и работы с телефоном в этом проходе не было. Stop/CAS/полная история/lossless originals/signed replay/manual effort/fingerprint v1/1h cache/5h+5M сохраняются. README/workflows/security/settings не менять без соответствующей задачи.


**Новый OpenAI аудит:** [полный список восьми гипотез и результаты](docs/audits/2026-10-12-openai-followup.md). OA-1 — **подтверждённый отказ API по логу владельца 2026-10-12 10:14:03.633 МСК**: `Invalid type for 'instructions': expected a string, but got an array instead.`; исходная wire-проба подтверждает причину. Независимый платный запрос агент не повторял; OA-2 — same-request checkpoint откатывается (SQL probe, Android scheduling не воспроизведён); OA-3 — foreground incomplete/failed до reload может пропустить последнюю реплику в outbound chain (source scenario). OA-4 — key/TTL eligibility, OA-5 — 120s pre-ID cleanup живого owner, OA-6 — bare key в error: точные доказательства и ограничения в отчёте. Исправления **pending**, APK #1282 их не содержит. Host pure suite **46 PASS**, actual reconciler local HTTP PASS; это не новый Android/phone/live run.
**Новые поручения владельца:** список сессий только после первого Send; сохранение позиции чтения при фоне/switch; шторка по направлению отпускания и 30% медленного жеста, более плавные переходы; разбор общего оформления Claude/OpenAI по референсам; выбор белой/серой темы. Приоритет — восстановить GPT. Владелец поручил сейчас проверить и заполнить **этот файл**, затем передаст реализацию другому агенту и вернётся для проверки. Приложение, тесты и APK в этом проходе не меняются. Получена первая партия **10 изображений**, ожидается следующая; описание и имена ниже, originals в публичный repo не добавлены.
**Ответ про APK:** сейчас устанавливать [Debug artifact #1282](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698456/artifacts/11716107416), последний Successful build с APK. #1243 job 15:02, #1282 10:48; основная разница — instrumentation stage 9:41 → 6:16. Cache restored в обоих, device tests 34 → 39; точная причина скорости runner не установлена.

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
- SQLite v22: сохранены messages attachments/metadata, request_events, request_accounting и openrouter_request_rounds/rounds_sealed; добавлены поля вложений и очередь answer_notifications с FK cleanup. Metadata и активные данные не стирать broad stale UI snapshot.
- MainActivity остаётся монолитом. Pure seams: ResponseEventReducer, ResponseCheckpointPolicy, BackgroundResponseUiOwnershipPolicy, AttachmentUploadCompletionPolicy; persisted boundary ActiveResponsePersistence. Foreground checkpoint cadence 750 ms.
- Нет общего короткого timeout для самой генерации, неявного output-token cap или fixed tool-round count. APP-05 F21 ввёл persisted 5h response deadline для OpenAI initial/resumed/Worker SSE; Vector Store metadata отдельно использует короткий 12s call timeout. OpenRouter имеет 5h cycle и 5M accumulated usage tokens; fake-clock proof не заменяет live 5h.
- Вложения: content:// копируется в private storage; text/image/pdf/zip manifests + wire bytes. Оригиналы без скрытого lossy/downscale; preview отдельно. EXIF strict lossless по #83 при допустимой геометрии, иначе оригинал. Provider/upstream limits нельзя выводить из одного общего числа.
- OpenRouter сохраняет requested/response model, SSE partial/usage, верхний automatic cache_control 1h без конфликтующего nested 5m marker. Manual effort не понижается молча; auto разрешается до HTTP; Anthropic verbosity опускается по F18. Поддержка конкретного upstream — отдельное доказательство.
- OpenAI background recovery и OpenRouter — разные пути; OpenRouter не подключать к Worker автоматически. Секреты не попадают в DB/diagnostics. Неподтверждённая стоимость — unknown, не бесплатный ноль.

## Фактический baseline

| Срез | Подтверждение | Граница |
| --- | --- | --- |
| Подтверждённый main #143 | `61597f4053b44a1686135e9ae77818eea9429f83`, merged 2026-10-12 09:39:38 +03:00, tree `4f35fe316dda2495a9566e89e15ed619bf784625` | База этой docs-only ревизии поручений; следующий агент повторно сверяет actual main |
| Последний runtime/code SHA | `cceacdd8c392b2a69338f2313c6db137d210f307`; 4.17.0, versionCode 64, SQLite v22 | В APK Build SHA = этот code SHA, а не squash SHA |
| Debug push / attempt 1 | [38175698456](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698456), job `114577504249`: source/mutations, JVM tests, lintDebug, assembleDebug, 39/39 API34 device tests, upload — Successful | Offline mocks/Android emulator, не физический телефон или live provider |
| Release push / attempt 1 | [38175698458](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698458), job `114577504504`: test, lintRelease, assembleRelease, bundleRelease, upload — Successful | Обе JVM unit variants выполнены; production signing не настроен |
| Final docs Verify #141 / attempt 1 | [38176574925](https://github.com/luoecrt-pixel/000Chat/actions/runs/38176574925), pull_request, SHA `4f1e96f5c1327fab97a0fd595e546460f5c0d205` — Successful | Ранний code Verify `38175702106` не подменяет эту финальную проверку |
| Final docs Verify #142 / attempt 1 | [38210797617](https://github.com/luoecrt-pixel/000Chat/actions/runs/38210797617), pull_request, SHA `9800c030c84bc900f592e53130c0a82d7f5e8283` — Successful | #142 merged; текущая docs ревизия проходит отдельный Verify |
| Final docs Verify #143 / attempt 1 | [38213463219](https://github.com/luoecrt-pixel/000Chat/actions/runs/38213463219), pull_request, SHA `e63c881696bbdb817948862b6608baf25ee0e25b` — Successful | Audit merged; исправления OA-1…OA-6 pending |
| Runs на merge/main SHA | При API-проверке runs отсутствовали | Ожидаемо: Android push на main исключён, Verify workflow запускается только по pull_request |
| #136 / #137 историческое доказательство | #136 merge `0308fd67`, code `613bff2`, Debug `37994461394`/Release `37994461425`; #137 merge `e3304979`, code `fe0b5b2`, Debug `37998337416`/Release `37998337154` — Successful | Исторические assertions не расширяют актуальную ручную приёмку |
| Наблюдения владельца | Камера/маленькое фото ранее; теперь 6 app screenshots, 3 visual references, 1 gray palette sample и GPT HTTP400 | Build SHA установки не указан; новая full acceptance/live-success не доказаны, запрет самостоятельного управления телефоном остаётся |

### Доступные артефакты и проверка содержимого

| Артефакт | Ссылка и содержимое | Размер ZIP / срок хранения |
| --- | --- | --- |
| Debug `11716107416` | [Скачать](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698456/artifacts/11716107416); `app-debug.apk` | 7 753 654 bytes; expires `2026-10-16T21:27:30Z` = 17 октября 00:27:30 МСК |
| Release `11714939668` | [Скачать](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698458/artifacts/11714939668); `apk/release/app-release-unsigned.apk` и `bundle/release/app-release.aab` | 12 439 053 bytes; expires `2026-10-16T21:21:00Z` = 17 октября 00:21:00 МСК |

Archive SHA-256: Debug `adc5f47445aeeb468ed2ef5e8662d0f8f29b8d82f1311e59f00f0967a43c0cc9`; Release `2f9a69295eb353f8ee2b84e49fbc028b9df499e954e4ac234f7b849b1f2e089b`. Это хеши ZIP, не исходных APK и не Maven JAR. В завершённом проходе ZIP bytes совпали с API digest; в Debug APK найдены signing block, правильный Build SHA в DEX и packaged KaTeX/Mermaid. В Release APK/AAB не обнаружены v1 certificates/APK signing block, что соответствует Gradle config. Это проверка структуры/идентичности артефакта, не отдельная криптографическая проверка сертификата или установка на телефон. Retention — 5 дней; expired артефакт не выдавать за доступный.

### Локальная среда и разделение доказательств

В этом workspace 2026-10-12 проверен JDK 17.0.20: `java -version` работает с `LD_LIBRARY_PATH=/usr/lib/jvm/java-17-openjdk-amd64/lib:/usr/lib/jvm/java-17-openjdk-amd64/lib/server`. Gradle и adb отсутствуют; Gradle wrapper в репозитории отсутствует. Android compile/JVM/Robolectric/lint/device assertions выполнены в Actions, не в scratch. Новый агент повторяет preflight в своём окружении.

Локально в предыдущем завершённом проходе прошли source/handoff verifiers, 5 implementation mutations и 8 existing mutations с positive controls, 8 Python unittest cases, asset inventory 82 modules/fonts/licenses, host Kotlin генерация production renderer и Node syntax/KaTeX probes. Host `Uri` был compile stub; это не WebView proof. Попытка standalone Chromium закончилась SIGTRAP и не считается браузерной проверкой. Scratch host binaries/ZIP/JAR не являются единственным источником результата: воспроизводимая база — GitHub code/tests/runs и pinned distributions.

Ошибки промежуточных коммитов `d0624e3`, `ce55889b`, `cd8aa0d`, `9bc2d66` — история исправленных failures, не действующие блокеры финального кода. Везде сохраняй distinction: exact test assertion → harness → SHA/run → scope of proof.

## Подтверждённые lifecycle/recovery контракты

Единая карта доказанности: instrumentation — Android/ActivityScenario; robolectric — Android/SQLite без эмулятора; pure — JVM без Android runtime; static — source/verifier без runtime. Тег применяется к конкретному утверждению, а не ко всему PR. Старые записи — исторический proof на указанных SHA, даже когда в цитируемом описании сказано «текущий». Актуальные границы #141 перечислены отдельной таблицей ниже; успешный общий CI не повышает старый source-only тег без соответствующего assertion.

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

### Актуальная карта контрактов релиза #141

Все названные tests находятся в code SHA `cceacdd8c392b2a69338f2313c6db137d210f307`. Android tests прошли в Debug `38175698456` (39/39), JVM/Robolectric suites — в успешных Debug/Release `38175698456`/`38175698458`, attempt 1. Таблица описывает конкретные assertions и отдельно отмечает source-only wiring. Prefix `app/src/main/java/com/example/openaichat/` для production файлов; JVM tests — `app/src/test/java/com/example/openaichat/`, device tests — `app/src/androidTest/java/com/example/openaichat/`.

| Контракт / исходник | Конкретная выполненная проверка | Граница доказательства |
| --- | --- | --- |
| Local request ownership · `AstraResponseModel.kt`, `ChatDatabase.kt`, Send wiring в `MainActivity.kt` | `OpenAiRequestOwnershipPersistenceTest.localRequestIdIsPersistedWhileHttpRequestIdRemainsDiagnostic`: чужой HTTP ID не расходует active claim, правильный assistant сохраняется и переживает reopen (instrumentation); Send builder дополнительно source-guarded | Device test вызывает DB напрямую; это не живой OpenAI Send |
| Failed status и типизированный JSON/SSE · `JsonProtocol.kt`, `AstraResponseModel.kt`, `OpenAiSseStreamReader.kt` | `OpenAiRequestOwnershipPersistenceTest.failedResponseHasEmptyCanonicalTextAndErrorOnlyInMetadata`; `OpenAiAndroidNullStreamTest.nullNumericBooleanAndObjectDeltasCannotLeakIntoAnswerOrReasoning`, `terminalNullIdDoesNotReplaceEarlierResponseIdOrStreamedText` (Robolectric) | Канонический текст и error metadata разделены; provider-specific live frames отдельно |
| Большие TEXT без CursorWindow truncation · `SqliteLargeTextQuery.kt` | `SqliteLargeTextQueryTest.completeUnicodeAndNulTextFitsSmallWindowAndKeepsColumnContract`, `compositeKeysAndNestedTransactionRemainOwnedByCaller`, `invalidProjectionDoesNotLeaveTransactionOpen` (Robolectric) | UTF-8/NUL/column/transaction assertions; итоговый String всё равно занимает heap, cursor закрывается на исходном потоке |
| Миграция v22 и выборочное обновление вложений · `ChatDatabase.kt`, attachment state helpers | `HistoricalAttachmentRepairTest.migration22KeepsSignedProtocolAndOldText`, `selectiveRepairPreservesAnswersAndRejectsStaleMutation`, `cacheOnlyWritePreservesConcurrentTailAndCannotChangeOriginalReferences` (Robolectric) | Не переписывать полную историю ради cache repair; не весь migration/device heap surface |
| Stop и один terminal winner · `ChatDatabase.kt`, `LocalArtifactToolExecutor.kt` | `StopDraftAndArtifactsTest.stopReopenAndStaleUiSaveDoNotResurrectRestoredDraft`, `stopAndCompletedWriterHaveExactlyOneWinner`, `generatedFileSurvivesStopAndDisappearsWithDeletedChat` (Robolectric) | DB/файлы: D2/Stop и CAS; не все Activity/Worker callbacks автоматически |
| Signed Claude replay и корректный terminal · `OpenRouterProtocolCodec.kt`, `OpenRouterChatClient.kt` | `OpenRouterProtocolRegressionTest.completedToolProtocolSurvivesSerializedHistoryAndNextUserTurn`, `changedPrefixResetsOldBlocksButKeepsNewBlocksSignedAfterThatChange`, `reasoningFragmentsWithOmittedTypeKeepSignatureAndOrder`, `lengthWithAndWithoutVisibleTextRemainsIncompleteAndDoesNotExecuteCutCalls` (JVM/MockWebServer) | Fingerprint v1/full signed tail сохраняются; acceptance подписи настоящим Claude не проверен |
| Полные wire bytes без большого Base64 String · `FileBackedDataUrl.kt`, `FileBackedText.kt`, `StreamingJsonRequestBody.kt` | `FileBackedWireRegressionTest.diskWireMatchesCanonicalJsonAndCopiesWithoutMaterializingBase64`, `fullAnalysisUsesOriginalImageAfterCompatibleOpenRouterCopy`; `HistoricalAttachmentRepairTest.fullLargeTextStreamsWithCorrectEscapingLengthAndMutationDetection` (Robolectric) | Канонический JSON/оригинал/escaping; это не обещание нулевого heap для всей истории |
| Upload reuse по account/bytes/name/MIME/purpose · `OpenAiFileUploadCache.kt` | `FileBackedWireRegressionTest.uploadReuseChecksBytesAndAccountAcrossCacheInstances`; `FileCacheAuditRegressionTest.uploadPurposeIsPartOfTheAccountAndByteIdentity`, `clearingDuringUploadDoesNotRepopulateCache`, `historicalDocumentAndImageIdsNeverBypassCurrentAccountCache` (Robolectric) | Исторический чужой file_id не bypass; expired/deleted server ID инвалидируется, автоматический платный resend не подразумевается |
| GitHub UTF-16 disk snapshot + ETag · `GitHubTextPageCache.kt`, `GitHubToolExecutor.kt` | `AuditFollowupRegressionTest.diskPagesSurviveReopenWithoutDownloadingPrefixesAndRejectChangedVersion`, `invalidUtf8LeavesNoPartialOrInstalledSnapshot`; `FileCacheAuditRegressionTest.laterGithubPagesRequireEtagBeforeAnyFetchWithOrWithoutDiskCache`, `diskBudgetIncludesReplacementAndPartialSnapshotsAndSameEtagNeedsNoSecondCopy` (Robolectric/mock fetch) | `get_file` decoder ≤256 KiB и `get_file_range` — разные paths; не live GitHub binary operation |
| Локальные CSV/XLSX/текстовые инструменты · `LocalFileTools.kt`, `LocalTextPageIndex.kt` | `LocalFileToolsRegressionTest.csvProcessesAllRowsAndQuotedNewlines`, `sharedFormulaWithoutCachedValueIsNotSilentlyCountedAsAnEmptyCell`, `malformedCsvAndUnknownDevicePathFailClearly`, `blankHistoricalPathDoesNotSilentlyDropAnUploadedFile` (Robolectric) | Request-scoped file allow-list; большие/сложные реальные office файлы и heap отдельно |
| Сохранение полного source и безопасная file card · `LocalArtifactToolExecutor.kt`, `RichResponsePanel.kt` | `LocalArtifactWriteRegressionTest.completeLargeSourceAndUtf8BoundarySurviveSaveAndReopen`; `RichResponsePanelTest.fileCardSavesOnlyAClosedSafeTextBlockAndNothingAutoExecutes` (Robolectric) | Текст/UTF-8/NUL сохраняются, raw text не выдаётся за настоящий office/binary файл; actions явные |
| Уведомления после winning SQLite commit · `ChatDatabase.kt`, `AnswerNotificationOutbox.kt`, `ResponseNotifications.kt` | `AuditFollowupRegressionTest.notificationQueueIsCommittedWithAnswerAndSurvivesReopenBeforePost`; `AnswerNotificationOutboxTest.interruptionAfterPostCanBeReplayedWithSameRequestAndThenDeduplicates`; `ClaudeReviewRegressionTest.oldSentNotificationsExpireWhilePendingDeliverySurvives` (Robolectric) | At-least-once с одинаковым Android tag/id; не exactly-once внешнего post и не реальный process kill/device permission race |
| Native rich response и incremental parsing · `ResponseRenderSafety.kt`, `RichResponsePanel.kt` | `ResponsePresentationRegressionTest.arbitraryStreamingCutsNeverLoseRawSource`, `previewKeepsAttackInsideEscapedOpaqueScriptFreeFrame`; `ResponseIncrementalPresentationTest.nativeHtmlWhitelistNeverAcceptsAttributesOrExecutableMarkup`; `RichResponsePanelTest.narrowDarkCardKeepsCodeAndTableScrollableWithoutCreatingWebView` (JVM/Robolectric) | Полный raw source/protocol не меняется ради показа; 380dp/UX/Copy на телефоне отдельно |
| Научный isolated renderer · `ScientificRendererDocument.kt`, `ScientificRendererPreview.kt`, `ScientificMathText.kt` | `ScientificRendererDeviceTest.packagedKatexAndMermaidRenderWithoutExposingAnAndroidBridge` (instrumentation); `ScientificRendererRegressionTest.rendererServesItsOwnedDocumentWithoutAllowingDataPagesOrOtherMainFrames` (Robolectric) | Реальные APK engines: math/math-in-text/Mermaid; origin, script rejection, source fallback; не всё форматирование и размеры на телефоне |

- [instrumentation] **#141:** выполнены DB ownership/reopen assertions из `OpenAiRequestOwnershipPersistenceTest` и реальный APK/WebView fixture `ScientificRendererDeviceTest` в Debug `38175698456` на `cceacdd8c392b2a69338f2313c6db137d210f307`, attempt 1. Остальные гарантии #141 имеют указанный в таблице JVM/Robolectric/source harness; этот тег не распространяется на них автоматически.

### Ограничения, которые нельзя превращать в лимиты ответа

- `SqliteLargeTextQuery`: маленькие SQL byte slices, полный UTF-8 текст/NUL собирается без тихого усечения. Это обход CursorWindow, не ограничение размера ответа и не bounded-heap гарантия.
- `get_file` сохраняет строгий decoder ≤256 KiB и preview до 40 000 characters с явным `truncated=true`; для полного текста использовать `get_file_range`. Последний дополнительно поддерживает последовательные страницы: UTF-16 offsets, возвращённый `next_offset`, ETag на следующих страницах, conditional validation при каждом вызове. Client raw download guard — 100 MiB; UTF-16 snapshot может занимать до 200 MiB, общий cache budget — 220 MiB. Превышение/неверный UTF-8/смена ETag дают явную ошибку, старый cache при HTTP failure не подставляется.
- File-backed values сериализовать через `JsonProtocol`/`StreamingJsonRequestBody`; не вызывать `JSONObject.toString()`/`getString()` для внутренних file objects как замену wire writer. Typed protocol и fingerprint должны видеть те же полные bytes.
- Model HTML/SVG preview: JS/network/file/content/storage off, escaped opaque sandbox/CSP. Научный renderer — отдельный WebView с app-owned JS, pinned APK assets, точной owned GET main page и GET subresources; внешние origins, data/file/content, POST/другие main frames запрещены, Android bridge отсутствует.
- KaTeX `0.17.0`, Mermaid `11.14.0`; доверенные JAR SHA — `config/response-renderer-sha256.properties`, происхождение — `RENDERER_DISTRIBUTIONS.md`. При mismatch расследовать bytes/provenance, не переписывать pins ради green CI. Inventory 82 modules проверяет ссылки/шрифты/licenses; запуск module graph доказан только device fixture.
- Preview guards: 16 000 LaTeX characters суммарно, 50 000 Mermaid characters, native HTML для math-in-text ≤400 000 characters; timeout подготовки preview 12 s и ограничение его размера внутри UI. Эти правила оставляют исходник доступным и не режут ответ, tool rounds или signed history.
- Notification queue фиксируется с assistant в SQLite, post и acknowledgement идут отдельно. Pending сохраняется при отсутствии permission; просмотр чата/выключенные уведомления закрывают уведомление явно. Повтор доставки не является повтором model API.

**Пока не подтверждено / не повышать тег автоматически.**

- Renderer coverage: device fixture проверил math/math-text/Mermaid documents в общем preview WebView. Успешное inline widget sizing/toggle в `ScientificMathText`, полный HTML/SVG preview runtime и все Copy/source/reopen UI branches отдельно не подтверждены device assertions; Robolectric parser/settings/failure paths не заменяют эти проверки.
- Drawer open/close: финальный Android lifecycle `37994461394` Successful для window-dispatch DOWN/MOVE/UP; не проверены OS injection и физический свайп на телефоне.
- Source-only/частичные границы: direct OpenAI Stop wiring (#20), adapter proof ActiveResponsePersistence (#37), reasoning callback (#40), все image/ZIP completion ветви (#42). F21 проверен fake-clock и quiet socket, не полным пятичасовым процессным restart или live provider. F22/F23 не гарантируют отсутствие любых heap/cleanup defects.
- Настоящие Claude/OpenAI: проблемное большое фото, живой provider switch/long-running stream, upstream-specific image/effort limits, cache hit/TTL/реальная стоимость, EXIF-интерпретация non-ideal/mirrored фото. Маленькое фото и камера — ограниченные наблюдения владельца.
- Телефон: три click outcomes, gesture/Back/selection/code scroll, IME/large font, настоящие Photo Picker и MiXplorer, previews/reopen, selectable Copy/share/latest logs, установленный build SHA. Читаемая ошибка и отсутствие нового выдуманного лимита важнее «mock accepted».
- GitHub: read подтверждён прежним использованием; реальные write/review/merge/workflow от приложения не испытаны. Branch protection — отдельное серверное ограничение.
- F21–F26 выполнены в проверенном CI, но не вся async surface/heap/cleanup: VS late dialog race проверен pure predicate, ZIP failure — Robolectric, старый `get_file` для binary/oversize явно unsupported; новый `get_file_range` имеет отдельные text/ETag/budget assertions, live VS/provider/физический телефон отсутствуют. Не называть aggregate green подтверждением иных ветвей.

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

**Архитектурный обзор 4.17.0, сверён 2026-10-12:** UI/stream/persistence остаются связаны в MainActivity (~7.5 тыс. строк) и ChatDatabase (~2.2 тыс.). Новые renderer views изолируют JS, сохраняют native source и отбрасывают stale callbacks по экземпляру WebView. Следующий безопасный шаг после ручных наблюдений — одна воспроизводимая lifecycle/persistence граница; широкое controller/Compose переписывание не включать в релиз.

## АКТИВНЫЙ ПЛАН — только незавершённое

Редакция 18: владелец 2026-10-12 около 10:24 МСК поручил **проверить новые наблюдения и записать план**, реализацию он передаст агенту, после неё нужна отдельная проверка. Ранее merged #141–#143/CI не закрывают новые требования. Первый приоритет — реальный GPT blocker OA-1, затем независимые UI slices; OA-2…OA-6 и distribution остаются pending. Исходный [аудит восьми гипотез](docs/audits/2026-10-12-openai-followup.md) описывает состояние до нового owner log; статус OA-1 выше теперь сильнее. Controller/Compose/accounting rewrite автоматически не добавлять.

**Новые доказательства / границы.** В логе владельца: 2026-10-12 10:14:03.633, `request_id=req_cc6e69f9508048e194d6e4afbdb8fcff`, `response_id=null`, `chat_id=chat_1791789192888_1137704985030042`, generation 30, latency 375 ms, HTTP400 invalid_type, param instructions. По наблюдению владельца — любая GPT-модель; агент не выполнял собственную paid/live matrix. `GenerationInstructions.openAi()` даёт JSONArray при system **или включённых по умолчанию presentation instructions**; MainActivity кладёт её в body.instructions. Поэтому пустое пользовательское поле system prompt не устраняет default путь. Это ошибка request builder до создания response, а не доказанный отказ всех моделей/ключа/сети.

**Первая партия изображений — 10/10 прочитаны, JPEG проверены.** Это визуальные наблюдения и примеры желаемого вывода; Build SHA/Android/font scale устройства не указаны. Имена позволяют сопоставить материалы при передаче агенту; оригиналы находятся в сообщении владельца, в repo не загружены.

| Изображения | Что видно / для чего нужны |
| --- | --- |
| `1000269189.jpg`, `1000269190.jpg` | Две горизонтальные позиции одной широкой таблицы; крупные заголовки, серые code chips. Все колонки не помещаются одновременно; это не доказательство удаления данных |
| `1000269191.jpg` | Python и вложенная Markdown-ограда, крупные native кнопки «Копировать»/«Формулы в тексте» |
| `1000269192.jpg` | LaTeX виден исходником, `$45.50`, checklist/blockquote, отдельные preview controls |
| `1000269193.jpg`, `1000269194.jpg` | Mermaid/HTML/SVG показаны кодом с preview, file card и сырые названия инструментов со статусом |
| `1000269200.jpg` | Референс желаемой типографики Markdown/code/table: аккуратная иерархия, компактная copy action, простая таблица |
| `1000269202.jpg` | Референс оформленных формул, видимой диаграммы Mermaid и безопасного HTML/SVG preview |
| `1000269204.jpg` | Референс карточек реального tool call и файла с понятными названием/статусом/action |
| `1000269206.jpg` | Образец серой темы: основной фон по JPEG около `#CED8DA`, шапка `#8199A5`; это ориентир палитры, не готовая полная theme specification |

**Проверено сейчас:** source paths ниже + 12 host JUnit tests (`GenerationInstructionsTest`, `ChatScrollPolicyTest`) PASS; реальные production helper probes подтверждают default JSONArray и item-position-only tail policy. Эти тесты проверяют **прежнее** поведение, не выполнение новых требований. Новые Android/UI/gesture/viewport/theme assertions не написаны/не выполнены; телефона/платного API агент не касался. Поступающую вторую партию добавить к этой матрице, не считать текущий визуальный дизайн окончательно принятым.

### 1. Восстановить GPT: instructions и полный контекст — **следующий**

**Первый шаг:** fixture strict Create schema должен воспроизвести owner HTTP400 на текущем default JSON. Исправить транспорт `instructions` (для этого поля string/omitted), сохранив system/developer/presentation смысл и роли допустимым request transport; не лечить ошибку выключением инструкций/форматирования или молчаливым объединением ролей. Источник: `GenerationInstructions.kt`, MainActivity `presentationInstructionsForRequest` / `commitPreparedOpenAi`; старый `GenerationInstructionsTest.openAiPreservesSystemAndDeveloperAsDistinctRoles` закрепляет массив и требует изменения wire assertion. На ту же request boundary добавить OA-3/eligible chain и OA-4 cases.

**Критерии приёмки:** actual outgoing JSON для default/пустых/system-only/developer-only/system+developer/presentation on/off; first send и previous_response_id; все сохранённые инструкции и typed historical attachments присутствуют. Strict mock отклоняет старый array и принимает исправленный payload, без настоящих credentials. Completed → incomplete/failed/disconnected → следующий Send не теряет последнюю реплику до reload/после reopen. OR request/protocol/effort не меняется. Fresh Debug+Release CI на новом code SHA; live-success потребует отдельного поручения, mock acceptance не выдавать за live. OA-1 остаётся P0 до исправления, новый APK обязателен.

### 2. Новый чат появляется в списке только после первого Send

**Первый шаг:** разделить draft session и опубликованный chat в sidebar. Сейчас `newChat()` сразу зовёт `ensureChatEntry`, `ensureChatStore()` создаёт entry при старте, а `ChatDatabase.listChatSummaries()` читает все строки без eligibility filter. Внутренний ID/DB row допустим для FK/attachment ownership/draft, но не должен автоматически становиться строкой списка сессий.

**Критерии приёмки:** повторные «＋»/создание сессии, ввод черновика, выбор модели/effort и добавление вложения не увеличивают список чатов; текст/originals/draft IDs сохраняются при switch/recreate. Публикация ровно один раз при первом принятом Send, включая attachment-only message; empty/disabled Send и отказ preflight не публикуют чат. Не ждать первого assistant: если request уже отправлен и API вернул 400, отправленная пользовательская сессия остаётся видимой. Existing реальные чаты не удалять; пустые provisional rows не удалять вслепую вместе с originals/active owner. Нужны DB eligibility/reopen и Activity action tests; существующий `newChatKeepsOldDraftAndStartsWithEmptyComposer` проверяет только draft, не новое listing правило.

### 3. Позиция чтения сохраняется при фоне и переключении сессий

**Первый шаг:** per-chat viewport anchor (stable message identity + offset, для длинного bubble учитывая место внутри содержимого) сохранить до switch/rebuild/фона и восстановить после async adapter commit/layout. Сейчас `restoreCurrentChat()` очищает и пересобирает adapter, `stackFromEnd=true`, `switchChat`/`onStart` вызывают restore; собственного per-chat anchor и offset restore нет. `ChatScrollPolicy` судит только по visible item indices: последний многоэкранный assistant считается «внизу» даже при чтении его начала.

**Критерии приёмки:** в длинном чате A читать середину ответа → свернуть/вернуться, A→B→A, rotation/recreate, приход background answer, обновление rich heights/темы — вернуться к тому же тексту, без прыжка к последнему сообщению; B имеет собственную позицию. Scroll following определять по фактическому расстоянию до конца, не только последнему item; фоновая delta не отнимает позицию читающего выше. Явная «Конец»/Send имеет отдельную политику и не является причиной reset при restore. По поручению владельца переход в конец допустим после **полного выключения и нового запуска**; рабочая трактовка — cold launch после завершения задачи, обычный onStop/onDestroy/rotation не означает выключение. Process/task restoration не путать с cold launch; если жизненный цикл не позволяет надёжно отличить их, зафиксировать выбор до объявления acceptance. Проверки: A/B anchors, tall last bubble, saved-state restore и отдельный cold-start case, без изменения fingerprints/full history.

### 4. Шторка: направление отпускания, 30% и плавные переходы

**Первый шаг:** выделить release/settle policy, используя направление и скорость жеста плюс относительный путь. Сейчас `FingerDrawerLayout` на UP выбирает ближайшее положение по `visibleFraction>=0.5`, velocity tracking отсутствует; animation duration 60–180 ms. Existing `FingerDrawerLayoutTest.textGestureFollowsHeldFingerAndSnapsNearestOnRelease` закрепляет старый 50% результат, это не новая приёмка.

**Критерии приёмки:** короткое осмысленное горизонтальное движение и отпускание в направлении открытия/закрытия доводит шторку до цели, даже до 30% пути; micro-jitter ниже touch slop не срабатывает. Медленный drag фиксирует цель после **30% пройденного пути от исходного состояния**: closed→right >=30% открывает, open→left >=30% закрывает; slow <30% возвращает исходное положение. Пока палец удерживается — шторка следует пальцу, плавное завершение после release. Проверить 29/30/31%, короткий flick в обе стороны, reversal/CANCEL/multi-touch и interruption анимации. Сохранить приоритет вертикального чтения, long press/selection, горизонтального scroll таблиц/code и Back/outside tap. Под «плавнее везде» проверить список UI transitions: drawer/menu/sheet/theme/response resize; согласованный easing, без скачка стартовой позиции/блокировки касаний и массового rebind на каждый SSE token. Не включать blindly RecyclerView itemAnimator (сейчас null): streaming/scroll stability отдельно. Full DOWN/MOVE/UP window tests + runtime observation плавности; одни таймеры/geometry не доказывают UX.

### 5. Оформление ответов по референсам, одинаковое для Claude и OpenAI

**Первый шаг:** зафиксировать общие presentation styles и fixtures из изображений 1–9. Причина отличий — renderer приложения: `RichResponsePanel` получает любой assistant независимо от provider; Markwon + native TextView/Button, table columns фиксированы 160 dp, math `ScientificMathText` первоначально показывает исходник и явную кнопку, Mermaid открывается через preview. `RichTextRenderer` не заменяет этот основной assistant path. При одинаковом Markdown/content у OpenAI будет тот же вид; модель не передаёт дизайн приложения Claude. Оригиналы изображений — первая партия; ещё ожидаются, итоговый визуальный scope сверить с дополнением владельца.

**Критерии приёмки:** компактная согласованная типографика заголовков/текста/списков/цитат, аккуратный code card с небольшим copy action вместо больших серых all-caps controls. Таблицы адаптируют ширину колонок под содержимое/экран; все rows/columns доступны, horizontal scroll имеет понятный признак, число колонок/контент не урезается. По scientific референсу нормальные завершённые формулы должны показываться оформленными в читаемом тексте, Mermaid — видимой диаграммой с доступным source; нынешняя manual-preview функция сама по себе это ожидание не закрывает. Закрытые safe blocks, engine availability, fallback/error/незавершённый stream/очень большой source и высота renderer требуют явной policy без ухудшения original bytes. HTML/SVG preview сохраняет sandbox/no untrusted scripts/no network; модельный markup никогда не выполняет tool call. Tool cards использовать реальные structured tool_trace, file card — действительный файл/Save, а не обещанную моделью ссылку. Тесты одинакового fixture через provider metadata OpenAI/OR, narrow screen/font scale, copy bytes, reopen и source safety; новая visual/device acceptance, не только наличие preview кнопки.

Дополнительное наблюдение из изображений 1–2, P2: CSV и HTML reported как text/plain. Source `LocalArtifactToolExecutor.mimeForFilename` действительно относит csv к text/plain, html попадает в fallback. Проверить metadata/Save/share как отдельный маленький fix (text/csv, text/html), не меняя UTF-8 bytes и не выдавая текст за бинарный Office/PDF. Это не причина GPT HTTP400 и не основание менять inbound file protocol.

### 6. Выбор темы: текущая тёмная, белая и серая по образцу

**Первый шаг:** добавить сохраняемый выбор цвета приложения и единые theme colors для root/header/composer/drawer/cards/dialogs/code/table/selected state/link/preview. Сейчас `MainActivity`, `RichResponsePanel`, `ScientificMathText` и styles.xml используют множество literal colors, а Markwon/WebView часть имеет собственные стили; сменить только фон root недостаточно.

**Критерии приёмки:** белая и серая темы доступны через настройку, выбранная сохраняется после restart; текущая тёмная остаётся вариантом. Серая палитра ориентируется на `1000269206.jpg` (фон около #CED8DA, шапка #8199A5), текст/иконки/состояния читаемы; светлая тема не наследует белый текст на белом фоне/тёмные status-nav gaps. Markdown/table/code/math/diagram/native dialogs переключаются согласованно. Смена темы не меняет model/effort/credentials, не начинает новый request и не теряет draft/attachments/reading anchor/live response. Theme/style assertion плюс screen inspection для всех трёх вариантов и font scale; phone pixels из JPEG не считать готовым полным design token набором.

### 7. Monotonic checkpoint и pre-ID lifecycle ownership

**Первый шаг:** воспроизвести OA-2 через настоящий ChatDatabase: same local request sequence 20 → 10, terminal → late in_progress, different request и Stop. Отдельно OA-5: delayed response.created, Stop/Start при started_at старше 120s, затем terminal. Cancel Worker не является атомарным fence для уже выполняющегося callback.

**Критерий завершения:** text/status/cursor не регрессируют вместе, равный sequence имеет явный contract, живой pre-ID call не теряет owner из-за orphan cleanup; сохранить one-shot terminal CAS и общую 5h границу. Нужны Android/Robolectric DB и lifecycle assertions, не только Python SQL probe, затем новая push CI пара.

### 8. Диагностика: bare key в произвольном error

**Первый шаг:** regression OA-6 на synthetic bare key в error/message, включая provider error, сохраняя safe request IDs. Existing redaction тест покрывает sensitive field/Bearer/query/base64, но не этот вариант. Не использовать реальные credentials в fixture.

**Критерий завершения:** arbitrary error не публикует bare API key через file/Log.d/share; existing diagnostic fields сохраняются. Runtime change требует targeted Android/Robolectric check и общей CI пары; реальной утечки в этом аудите не наблюдалось.

### 9. Ручная приёмка установленной новой сборки

**Первый шаг:** после реализации сверить actual main, Successful Debug/Release на новом code SHA, final docs Verify и новый Debug artifact; предоставить владельцу именно исправленный APK и его Build SHA. Старый #1282 остаётся baseline и новых исправлений не содержит. После отдельного поручения на телефон выполнить выбранные владельцем сценарии ниже и записать устройство/Android/build SHA, действия, наблюдаемый результат и воспроизводимый negative case. Без поручения не устанавливать APK, не управлять телефоном и не делать платные API-вызовы.

| Сценарий | Наблюдаемый результат / negative case | Имеющийся proof |
| --- | --- | --- |
| Formula/diagram/source | Отдельная формула, формулы в native тексте, Mermaid; source/preview/reopen; invalid LaTeX оставляет полный source, ввод с script не выполняется | API34 packaged engine + JVM/Robolectric fixture; физические размеры/fonts/scroll ещё не приняты |
| История и вложения | Оригинальное большое JPEG, provider switch/history replay, picker после switch/recreate; недоступный файл не исчезает молча, D2/history сохраняются | HTTPS mock bytes, ownership/repair tests; исходное фото владельца и настоящий Claude/OpenAI отдельно |
| Composer/UI | Модель у ввода, ⚡ quick effort и advanced имеют разные click outcomes; drawer против вертикального scroll, Back/IME/large font, Copy/share logs | Geometry/window dispatch/redaction tests; системные касания и настоящий picker/IME не доказаны |
| Stop/recovery/уведомления | Stop/late completion не восстанавливает старый request и не теряет новый draft; reopen не дублирует assistant; denied notification permission не теряет сохранённый ответ | SQLite CAS/outbox/Activity fixtures; process death/reboot и реальная 5h сессия отдельно |
| File/cache/VS | Полная пагинация и Unicode, cache reset/expired ID без скрытого resend, поздний VS callback не меняет другой диалог | JVM/Robolectric ETag/cache/owner predicates; реальные большие provider файлы, VS dialog races и heap отдельно |

**Критерий завершения:** по выбранным сценариям есть конкретные observation records; ошибки оформлены с исходными данными и owner/negative test. При недоступности телефона записать blocked/not verified, а не success. Наблюдения не переносить на непроверенные upstream/телефоны. Далее выбрать ровно одну следующую задачу с acceptance matrix.

### 10. Production-подпись и публикация — по выбранному владельцем каналу

**Первый шаг:** если владелец поручает distribution, определить целевой канал и нужную signing identity для обновления существующей установки. Сейчас Debug APK подписан, Release APK/AAB unsigned; GitHub Releases пуст, магазинная публикация не подтверждена. Не объявлять CI artifact production release и не создавать/заменять signing identity без нужного поручения. Signing credentials не включать в исходники, логи или handoff.

**Критерий завершения:** concrete release package с согласованной подписью, проверкой package/version/signature и фактической ссылкой выбранного канала. При изменении Gradle/workflow/code выполнить новую CI пару по merge-гейту; оставленные unsigned artifacts этого не подтверждают. Этот пункт не блокирует уже завершённую интеграцию #141.

## Репозиторий / branches

Репозиторий `luoecrt-pixel/000Chat` (переименован из `luoecrt-pixel/OpenAIApiChat`); main только через PR. Продолжай actual open branch, не создавай stacked копию старого snapshot. Перед записью сверяй head и blob SHA; поддерживаемый lease при ref update, без blind force-push. На проверенном старте open PR отсутствовали, release branch `agent/integrate-clean-bundle-preserve-handoff-20261011` уже merged как #141; не продолжать её как незавершённый релиз. Для нового scope создавать отдельную ветку actual main, если не появился действующий PR. Текущая docs ветка — `agent/complete-next-agent-handoff-20261012`. Удаление старых веток не нужно для этой задачи. В текущем коннекторе можно читать push runs/jobs/logs и rerun существующий failed job; создание нового dispatch/repository variable недоступно.

## Контекстный режим для агента

Существующая запись — гипотеза до сверки с current source/test/run. Историю ошибок и отменённое UI-ТЗ не выдавай за новый backlog. Источники доказательств не взаимозаменяемы: exact test assertion → выполненный harness → конкретный SHA/attempt; соседний test и общий summary не расширяют claim. Выполненные APP-05 F21–F26 и интеграция #141 сохранены выше как proof с границами. ZIP от `4111b83a` — исторический partial patch, не полный checkout и не текущий pending scope. Source/debugging helpers из scratch не подменяют отслеживаемые repo tests.

## Как проверять новую работу

Текущий scope — **только `NEXT_AGENT_HANDOFF.md`**: owner log/10 images, targeted source review, 12 host pure JUnit tests и production helper/source probes. Code/test/build/workflow/README/старый audit не меняются; local handoff verifier и Successful Verify на final docs PR head обязательны, Android rebuild не нужен. Прежние 46 tests/HTTP/SQL probes относятся к #143 и своим границам; текущие 12 tests не означают, что новые UI требования реализованы. Следующий агент после runtime/test changes делает targeted Android/Robolectric assertions и новую push Debug+Release пару по merge-гейту.

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
- Implementation source: `python3 tools/verify_implementation.py`
- Existing mutation matrix: `python3 tools/test_verify_v4_17_0_mutations.py`
- Implementation mutation matrix: `python3 tools/test_verify_implementation_mutations.py`
- Offline assets после Gradle prepare: `python3 tools/verify_response_renderer_assets.py`
- Python verifier fixtures: `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools -p 'test_*.py'`
- Targeted JVM: `gradle --no-daemon :app:testDebugUnitTest --tests 'com.example.openaichat.<ChangedTest>'`
- Compile app/test APK: `gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest`
- Targeted Android (подключён emulator): `gradle --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.openaichat.MainActivityLifecycleTest#<changedMethod>`
- Full Debug: `gradle --no-daemon --stacktrace test lintDebug assembleDebug`, затем `gradle --no-daemon --stacktrace connectedDebugAndroidTest`
- Full Release: `gradle --no-daemon --stacktrace test lintRelease assembleRelease bundleRelease`

Провайдерские лимиты и capabilities сверяй с официальным актуальным контрактом при изменении wire builder; offline mocks не доказывают acceptance/биллинг.

## История последних рабочих заходов — последние 5 PR

Окно последних пяти рабочих PR — #140–#144, включая текущую docs-only ревизию поручений. #140–#143 merged подтверждены API; #144 записывает owner HTTP400 и новые UI criteria, runtime/code baseline не меняет. Для текущего документа итоговые head/Verify/merge записываются в описании PR после подтверждения; не делать ещё один коммит только ради записи собственного SHA. Следующий агент сверяет фактический merged/merged_at и actual main в GitHub.

- **#140:** очистка устаревших Android artifacts и retention 5 дней; merged `120ac217c2a9f06a8b0b062b8571800c52682f33`, 2026-10-11. Workflows при релизной интеграции не менялись.
- **#141:** интеграция 111 файлов partial bundle с последующими code/test fixes и актуализацией канонического handoff. Финальный код `cceacdd8c392b2a69338f2313c6db137d210f307`; push Debug `38175698456` и Release `38175698458`, Verify `38175702106` Successful, attempt 1. Артефакты `11716107416`/`11714939668`; final docs head `4f1e96f5c1327fab97a0fd595e546460f5c0d205`, Verify `38176574925` Successful; merged 2026-10-12 00:30:47 +03:00 как `78b6be313f82782999c95579466116f4e572070f`. Physical phone/live/distribution границы открыты выше.

- **#142:** полная docs-only актуализация `NEXT_AGENT_HANDOFF.md` после merge #141: карта 14 source/test/harness contracts, baseline/артефакты/границы/план. Final head `9800c030c84bc900f592e53130c0a82d7f5e8283`, Verify `38210797617` Successful, attempt 1; merged 2026-10-12 09:06:40 +03:00 как `41a02fa8fc83a25c8c5dcf400b4c5c228cefe86d`. Код/тесты/build/workflows не менялись.
- **#143:** docs-only OpenAI audit/план: восемь гипотез, graded OA-1…OA-6, 46 host JUnit и local HTTP/SQL/function probes. Final head `e63c881696bbdb817948862b6608baf25ee0e25b`, Verify `38213463219` Successful, attempt 1; merged 2026-10-12 09:39:38 +03:00 как `61597f4053b44a1686135e9ae77818eea9429f83`. Код/APK прежний, fixes pending.
- **#144:** только handoff: новые поручения владельца, 10 images, HTTP400 теперь подтверждает OA-1; причины listing/viewport/50% drawer/shared renderer/literal theme colors и конкретные acceptance criteria. 12 host pure tests/source probes, не новые Android/UI/live tests. Реализация передаётся владельцем другому агенту; final head/Verify/merge этого docs PR записываются в его описании.

## Product radar — идеи с реальной ценностью

Это не backlog и не обещание реализации. Идея попадает сюда только если её можно проверить маленьким шагом и она заметно улучшает ежедневный сценарий.

**Ревизия после #141 (исторические решения #130 сохранены):** хранение метаданных, GitHub tool safeguards и `+` уже реализованы; новые узкие wiring defects не отменяют этих гарантий. Ближайший слой — целостность истории/вложений/ответов, точный UI и понятные outcomes; отменённая стоимость отложена. Регрессионные тесты доказывают конкретные pure/SQLite/ActivityScenario contracts, но не фактические расходы и провайдерский биллинг. Не затевать полный controller/Compose rewrite. Владелец просил README не трогать.

### 1. Версии ответа + обратимость
Сейчас regenerate/edit — точечные действия над линейным чатом. Следующий уровень — сохранять альтернативы и уметь вернуть чат к состоянию «до этого ответа». Это естественное продолжение fork: пользователь не должен бояться попробовать другой prompt/effort. Первый шаг: pure domain contract для snapshot/branch point + DB test, без UI.

### 2. Context Inspector перед отправкой
Показывать по запросу компактный «что реально уйдёт»: модель, effort, tools, attachments, примерный объём контекста, background mode, выбранные IDs файлов. Это уменьшит загадочность дорогих/долгих запросов и даст объяснимость auto-effort. Первый шаг: immutable preflight snapshot из уже существующего sendMessage().

### 3. Поиск по всей истории
Для длинной локальной истории нужен мгновенный full-text search с переходом прямо к сообщению, а не только список чатов. SQLite уже является source of truth, поэтому Первый шаг: оценить FTS5 на messages(text) и тесты по индексации/удалению/поиску.

### 4. Переиспользование вложений
Контентный upload reuse уже реализован: `OpenAiFileUploadCache` использует bytes SHA/name/MIME/purpose/credential hash, selective invalidation и generation guard; cache/reset/expired-id регрессии прошли в #141. Не переписывать это как отсутствующую функцию. Возможный следующий UX — явное повторное прикрепление локального оригинала и понятное состояние cached/upload-required по выбранному аккаунту. Первый шаг: инвентаризация существующего UI/manifest paths и pure reuse decision, без автоматического upload/resend или смены server semantics.

### 5. Per-chat configuration profile
Сейчас значительная часть настроек живёт глобально в SharedPreferences. Для рабочего чата полезнее помнить его собственный model/reasoning/tools/verbosity profile, чтобы переключение между чатами не меняло незаметно условия эксперимента. Первый шаг: snapshot только settings metadata в DB, без миграции всех UI-настроек сразу.

### 6. «Продолжить / починить ответ» как отдельный UX
У приложения уже есть сильная recovery-модель. Её можно превратить в пользовательское действие: после incomplete/failed/background interruption предлагать понятные варианты «Продолжить», «Повторить», «Открыть checkpoint». Первый шаг: описать состояния response и разрешённые пользовательские действия как pure policy; это лучше, чем ещё один набор if в MainActivity.

### 7. Fork / ветвление чата
MVP: «Ветвить отсюда» на выбранном сообщении создаёт независимый local snapshot до этой точки. Первый шаг: pure DB/domain contract + tests. Обязательно: новый `chatId`, новые local records, не переносить active response / Worker / temporary UI state, и сначала подтвердить актуальную семантику Responses API для продолжения ровно от fork point.

### 8. Настройки генерации, зависящие от источника и модели
**Системный промпт реализован в #98 (v1)**: глобальное поле, отделённое от существующего Developer instructions; текущий OpenAI transport формирует JSONArray system/developer в instructions, **что owner HTTP400 теперь подтверждённо отклоняет** (активный OA-1/P0). Distinct instruction roles/смысл сохранять при исправлении request contract; прежний offline тест массива не является API compatibility proof. OpenRouter получает пользовательскую часть в system text вместе с app контекстом; отдельная live/UI приёмка остаётся открытой. **«Градус» = temperature (sampling/randomness), а не reasoning effort**: это два независимых рычага; `top_p`, `top_k`, reasoning effort/summary, verbosity, tool-choice и structured output тоже зависят от API source + model, универсального набора нет. Сейчас `temperature`/`top_p` не экспонируются; старое silent xhigh/max → high устранено #133, manual/auto policy сохраняется. Совместимость конкретных моделей/upstream не выводить из одного общего списка уровней. OpenRouter model endpoint отдаёт `supported_parameters`, но реальная совместимость также зависит от upstream provider. Первый шаг: описать pure `ModelCapabilities`/`GenerationSettings` contract для обоих request builders, затем regression matrix для unsupported/omitted parameters, invalid ranges и неявной смены effort. Дальнейшие ползунки UI — только после этого; per-chat profiles — отдельная задача.

**Черновик идей — без фильтра, до следующего обзора**
Сюда можно писать сырое: без доказанной ценности и без первого шага — это специально разрешено, чтобы не отсеивать идею раньше, чем она успела оформиться. Максимум 5 строк. На каждом архитектурном/продуктовом обзоре: по каждой старой записи — либо дожать её до формата Product radar (с «Первый шаг:») и перенести выше, либо вычеркнуть одной строкой почему; добавить не больше одной новой записи, обязательно вдохновлённой взглядом наружу — как эту же проблему решает другой multi-chat/Responses API клиент, либо какая уже существующая pure-архитектура (`ResponseEventReducer`, `ActiveResponsePersistence`, `AttachmentUploadCompletionPolicy`) недоиспользуется и что даст её переиспользование иначе.

Неразобранных черновых идей на этой ревизии нет. Существующие восемь кандидатов остаются radar; новые идеи добавлять после конкретных наблюдений по правилам выше, без выдуманного обязательства реализовать их.

### Что сознательно не тянуть сейчас
- MCP и Computer Use — пока нет доказанной продуктовой потребности и это резко увеличивает execution surface.
- Полный Compose rewrite — инфраструктурная стадия, не пользовательская функция.
- «AI сам организует чаты/саммари/теги» — выглядит эффектно, но пока не доказано, что это важнее поиска, обратимости и прозрачности контекста.
- Синхронизация между устройствами — отдельная security/backend задача, не естественное продолжение текущей local-first архитектуры.

## Документация

Операционная истина: top/baseline — проверенный SHA и результат workflow; контракты — assertion/harness/proof/границы; активный план — только незавершённое; история — последние 5 merged PR; Product radar — идеи без обещания реализации. Не повторяй один scope в каждом разделе и не сохраняй отменённые требования как активные.

README устарел (v15/baseline #42); **по просьбе владельца не редактировать README**, включая косметику. Исторический `docs/audits/2026-10-08-live-and-independent-triage.md` и архивные audit/notes не переопределяют завершённые #136/#137/#141, текущие exact CI results и оставшуюся ручную acceptance. `BUNDLE_AGENT_HANDOFF.md`, `AGENT_IMPLEMENTATION_RULES.md` и `RENDERER_DISTRIBUTIONS.md` — дополнительные материалы; актуальный main/handoff имеет приоритет над package snapshot. Пересылаемый отчёт независимому аудитору ведётся отдельно от репозитория. Архивы notes/отсутствие wrapper не важнее data-loss/runtime bugs.

Рабочий этап ограничен последним поручением владельца (сейчас до 40 минут); при незавершённой остановке сохранить краткий фактический checkpoint с SHA/run/remaining, без накопления исторических записок. Не путать этот лимит агента с пятичасовой safety boundary приложения.

Жизненный цикл: plan → implementation → check → merge → history. Контракты обновляются вместе с кодом: actual assertion/harness/SHA/run + что не доказано. Единственный новый пункт не дублировать в radar; завершённые F21–F26 оставить с точным CI proof и границами, не копировать в активный backlog.

## Короткий отчёт пользователю

Достаточно 3–6 строк: что изменено и почему; что реально прошло (SHA/run/результат); что осталось; ссылка на PR/актуальный handoff. Если прогон оставлен running — скажи прямо, PR открыт/merge не выполнялся. Не выдавай компиляцию или 33 passed/1 failed за полностью проверенное приложение.


# Итоговый отчёт автору предложенных исправлений — 19.10.2026

## Состояние и область отчёта

Репозиторий: `luoecrt-pixel/000Chat`, [PR #177](https://github.com/luoecrt-pixel/000Chat/pull/177). Проверенный исходный main: `d51516151d560687b00d5e349fb6bcabfc5c314d`. Финальный runtime/test/build SHA: **`d38e00075f6570431e1587bf08703b966c456fbb`**. После него изменяются только документы. Статус слияния следует проверять по PR: этот документ фиксирует доказательства перед финальным merge gate, а не обещание будущего результата.

Ветка безопасности `fix/request-and-attachment-safety`, включая `c918ed996b31af460c882bb9a5b0a6f845580eb6`, интегрирована в `fix/interaction-telemetry-design`. Полные замены файлов из старых архивов не накатывались поверх более нового WIP. Принимались требования и проверяемые изменения, а не неподтверждённые объяснения причин. Исторические разборы: [PROPOSED_FIX_REVIEW_2026-10-19.md](PROPOSED_FIX_REVIEW_2026-10-19.md), [DEEP_REVIEW_2026-10-19.md](DEEP_REVIEW_2026-10-19.md). Нижние старые разделы этих документов могут описывать уже завершённые промежуточные задачи; актуальный результат ниже.

Оригинальные 1fix/2fix/3fix и их manifest сохранены отдельно, в публичный репозиторий и отчёт не копируются. Текущая финальная проверка опиралась на настоящий код, исторические probe-результаты и свежие исполняемые доказательства; не выдаётся за новый полный прогон исходных предложенных файлов.

## Фактически выполненные финальные проверки

| Проверка | Точный запуск и результат |
|---|---|
| Debug CI | [39251947439](https://github.com/luoecrt-pixel/000Chat/actions/runs/39251947439), push, attempt 1, **success**, SHA `d38e000…`; job `118057642323` |
| Release CI | [39251947508](https://github.com/luoecrt-pixel/000Chat/actions/runs/39251947508), push, attempt 1, **success**, тот же SHA; job `118057642411` |
| Debug JVM/lint/APK | Реальный `gradle --no-daemon --stacktrace test lintDebug assembleDebug`, `BUILD SUCCESSFUL in 6m 11s`, не skipped |
| Release | Реальные unit tests, release lint и сборка release bundles, `BUILD SUCCESSFUL in 4m 47s` |
| API 34 Android, основной XML | **149 всего: 146 PASS, 3 SKIP, 0 failures/errors**. Три skip — host-driven fixture без сценарных аргументов, не три дополнительных теста |
| Отдельный Android snapshot/upload XML | **10/10 PASS**, 0 skips/failures/errors; это отдельный запуск, не 10 новых уникальных тестов сверх списка основного suite |
| Реальная гибель процесса | **5/5 PASS**: `terminal`, `expired_partial`, `stopped_pre_id`, `replaced`, `background_created`; каждый summary фиксирует `live_process_killed=true`, `production_workmanager=passed` |
| Финальный локальный drawer/layout target | **33/33 PASS**, 0 failures/errors/skips; Android test Kotlin compilation PASS. 6 Activity/Window regression + 23 drawer + 4 selection/layout |
| Static/mutation | `verify_handoff.py`, `verify_v4_17_0.py`, `verify_implementation.py` PASS; mutation matrix **18 отрицательных изменений + positive control PASS** |

Job stdout, XML и recovery summary прочитаны, а не только проверены зелёные значки. Для Android ZIP `12186691932` SHA256: `ea137582a4f62b29fe1288f1196a79ac86998fc16786d63f664b360104b2437c`; для process recovery ZIP `12186706985`: `c147dc1cfb4c493666db05d5faf8fec36afbf476929b7cb72d13db71c844875f`. Скачанные ZIP совпали с digest GitHub. Сырые логи и временные download URL не публикуются.

Полные JVM tasks прошли в CI; точное количество всех JVM test cases здесь не заявляется без финального XML. Нельзя превращать прежний подсчёт либо число исходных функций в результат нового запуска. Финальный docs-only head требует собственного успешного `Verify handoff`; Android runtime/test/build tree должен оставаться идентичен `d38e000`.

## Разбор предложений: принять, изменить, отклонить

Все ссылки `file:line` ниже относятся к **runtime SHA d38e000**, базовый путь Kotlin: `app/src/main/java/com/example/openaichat/`; тестов JVM: `app/src/test/java/com/example/openaichat/`.

| Предложение | Итог и доказательство |
|---|---|
| Запечатывать reasoning при первом непустом `signature`/`data` | **Отклонено.** Это может быть первый фрагмент, а не конец блока. Изолированный counterexample с `data=a`→`b`, `signature=s`→`ig` показывал разрушение `ab`/`sig`. Текущая конкатенация opaque delta: `ReasoningDetailAccumulator.kt:48–61`; regression `OpenRouterProtocolRegressionTest.kt:342`. Сам пример — synthetic proof, не захваченный production SSE конкретной ошибки владельца |
| Удалять unsigned/«битые» reasoning blocks перед replay | **Отклонено.** `signature:null` для некоторых documented reasoning text допустим; sanitizer может скрыть binding reset. `OpenRouterProtocolCodec.kt:75–90` сохраняет подходящий protocol tail без нового фильтра. Существующий явный reset при несовместимом model/account/tools/prefix binding остаётся: `:100–115`; его warning не скрыт |
| Точная причина исходного immutable-thinking 400 уже известна | **Не доказано.** Добавлена точная классификация и понятный безопасный совет, без разрушительного переписывания истории и скрытого платного retry: `OpenRouterErrorClassifier.kt:137–150`. Исходный wire trace для конкретного `messages.19` не установлен |
| `age` точно означает возраст шаблона/индексации портала | **Не доказано.** Поле не скрыто. Нужен настоящий provider result с engine/date/URL и сопоставлением дат страницы, а не предположение по трём примерам |
| Любой `web_fetch` открывает только user/search URLs | **Не универсализировано.** Правило конкретного native Anthropic инструмента не доказывает контракт всех OpenRouter engines. Для исходных сайтов реальный provider smoke не выполнялся; причины cache/шума/недоступности не объявлены найденными |
| Шторка не захватывает горизонтальный код/таблицы/selection | **Принято и расширено.** Исключается весь HSV независимо `scrollX`, также WebView/EditText/активное выделение; настоящий child-disallow сохраняется: `FingerDrawerLayout.kt:188–222`, `:290–322` |
| Убрать якобы конфликтующий двойной scroll у code TextView | **Не применено без доказательства.** `RichResponsePanel.kt:468`, `:478`, `:484`, `:510` всё ещё содержит TextView horizontal layout + внешний HSV. Нельзя писать, что это удалено; доказанный drawer conflict решён по ancestry |
| Лёгкое открытие/закрытие и плавный drag | **Принято с собственной state machine.** Порог 20%, fling 280dp/s, touch slop, direction, resize/RTL/animation/мультипалец; `FingerDrawerLayout.kt:35–54`, `:233–270`. Отдельно исправлен native RecyclerView fling claim, см. ниже |
| Blanket `requestChildRectangleOnScreen=false` | **Отклонено.** Узкий `ChatMessageLayoutManager.kt:11–21` предотвращает только touch focus-scroll noneditable selectable TextView; editable/keyboard/явные rectangle/accessibility пути сохранены и имеют отрицательные тесты |
| Более разнообразные темы, рамки и native controls | **Принято.** Семантические поверхности/контуры кода, формул, диаграмм, tools/log; `AppThemePalette.kt:30–50`, `AppThemeViews.kt:19–71`, `:83–113`, `RichResponsePanel.kt:427–449`. Исправлен также реальный native input background, а не только цвет текста |
| Убрать обычный source toggle формул/диаграмм | **Принято с fallback.** При успешном auto preview лишняя кнопка не нужна; исходник/копирование и явный renderer failure/retry не потеряны: `ScientificMathText.kt:74–109`, `RichResponsePanel.kt:443–449` |
| Верхняя модель, progress→accounting, framed log без дублей/зелёного/Close | **Принято.** Раскрытая шапка — общий заголовок, latest только последним в хронологии; ошибки остаются рядом с accounting: `MainActivity.kt:1245–1295`, `RequestEventFormatter.kt:7–94`. Picker пишет generic статус, а не второе имя модели: `MainActivity.kt:1637` |
| Скрыть пустой toolLabel, равная высота model/settings | **Принято.** GONE при blank, обе кнопки 40dp: `MainActivity.kt:952–961`, `:994–1002` |
| Чаты не прыгают от переключения/настроек | **Исправлено по смыслу изменения.** No-op snapshot/switch не меняют modified time; настоящий append/title/status change сохраняет изменение времени. `ChatDatabase.kt:701–769`, `:2548–2570`. Нельзя заявлять, что `clearActiveResponse` вообще никогда не обновляет timestamp |
| Убрать любые уведомления процесса | **Изменено с учётом Android.** Обязательный FGS indicator сохранён silent/minimum/deferred; полноценные alerts только о результате: `ResponseNotifications.kt:24–47`, `BackgroundResponseWorker.kt:78`. Удаление setForeground разрушило бы обещанную долгую фоновую работу |
| Footer 👍/👎/Копировать/Повторить, без одиночного TXT | **Принято.** Footer и long-press не экспортируют отдельное сообщение: `MainActivity.kt:8872–8906`, `:9042`. Long-press user Copy/Edit, assistant Copy/Repeat |
| `sendMessage(true)` с любой старой карточки | **Отклонено.** Реализация проверяет fresh adapter, durable row/request, последний user в UI и SQLite, ownership/draft/attachments; старый ответ не отправляет чужой последний запрос: `MainActivity.kt:8918–8972`. Это строже проверки только отсутствия следующего user |
| Whole-dialog MD/TXT export из шторки | **Принято с переработкой.** Экспортируется выбранный чат, не случайно открытый; history+active читаются согласованно. Сохраняются raw текст/таблицы/формулы/fences, вложения, краткие safe tool statuses; active response отмечен/дедуплицирован. `MainActivity.kt:1380–1443`, `DialogConversationExporter.kt:10–165` |
| Восстановить старые save-state строки ради verifier | **Отклонено.** Bounded/current-chat-priority anchors сохранены. Верификаторы актуализированы для настоящего source-CAS контракта, не ослаблены ради ложного green старого snapshot |

## Дополнительные исправления безопасности

1. **Нет скрытого платного повторного inference POST OpenRouter.** `OpenRouterChatClient.kt:223` передаёт `StreamingJsonRequestBody(body, oneShot = true)`. Mock HTTP проверяет 408, 503, disconnect и 307/308; explicit tool continuation остаётся намеренным отдельным запросом (`OpenRouterPaidPostSafetyTest.kt:45–80`). Это не доказательство реального биллинга провайдера.
2. **Промежуточный OpenRouter ответ сохраняется до terminal.** Monotonic local sequence + request/provider/status/accounting ownership CAS: `ChatDatabase.kt:2370–2392`, `OpenRouterResponseRuntime.kt:209`; `OpenRouterRuntimeCheckpointTest` проверяет SQLite и соседний OpenAI checkpoint.
3. **Исторические вложения защищены от позднего завершения.** Захвачены chat/generation/message/source identity, commit выполняется source-CAS; credential захватывается до queue и сверяется перед HTTP. После Edit/Delete/Reorder/switch/recreate старый callback не подменяет другое сообщение, original/draft сохранены. `MainActivity.kt:7734–7778`, `ChatDatabase.kt:2149`, `:2183`, `:2207`; `HistoricalAttachmentDownloadTest` — 18 целевых сценариев. Полная привязка всех legacy file IDs к первоначальному аккаунту этим не доказана.

## P2: временные копии экспортов и память

Замечание о plaintext-снимках после гибели процесса принято и исправлено **в том же PR**. `ConversationExportSnapshots.kt` обслуживает только собственные UUID `dialog/message_….md/.txt` в private export directory. Очистка запускается на старте и перед экспортом: старше 24 часов, максимум 256 просмотренных entries и 32 удаления за проход. Pending picker восстанавливается до cleanup; writer/picker/copy pinned. Внешние/неизвестные пути, symlink и NUL не становятся кандидатами. Cancel/success/error освобождают snapshot. Cleanup best-effort: это не обещание мгновенной очистки всего диска или строгого общего размера каталога.

`DialogConversationExporter.writeTo(Appendable, …)` и buffered UTF-8 writer исключают вторую полную выходную строку. Исходный текст пишется кусками до 8Ki UTF-16 units с сохранением surrogate pairs; large Unicode digest и IOException проверены. **История JSON и active/deep copies пока читаются целиком в память**, поэтому формулировка «экспорт полностью bounded-memory» неверна.

Тесты: `ConversationExportSnapshotsTest` (fresh process registry/retention/pins/unknown paths), `DialogConversationExporterTest` (12 сценариев), `MainActivityConversationExportTest` (3 Activity/SAF сценария). Настоящий Android process kill именно во время export picker не исполнялся. Пять host-driven process-kill проверок выше относятся к **восстановлению генерации**, это другой контракт.

Экспортные metadata берутся по allowlist; raw tool JSON arguments/output, system/developer prompts, reasoning/encrypted content, account IDs и служебные пути не сериализуются. Пользовательский видимый текст сохраняется как есть; если сам пользователь написал секрет в диалоге, экспорт по его явной команде сохранит и этот текст.

## Почему старый green не поймал шторку

Предыдущий Android green не доказывал, что следующий horizontal DOWN попал в RecyclerView **во время SETTLING**. Исходный тест делал vertical gesture, но не проверял это исходное состояние; JVM helper дополнительно продвигал время на 400ms. Из-за тайминга тот же native переход мог не возникнуть.

В диагностическом commit `0e00529` acceptance не ослаблялся. Debug [39246908349](https://github.com/luoecrt-pixel/000Chat/actions/runs/39246908349) воспроизвёл отказ. Content-safe trace показал стабильные размеры, отсутствие IME/выделения/layout race: vertical UP → RecyclerView SETTLING, следующий DOWN → native disallow parent intercept, horizontal MOVE остаётся без шторки. Это не было объявлено случайной инфраструктурой.

Новый Activity/Window тест использует настоящий публичный `RecyclerView.fling`, явно проверяет SETTLING и воспроизводит **1/1 RED на старом production**. В `d38e000` исправлен конкретный механизм:

- Кандидат допускается только при закрытой шторке и после прежних selection/HSV/WebView/EditText exclusions.
- Native fling claim на DOWN учитывается отдельно, сохраняя consume-to-stop: тап по движущемуся списку не активирует ссылку под ним.
- После DOWN очищается кеш DISALLOW у непосредственного parent RV, чтобы последующий **настоящий** child-disallow дошёл до шторки; он отменяет кандидата.
- Подпороговый jitter буферизуется до определения направления; ясный horizontal MOVE использует обычную capture policy, vertical/longpress/selection продолжают защищаться. CANCEL/resize/multipointer/команды очищают pending state.

Шесть Activity/Window regression tests проверяют default Window path, actual fling→horizontal, работающую контрольную ссылку + отсутствие её активации fling-тапом, vertical continuation, populated 50-row + jitter без скачка offset, новый child-disallow после DOWN. Локально 33/33; финальный настоящий Android trace подтвердил открытие `fraction=1.0` и закрытие `fraction=0.0`. Таймауты/acceptance не ослаблялись ради green.

В ходе разработки промежуточные отрицательные тесты также нашли кеширование повторного child-disallow и неверный жёсткий размер Robolectric fixture. Исправлены причина в production и размер fixture по настоящему displayMetrics; resize guard сохранён. Промежуточные failed/cancelled сборки не посчитаны успешными.

## Что добавлено в правила тестирования

Пять коротких правил находятся в **NEXT_AGENT_HANDOFF.md**, раздел «Выбор проверок»: минимальные risk cases; RED→GREEN с явным исходным состоянием; самый простой harness, воспроизводящий механизм; небольшие повторно используемые fixtures без отдельного framework; targeted сначала, затем обязательный CI и диагностика без слепого rerun. Постоянные CI требования сведены в один раздел. Текущая очередь/план из правил удалены по просьбе владельца; план остаётся в AGENT_TASKS.md.

Новые тесты должны покрывать конкретный переход и опасный соседний эффект. Здесь это не «ещё десять свайпов с разной скоростью», а **SETTLING→DOWN→horizontal**, затем отрицательные link tap / vertical / child claim. Это повышает полноту без перебора всех сочетаний и дублирования тестовой инфраструктуры.

## Оставшиеся границы

- Причина конкретного signed-thinking 400 и конкретных `age`/URL/cache/noise наблюдений требует настоящего upstream evidence; они не объявлены исправленными. Signed replay не подвергнут blanket sanitizer; штатный явный несовместимый binding reset сохранён.
- Legacy historical server file ID → original account binding и задержка внутри inner async preview остаются известными границами. Download tests используют `openAfter=false`; собственные ownership guards preview проверены кодом, но отдельная такая гонка не исполнялась.
- Физический телефон: плавность пальцем, selection handles, TalkBack, крупный font scale и узкий экран ещё не приняты вручную. Эмулятор не заменяет эту приёмку.
- Live provider/API/cache pricing/billing не проверялись и не оплачивались. Mock/CI не доказывают доступность исходных новостных сайтов.
- Обязательный тихий FGS indicator оставлен сознательно. Export JSON memory и best-effort cleanup описаны выше.

Независимый финальный обзор проверил весь PR соразмерно затронутым ownership/HTTP/SQLite/UI/export/renderer/notification контрактам и не нашёл подтверждённого блокирующего дефекта. Зелёные проверки дают основание для решения о merge этого scope, но не для заявления «ошибок точно нет». Следующий отдельный полный аудит из плана не запускается и не считается выполненным автоматически.

## Передача кода

Канонический код: [полный PR diff](https://github.com/luoecrt-pixel/000Chat/pull/177/files) и [runtime commit d38e000](https://github.com/luoecrt-pixel/000Chat/commit/d38e00075f6570431e1587bf08703b966c456fbb). Для пересылки владельцу подготовлен отдельный пакет: этот отчёт, точный `implementation.patch` main d515161→runtime d38e000 по `app/` и `tools/`, меньший `follow-up.patch` после прежнего green `1dac…`, исходники новых exporter/snapshot/drawer классов, manifest с размерами/SHA256. Это материал для review: не применять поверх уже интегрированной ветки и не заменять целиком более новый MainActivity.

Финальное решение и точный squash SHA фиксируются в PR и передаваемом `MERGE_RECEIPT.md` после actual merge. Содержимое runtime patch остаётся привязано к указанным base/code SHA.

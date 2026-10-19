# Проверка предложенных решений — 19.10.2026

Ограниченная проверка по поручению владельца, начало 00:54:29 UTC (03:54:29 МСК). Это продолжение сохранённой работы, не готовый релиз и не полный повтор контрольного аудита. До изменений проверены GitHub main `d51516151d560687b00d5e349fb6bcabfc5c314d` и отсутствие открытых PR. Сверены AGENTS/NEXT_AGENT_HANDOFF/AGENT_TASKS/SESSION_CHECKPOINT обеих WIP-веток. Main не изменялся.

Входные файлы сохранены byte-for-byte в `/workspace/shared/000Chat-review-2026-10-19`, сверены SHA-256:

| Файл | Bytes | SHA-256 |
| --- | ---: | --- |
| 1-1fix.md | 159355 | eca58296897733c13f57f0ff69d460b726bbbbdb31416218771b929fbe330425 |
| 2-2fix.md | 168579 | e43c31bc2b3938c7892a8d8cb93e496e0411a5f3cbd8fd10cc2584d01b2e8d9d |
| 3-3fix.md | 80761 | 47ff0f81c7088320863e2ae30677846070cec1066cd7e2f3d30478bedde2d273 |

Два независимых узких агента с `reasoning_effort=ultra` выполнили source review протокольных и UI предложений. Они не запускали сборки и не изменяли production. Root выполнил указанные ниже тесты; чтение теста отдельно не считается исполнением.

## Решения по присланному коду

**Full-file replacements не применять.** Они содержат полезные направления, но также неподтверждённые предположения и откаты текущих исправлений.

| Вердикт | Предложение и точные места | Основание / что делать |
| --- | --- | --- |
| Отклонить | Sealed reasoning: `1-1fix.md:558–569,615–634` | Получение первого фрагмента signature не доказывает завершение блока. Реальный isolated JVM probe присланного класса разрушает последовательность `data=a,signature=s` → тот же index `data=b,signature=ig`: 3 блока вместо 2 и потеря `ab/sig`. Текущий `OpenRouterProtocolRegressionTest.kt:342–348` на действующем accumulator проходит. Сохранять opaque fragments/order/indices; отдельные блоки различать только по доказанному wire contract. Это не устанавливает причину исходного provider400. |
| Отклонить | Auto-sanitize: `1-1fix.md:667–705,725,780,808` | Удаляет unsigned/unknown blocks и переиндексирует replay без достаточных оснований. Удаление происходит до checkBinding, поэтому последующий warning может вообще не сработать (`OpenRouterChatClient.kt:111–114`). Второй isolated JVM probe подтвердил изменение входной истории. Отсутствие signature универсально не доказывает повреждение для всех моделей OpenRouter. Не «лечить» текущую сессию тихой потерей wire source. |
| Отклонить | Удалить `setForeground`: `1-1fix.md:1423` и запуск OR service | Это меняет долгий Android background contract. Текущий Worker имеет до 5h recovery; обычный Worker не гарантирует то же выполнение. Сохранять тихий обязательный индикатор; result alerts отдельно. Устройство/точное время остановки без FGS не испытывались. |
| Отклонить | Старые saved-state/verifier строки: `2-2fix.md:56`, `3-3fix.md:48–58` | Actual `tools/verify_implementation.py:172–175` требует `encodeAnchorsJson(map,currentChatId())` и прямо запрещает отдельный unbounded `ArrayList(userScrollLockedChats)`. Текущий bounded state не возвращать к старому снимку ради мнимой совместимости. |
| Отклонить замену | Drawer: `1-1fix.md:461–466,497–499` | Возвращает velocity-based CANCEL/multitouch settlement и теряет восстановление interrupted animation при tap. Текущий WIP `FingerDrawerLayout.kt:178–193,241–249` уже содержит отдельные cancel/interruption правила; не откатывать. |
| Отклонить замену | Renderer: `1-1fix.md:1932–1952,2886–2933` | Теряет normal reattach/retryRequired, возвращает лишние SVG Source/Preview tabs. Текущие `ScientificMathText.kt:61–83`, `RichResponsePanel.kt:575–628` удерживают diagnosed source до explicit retry и восстанавливают обычный preview после detach. Sandbox не объединять с untrusted HTML renderer. |
| Исправить | Web context: `3-3fix.md:799–802` | Не утверждать, что age точно означает возраст индексации шаблона: контракт/реальные данные этого не доказывают. Проверять даты страниц. URL-policy формулировать как проверяемое поведение конкретного инструмента, не как установленную причину всех отказов. Использовать captured search/fetch flags, не перечитывать prefs внутри подготовки и не требовать отключённый search. Шум не устранять потерей originals. |
| Исправить | Whole-dialog export: `3-3fix.md:731–745` | Сохраняет raw visible text, но только роли/text и только TXT. Не выполняет #32 по attachments, tool statuses/results, OpenAI typed tool summaries и MD. Отдельно определить active partial semantics. Сохранять отсутствие hidden prompts/reasoning/secrets. |
| Исправить | «Повторить» каждой карточки: `3-3fix.md:1680–1682` | `sendMessage(true)` берёт последнего user текущего чата (`MainActivity.kt:2787–2789`), а не запрос исторической карточки. Нужен request-aware контракт либо честное обозначение повторения последнего запроса; не добавлять неоднозначную платную кнопку. |
| Исправить | Live log глобальная строка: `3-3fix.md:532–538,933–942` | Нужен captured chat/request/model owner, а не общий mutable текст и текущие model settings. Существующий log уже chronological и не дублирует latest при раскрытии; изменения переносить точечно. Runtime multi-chat сценарий этой альтернативы не запускался. |
| Отклонить откат | Dialogs: `3-3fix.md:668`, `1-1fix.md:2231` | Возврат к plain AlertDialog.Builder теряет theme chrome, уже покрытый `AppThemeViews.dialog` в WIP. Реальная визуальная приёмка всех тем ещё открыта. |
| Принять направление | Footer/long-press, пустой toolLabel, равные controls, semantic surfaces | Соответствует последней воле владельца; сохранить raw copy. Текущий WIP footer TXT ещё требует замены, whole-dialog export не считать готовым. Новые требования внесены в #33/#32 актуального плана. |
| Проверить точечно | Идемпотентный clear: `2-2fix.md:2551–2554` | Guard неактивного слота выглядит полезным; тестировать unchanged timestamp, stale owner и настоящую очистку. Остальную БД целиком не заменять — это теряет новые attachment/checkpoint seams. |
| Проверить точечно | Двойной horizontal scrolling: `1-1fix.md:2768–2810` | В WIP `RichResponsePanel.kt:468,484` TextView и внешний HorizontalScrollView действительно оба настроены на horizontal scrolling. Проверить устранение внутреннего owner; blanket `requestChildRectangleOnScreen=false` не принимать без editable/keyboard/accessibility/explicit-scroll negative tests. Сам скачок на телефоне ещё не доказан. |

## Реально исполненные проверки

Safety code SHA: `3f82418d0021526e763e6b069728ea74a3d3a51a`, на основе WIP `10620f6`. Единственное новое production-изменение этой проверки — `StreamingJsonRequestBody(body, oneShot=true)` в OpenRouterChatClient:221–223. Каждое явное tool continuation создаёт собственный body; hidden OkHttp follow-up запрещён. Существующие raw signed history и lifecycle seams не изменялись.

Одна последовательная Debug Gradle сборка, JDK17/Gradle8.9/SDK35, `--no-daemon --max-workers=2`, реальные JVM/MockWebServer/Robolectric:

| Test class | Tests | Failures | Skips | Что доказано |
| --- | ---: | ---: | ---: | --- |
| OpenRouterPaidPostSafetyTest | 4 | 0 | 0 | 408/503 Retry-After:0 и ambiguous disconnect не создают второй inference POST; explicit successful tool continuation выполняется и учитывает оба раунда. Предыдущий baseline-red 2/4 сохранён в checkpoint. |
| OpenRouterRuntimeCheckpointTest | 4 | 0 | 0 | Delayed SSE→SQLite→MainActivity prefixes до terminal; replacement; Stop/archive; strict cursor/terminal/OpenAI protection. |
| OpenRouterErrorClassifierTest | 5 | 0 | 0 | Safe classifier guidance и HTTP/error envelope сценарии. Не доказывает причину исходного provider rejection. |
| HistoricalAttachmentDownloadTest | 11 | 1 | 0 | 10 сценариев прошли. `completionAfterEditOrDeleteCannotRestoreRemovedMessage` падает FileNotFoundException на keep-original.txt. Нельзя считать attachment пакет принятым; разобраться с ownership оригинала при Edit/Delete и корректностью fixture (само удаление последнего владельца может законно удалять файл). |

Итого safety 24 tests, 23 PASS/1 failure; production/test Debug Kotlin compile PASS. Полный Debug/Release и Android instrumentation не запускались.

**Отдельный isolated probe присланных классов:** из точного первого файла извлечены ReasoningDetailAccumulator и OpenRouterProtocolCodec, переименованы только имена классов и добавлены через внешний test-only source dir/Gradle init script. Production эти реализации не заменял. `ProposedProtocolProbeTest`: 2 tests, 2 ожидаемых failures (3 вместо2 blocks; wire-history mutation). В той же сборке существующий `OpenRouterProtocolRegressionTest.reasoningFragmentsWithOmittedTypeKeepSignatureAndOrder`: 1 PASS. Это воспроизводимые контрпримеры предложенному коду, не live Anthropic proof. Исходники probe/скрипт сохранены локально в `/workspace/shared/000Chat-review-2026-10-19/probe`; synthetic XML остаются локальными.

**Статические проверки:** UI WIP `f9a2bb6`: verify_handoff, verify_v4_17_0, verify_implementation PASS. Safety: verify_handoff и verify_implementation PASS; verify_v4_17_0 FAIL на устаревшем lexical требовании `updateMessageFileLocalState` (удалено в identity-aware attachment fix). Это отдельный незакрытый verifier gap safety-пакета; не возвращать unsafe mutation helper ради зелёного токена. Mutation suite после адаптации verifier ещё обязателен. UI mutation suites также реально прошли: implementation 7 negative mutations + positive control; v4 verifier 10 negative mutations + positive control. git diff --check PASS.

**UI targeted run:** первая попытка скомпилировала production, затем test compile упал на недоступном Notification.foregroundServiceBehavior в SessionPresentationTest:162. Это ошибка тестового harness; исправлена в `cd9326e` на проверки публичных sound/vibrate/channel fields (дефер не выдаётся за runtime-проверенный). Повторно запущены FingerDrawerLayoutTest, ChatMessageLayoutManagerTest, ChatDatabaseChatOrderingTest, MainActivityChatOrderingTest, SessionPresentationTest. Итог будет дописан перед паузой; пока это не доказательство PASS.

## Точка дальнейшей работы

Завершить диагностику единственного attachment failure и safety verifier; после этого соответствующий regression/mutation/full Debug+Release+Android CI. Учитывать все новые требования #33/#32; не переносить full-file replacements. Сохранённые ветки всё ещё отдельны: UI пока не содержит safety. Нужен аккуратный merge/rebase MainActivity/DB позже, без потери обоих наборов изменений.

Live provider, billing, физический телефон, полная визуальная приёмка и Release CI нового кода не проверены. Причина исходной ошибки immutable-thinking, конкретные даты/age/cache/запреты сайтов остаются непроверенными. Main/merge/signing/release/device не затронуты. После короткой проверки — пауза до следующего поручения.

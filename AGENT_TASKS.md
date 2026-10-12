# AGENT_TASKS — задачи и планы 000Chat

Единый рабочий план на основе требований владельца и присланного им `agent_tasks.md`. Критерии приёмки дополнены из старого handoff; это **не выполненные исправления**. Правила в [NEXT_AGENT_HANDOFF.md](NEXT_AGENT_HANDOFF.md), история решений в [PROJECT_HISTORY.md](PROJECT_HISTORY.md).

## Приоритеты и исходные данные

- **P0 / следующий:** восстановить OpenAI GPT. Владелец получил `HTTP 400 Invalid type for 'instructions': expected a string, but got an array instead`. Не выключать инструкции, не терять разделение system/developer, полную историю и вложения.
- Остальные пункты остаются открытыми до отдельных проверок. Не считать соседний зелёный CI доказательством нового UI/Android поведения. Телефон, платные API и production-подпись — только с разрешения владельца.
- Исходные пользовательские изображения **не находятся в репозитории**. Имена и наблюдения ниже — текстовая карта референсов. Для точного визуального сравнения нужны оригиналы.

### Референсы первого набора

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

## Текущие задачи

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

**Первый шаг:** после реализации сверить actual main, Successful Debug/Release на новом code SHA, final docs Verify и новый Debug artifact; предоставить владельцу именно исправленный APK и его Build SHA. Сборка #1282 не содержит будущих исправлений; ориентироваться на самый новый успешный code SHA. После отдельного поручения на телефон выполнить выбранные владельцем сценарии ниже и записать устройство/Android/build SHA, действия, наблюдаемый результат и воспроизводимый negative case. Без поручения не устанавливать APK, не управлять телефоном и не делать платные API-вызовы.

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

## Планы на потом

Эти идеи не являются автоматически назначенным scope следующего PR. Проверять существующий код перед реализацией.

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

### 9. Расходы отменённых запросов

Из прежнего пожелания владельца: показывать фактическую сумму отменённого запроса только при доказанном provider usage/cost; если сумма неизвестна — явно «неизвестно», не ноль. Ledger и nullable accounting уже есть; отдельно проверить позднее получение usage после cancel и привязку к request_id. Не переносить общий счёт биллинга на конкретный request, не повторять платный запрос автоматически.

### 10. Более компактная организация настроек

Старое отложенное UI-пожелание: разделы «API и модель», «Мышление и ответ», «Инструменты», «Файлы», «Диагностика/дополнительно»; часто используемые controls — у поля ввода, сложные — в Advanced. В текущем showSettings уже есть секции и Advanced, поэтому сначала проверить реальную оставшуюся проблему, не переписывать приложение ради косметики; сохранять prefs и доступность всех настроек.

### Что сознательно не тянуть сейчас
- MCP и Computer Use — пока нет доказанной продуктовой потребности и это резко увеличивает execution surface.
- Полный Compose rewrite — инфраструктурная стадия, не пользовательская функция.
- «AI сам организует чаты/саммари/теги» — выглядит эффектно, но пока не доказано, что это важнее поиска, обратимости и прозрачности контекста.
- Синхронизация между устройствами — отдельная security/backend задача, не естественное продолжение текущей local-first архитектуры.

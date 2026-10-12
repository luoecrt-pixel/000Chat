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


### 11. [P1] OpenRouter: точная классификация ошибок и ограничений — подтверждённый пробел

**Наблюдение по коду (12.10.2026):** `OpenRouterChatClient.upstreamError()` читает `error.type`/`code`, но не `error.metadata.error_type`, `limit_source`, `resets_at` и `Retry-After`. Для 400 изображения могут быть `image_too_large`, `invalid_image`, `unsupported_image_format`, `image_download_failed`, `image_not_found`; для 402 различаются отсутствие средств, лимит ключа и in-flight budget; 403 может означать guardrail, 429 — rate limit, 503 — отсутствие совместимого маршрута. Сейчас пользователь может получить малоинформативное upstream сообщение. Не пересылать `metadata.raw` или `flagged_input` без редактирования.

**Первый шаг:** pure mapping `status + structured metadata + safe headers → безопасная человеческая причина, retry eligibility, retry-after`; регрессии synthetic HTTP 400/402 (с/без `Retry-After`)/403/429/503 и masked 500. `Retry-After` только как информация или явно разрешённая стратегия; **никаких скрытых повторных платных запросов**. Сохранить подробности только в защищённой диагностике и проверить редактирование API-ключей. [Ошибки OpenRouter](https://openrouter.ai/docs/api-reference/errors-and-debugging), [лимиты](https://openrouter.ai/docs/api-reference/limits).

**Уже обработано — не переделывать:** `OpenRouterChatClient.consume()` выбрасывает ошибку из верхнеуровневого `error` внутри HTTP 200 SSE (A1), `SseEventParser` поддерживает комментарии и многострочный `data:` (A3); финальный пустой usage chunk не добавляет текст и `usage` трактуется как snapshot, а не дополнительный платёж (A2). Добавить тесты именно на **первый/единственный** error SSE и повторяющийся `finish_reason`, не заводить эти случаи как отсутствующие функции. Клиент сейчас сам использует `stream:true`; non-streaming 200+error нужно учитывать только если появится non-streaming path.

### 12. [P1] OpenRouter Claude: кэш на маршрутах Auto и сохранность подписанного префикса

**Наблюдение:** `OpenRouterCachePolicy.configureRequest` выставляет top-level `cache_control` всем `anthropic/*` с включённым кэшем, а `OpenRouterClaudeRoute.AUTO` разрешает партнёрские маршруты. По текущей [документации OpenRouter о кэше](https://openrouter.ai/docs/guides/best-practices/prompt-caching) top-level automatic caching поддерживается только у прямого Anthropic; explicit breakpoints имеют иные правила. Это **несоответствие условий маршрутизации**, а не доказанный live HTTP failure или отсутствие cache hits.

**Первый шаг:** unit request-body matrix `AUTO/ANTHROPIC × 1h/5m/off × tools/system/history`; проверить fallback/route и provider cache usage на разрешённой владельцем live дешёвой сессии, только после этого менять стратегию. Не посылать несовместимые default 5m + top-level 1h. Различать cached read/write по provider usage, без предположения о цене каждого endpoint. При 400 из-за несовместимости signed thinking с pinned provider показать предложение выбрать совместимый маршрут или начать новую ветку без незаметного удаления исходной истории (A10/B8). Существующий `OpenRouterProtocolCodec.checkBinding` — защитный механизм; проверить его тестами при смене model/key/tools/route/system.

### 13. [P1] OpenRouter: PDF-аннотации и реальные пределы медиа

**Наблюдение:** `OpenRouterChatClient` собирает `content/reasoning_details/tool_calls`, но не переносит входящие `assistant.message.annotations` (parsed PDF и citation payload) в `openrouter_protocol.tail`; `OpenRouterResponseRuntime.annotations()` создаёт только локальные `local_file` карточки. На OCR/не-native пути это может приводить к **повторному платному парсингу PDF** на следующем turn (C2). `OpenRouterAttachmentPreflight` проверяет byte limits и размеры изображений, но явно не ограничивает суммарные **количество картинок и страниц PDF** с учётом истории (B11).

**Первый шаг:** mocked 2-turn PDF OCR scenario: ответ с `annotations[].file.hash/content` сохраняется **без потери оригиналов и без исполнения недоверенных данных**, возвращается в точном поддерживаемом форматировании; отрицательный сценарий — отсутствующий/подменённый hash. Сверить с текущей [спецификацией PDF](https://openrouter.ai/docs/guides/overview/multimodal/pdfs). Отдельно counted media/pages preflight на 20/21 картинках, пограничных моделях и документе с большим количеством страниц; не объявлять лимиты одинаковыми для всех моделей/маршрутов. При `native/auto/mistral-ocr/cloudflare-ai` явно сообщать пользователю о возможной отдельной тарификации OCR; текущий UI уже имеет selector.

### 14. [P1/P2] Claude 5.x: возможности модели, reasoning и видимость неполного ответа

**Наблюдение:** `OpenRouterReasoningPolicy` ограничивает effort для **статического списка** Opus/Sonnet/Fable, но не читает `reasoning.mandatory` или `supported_parameters` из API метаданных. Каталог моделей меняется. `OpenRouterChatClient` получает `reasoning_details`, но основной текстовые обновления идут только из `delta.content`; ответ с одними размышлениями может выглядеть молчащим (B1/B5). `OpenRouterFinishPolicy` **уже** переводит `length/content_filter/error` в incomplete, так что B6/B9 не считать отсутствующим обработчиком.

**Первый шаг:** запросная матрица с синтетическими `/models` capabilities: mandatory vs optional reasoning, supported effort, отсутствие недопустимых `temperature/top_p/top_k`, запрет неподдерживаемого forced `tool_choice` (текущий цикл шлёт auto), отсутствие assistant prefill в Claude 5.x. Для tool-only reasoning chunks проверить явное «идёт генерация», доступную диагностику/индикатор без показа скрытых или запрещённых thinking details. Не имитировать `content` из недоверенной `reasoning`. Тест `length` с пустым visible content и отказ `content_filter` обязателен. Параметры и названия моделей дополнительно сверять с `/models`, не объявлять свойства неподтверждённых 5.5-алиасов фактом.

### 15. [P1/P2] OpenAI: срок хранения файлов, удаление и безопасное повторное использование

**Наблюдение:** `OpenAiFileUploadCache` корректно разделяет account/credential/bytes/purpose и умеет `invalidate/clear`; **локальная очистка кэша не удаляет серверный файл OpenAI**. В коде есть reactive eviction при file-not-found, но политика `expires_after`, информирование пользователя об upstream retention и remote delete при удалении чата пока не подтверждены.

**Первый шаг:** определить явную user-facing retention/deletion policy и ownership между чатами/повторно используемыми file IDs. После Delete chat нельзя безусловно удалять remote file, если он нужен другому чату; нельзя обещать удаление на сервере после одного local cache clear. Mock server delete/expiry/error с локальными оригиналами и запретом автоматического платного повторного анализа. Верифицировать file input limits и `purpose=user_data/vision` по MIME и request-wide bytes отдельно (D2/D3). **Уже закрыто:** `OpenAiFileId` принимает `file-` и `file_` (D1); `MainActivity` умеет локально скачивать `container_file_citation` (D4) — нужны regression fixtures, не переписывание с нуля.

### 16. [P2] OpenRouter: HTTP generation ID, PDF/web-tool metadata и управляемая диагностика

**Наблюдение:** есть `OpenRouterGenerationReconciler` и учёт `/generation?id=`, а `OpenRouterChatClient` достаёт generation ID из JSON SSE; **fallback из заголовка `X-Generation-Id` не виден**. Это может терять сопоставление расходов, когда генерация оборвалась до JSON frame (A6). `OpenRouterServerToolCatalog` отправляет `openrouter:web_search`/`web_fetch`, но не выбирает конкретный engine, а входящие server annotations/citations явно не выделяются (C3–C5).

**Первый шаг:** mock `X-Generation-Id` без SSE ID, когда headers пришли до disconnect; безопасная запись в уже существующий per-round ledger, а `GET /generation` только как read-only reconciliation и с request ownership. Отдельно mock server-tool annotations/citations на assistant, совместимость изображения с tools и выбор engine/маршрута — не делать вид, что native engine всегда поддерживает обе операции. `debug.echo_upstream_body` допустим исключительно в **явно разрешённой dev-сборке** с synthetic/контролируемыми данными; никогда не включать по умолчанию, не записывать в logs/CI реальные keys/messages/file paths.

### 17. [P1] Исключить скрытую OpenRouter middle-out compression до отправки истории

**Подтверждено кодом и документацией, но не live-сокращением:** `OpenRouterChatClient.requestMessage()` отправляет `plugins` только для выбранного PDF engine, **не** указывает `{"id":"context-compression","enabled":false}`. Согласно [OpenRouter Message Transforms](https://openrouter.ai/docs/guides/features/message-transforms), endpoints с контекстом до 8192 токенов **по умолчанию** используют middle-out compression; администратор может также включить default plugins на уровне аккаунта. Это потенциально **молча исключает середину разговора**, включая вложения и важный подписанный префикс, вопреки требованию владельца «полная история без тихого обрезания». Для нынешних основных Claude это не свидетельствует о фактическом сокращении; риск возникает при смене модели/маршрута, в том числе будущими роутерами.

**Первый шаг:** добавить явное отключение компрессии в сформированном wire JSON одновременно с выбранным `file-parser` PDF plugin, не теряя его; mock request-body tests для малого context window, tools/history с image/file, всех cache modes и provider routes. При переполнении контекста должна приходить **явная ошибка без скрытого повторного платного вызова**, а не потеря частей беседы. Учитывать, что принудительная plugin policy уровня workspace может перекрывать request override: проверить effective behavior безопасным способом и показывать ограничение. **Не включать** server-side compact/edit/context-management, пока нет отдельного согласия пользователя на изменение истории. [Plugin precedence](https://openrouter.ai/docs/guides/features/plugins/overview).

### 18. [P2] Разделить подтверждённую общую цену и дополнительную стоимость server tools/tiers

**Наблюдение по коду:** `OpenRouterUsageSnapshot` читает токены и `usage.cost`; `OpenRouterChatClient.accumulateUsage()` суммирует стандартные поля, но **не сохраняет разложение** `usage.cost_details.server_tool_cost`, `upstream_inference_cost`, `iterations[]`, `server_tool_use` и `service_tier`. При текущих включаемых `openrouter:web_search`/`web_fetch` это снижает объяснимость расходов на инструменты; **не доказано**, что существующий итог `cost` занижен или неверен. Восстановление общей фактической цены из /generation уже есть (см. задачи 9 и 16).

**Первый шаг:** fixture двух tool rounds с `usage.cost` + `cost_details` + `server_tool_use` + `iterations`, добавить безопасные per-request/per-round поля учёта, исключить повторное суммирование уже включённой в total стоимости; показывать отдельные платные компоненты только когда они реально подтверждены провайдером. Отсутствие детализации — «неизвестно», не 0. Не включать дополнительные инструменты, быстрые tiers или сторонний Broadcast ради проверки. Опираться на [OpenRouter Server Tools](https://openrouter.ai/docs/guides/features/server-tools/overview) и актуальные поля API выбранного endpoint.

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


### 11. [P2] OpenRouter бюджет, отказ, приватность и модельные возможности

Проверить безопасные подсказки для `402/403/429/503` после задачи 11; пользовательское объяснение, что Stop при некоторых upstream маршрутах не гарантирует прекращение биллинга (A7/E1), без нового скрытого polling/auto retry. Для будущей настройки приватности сравнить `provider.data_collection=deny`, ZDR и доступность моделей/файлов (A18); выбрать default **только с владельцем**, не отказывать в доступе ко всем моделям молча. `/models` как источник модели/цены/max_completion_tokens/effort/параметров и точный resolved `response.model` для `~latest` (A13–A15/B10): сначала immutable capability snapshot и UI indication, затем динамический конфиг. Поддержка `json_schema` и `provider.require_parameters=true` уже присутствует, дополнительно проверять compatibility model/provider и negative mock (A16).

### 12. [P2] OpenAI: stateless reasoning, billing/rate и границы контекста

Проверка режима `store=false`/ZDR и переноса encrypted reasoning/function_call без выкидывания tool items (D7/D12) — **новая функция лишь при запросе владельца**, не незаметная замена текущему stateful Responses. Chain key/project/expired ID и complete replay уже включены в задачу 1; `response.incomplete/failed` и sequence cursor уже есть — не заводить дубликатов D6/D9. Дополнительно: безопасная UX-классификация `429 insufficient_quota/organization_limit/rate`, `503 overloaded` и `Retry-After` (D10); compact encrypted items передавать без трансформаций (D8), стабильный prefix для cache (D11). **Уточнение по официальной документации:** background Responses сейчас допускает `store=false` и даже ZDR с временным хранением для polling; утверждение «обязательно store=true» в исходном списке не следует добавлять как универсальный закон. Проверить точную политику хранения перед изменением приватности.

### 13. [P3] Android долгие запросы — отдельно от уже работающего onTimeout

`OpenRouterResponseService.onTimeout` для foreground `dataSync` уже реализован, а `MainActivity` запрашивает `POST_NOTIFICATIONS`. Не объявлять E3 отсутствующей функцией. На реальном Android 15/Xiaomi проверить видимость уведомления, последствия исчерпания суточного FGS budget, убийства процесса и доступность истории/черновика. Никакие OS-исключения из энергосбережения не выдавать за разрешение на бесконечный background stream.

### 14. [P2, только при решении владельца] Отдельный транспорт OpenRouter /messages для Claude

**Это не исправление существующего Chat Completions-клиента.** Нынешний `OpenRouterChatClient` вызывает `/api/v1/chat/completions` и использует `delta.content`, `delta.reasoning_details`, `finish_reason`, `tool_calls`, `OpenRouterProtocolCodec` и собственные sealed replay bindings. Возможности из присланного списка с маркером **[M]** относятся к другому endpoint: [Anthropic Messages через OpenRouter](https://openrouter.ai/docs/api/api-reference/anthropic-messages/create-messages).

Если владелец выберет Messages, сначала сделать самостоятельный transport/stream adapter, typed content-block persistence, signature/binding/Stop/ownership/price/attachments regression matrix, а затем UX. Конкретные кандидаты:
- citations документа и `search_result` в tool result; показывать источник, не превращая цитаты в простой слитный текст (A1–A3);
- `context_management.edits`, clear thinking/tool uses, server compaction и encrypted compaction blocks **только по явному opt-in**, с видимыми `applied_edits` и сохранением байтов протокола (A4–A5), без обхода запрета скрытого усечения;
- mid-dialog system reminders/per-turn effort, строгие инструменты, deferred loading/tool search, advisor, fallbacks, safeguards, thinking display/binding (A8–A16): разные model/route/schema ограничения и реальная цена; сначала capability proof + неплатные контракты, затем отдельная приёмка;
- `task_budget`, premium `speed`, особые sampling и metadata.user_id (A6/A7/A17) — не внедрять молча и не путать с аварийным 5M budget.

Не утверждать, что прямые Anthropic MCP, memory tool, code execution и token counting доступны через OpenRouter без официальной подтверждённой поддержки (G1–G5). Выбор всего нового endpoint — отдельное продуктовое решение; существующий signed-history Chat Completions обязан продолжить работать.

### 15. [P3] Дополнительные OpenRouter server tools — маленькие опции, большие расходы по согласию

[Обзор server tools](https://openrouter.ai/docs/guides/features/server-tools/overview): текущий каталог предлагает только web_search и web_fetch. Новые кандидаты из списка владельца:
- **`openrouter:datetime`** [C,M,R] — потенциально простой и полезный источник точного времени; перед добавлением проверить schema, timestamp/timezone, локализацию и overhead. Не считать обязательным багфиксом.
- **`openrouter:image_generation`** [C,M,R] — стоимость дополнительной image model, получение URL, безопасное скачивание, долговечное локальное сохранение и повторное открытие в истории; UI только после явного consent по оплате.
- advisor/subagent/fusion/tool_search — дополнительные модели, потенциально многократная оплата и новый слой доверия. Подключать отдельно, с max-cost/step policy и точным usage, без скрытого orchestration.
- **`openrouter:bash` [M]**, `openrouter:shell` [M,R], `openrouter:apply_patch` [R] и Containers/Files [бета] — **не работают автоматически в нынешнем [C]-клиенте**. Если появится реальная необходимость, спроектировать sandbox, разрешение на запись/сеть, границы файла/контейнера, retention/платежи, безопасные tool outputs; не отдавать модели устройство Android или приватные файлы.
- OpenRouter Files API, embeddings/rerank, speech, STT, TTS, видео и прочая мультимодальность — продуктовые кандидаты (B5–B7, F1–F3), **не включать без запроса владельца**; файл из облака может быть недоступен для обратного скачивания, локальный оригинал обязателен.

У OpenRouter server tools и beta интерфейсов схема/стоимость меняются; подтверждать по актуальному выбранному endpoint+model+provider, не по рекламному списку возможностей.

### 16. [P3] Управление OpenRouter: маршруты, response caching, доступ и безопасность

Из разделов C–E/H нового списка — **не доказанные ошибки текущего приложения**, а параметры будущего выбора:
- `models`/fallbacks, router families, provider `sort/order/only`, `max_price`, `service_tier`, presets и `X-OpenRouter-Metadata`: поддержка и стоимость зависят от endpoint, модели и конкретного провайдера; режимы fast/priority/flex не применять без информирования о цене. Текущий UI уже умеет запоминать requested модель и показывать фактическую, при расширении проверять resolved model и тариф.
- [Response caching](https://openrouter.ai/docs/guides/features/response-caching) через `X-OpenRouter-Cache` — **выключен по умолчанию**, но может прийти из preset. Если включать как отдельную функцию, обозначать `HIT/MISS`, TTL, повторную стоимость и предотвращать возврат того же ответа при явном «Перегенерировать» через `Cache-Clear`/другое тело. Не путать с 1h **prompt caching**, уже существующим в приложении.
- `plugins.response-healing` работает не во всех stream/schema комбинациях; `context-compression` может выбросить часть истории (см. текущую задачу 17). `moderation`/`alignment enforce`/Guardrails могут вызвать лишние платные действия или 403; нельзя включать скрыто.
- ZDR/`data_collection`/in-region endpoint, OAuth PKCE и BYOK — выбор владельца с учетом privacy/распределения платы/credential scope. Management API keys/credits/guardrails **не** класть в клиент; внешние Broadcast/trace передают данные третьим сторонам и требуют отдельного согласия.
- Batch, async generation, analytics, service tiers и variants — самостоятельные сценарии; Chat UI не должен обещать их только потому, что есть docs. Перед решением — capability matrix по `/models/{slug}/endpoints`, стоимости и data retention.

### Что сознательно не тянуть сейчас
- MCP и Computer Use — пока нет доказанной продуктовой потребности и это резко увеличивает execution surface.
- Полный Compose rewrite — инфраструктурная стадия, не пользовательская функция.
- «AI сам организует чаты/саммари/теги» — выглядит эффектно, но пока не доказано, что это важнее поиска, обратимости и прозрачности контекста.
- Синхронизация между устройствами — отдельная security/backend задача, не естественное продолжение текущей local-first архитектуры.

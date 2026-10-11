# Применение и проверка подготовленного пакета

Последнее дополнение и текущие границы: [BUNDLE_AGENT_HANDOFF.md](BUNDLE_AGENT_HANDOFF.md). Поддержка обоих file ID
и изоляция диагностического callback уже написаны; не заменять их задачами.
При SHA mismatch соблюдать RENDERER_DISTRIBUTIONS.md, не обновлять pin молча.
Следующий этап — первая сборка полного checkout, затем исполнение регрессий.

## Состояние и границы поручения

Владелец поручил написать код и правила для следующего агента, который применит изменения. Пакет написан на main 4111b83a215da03af544e8c0477246962c43395b; исходные Git blob SHA сверены. Репозиторий удалённо не изменялся. Push, PR, merge, Actions, платные API и телефонные испытания при подготовке не выполнялись. Это исходники для дальнейшей сборки и проверки, не готовый APK.

Изменение достаточно связное для одного PR: данные ответа, файловые инструменты и UI используют общие request_id и SQLite. Проверяй частями локально, затем собирай вместе. Не раскладывай исправления на несколько CI-прогонов без конкретной необходимости.

PR предпочтителен для проверки diff. Владелец отменил обязательность записи исключительно через PR и не поручал включать защиту main. Настройки репозитория и массовую уборку веток оставить вне scope. Автоматические GitHub write-инструменты приложения по-прежнему требуют предусмотренного подтверждения пользователя.

## Порядок применения

1. Прочитай START_HERE.md внутри архива и manifest.json. Сверь actual main, текущую рабочую ветку, PR и чужие изменения. Не предполагай, что базовый SHA вечен.
2. В чистом checkout запусти verify_bundle.py из архива с путём checkout: он проверяет хеши и git apply --check, ничего не записывает. При несовпадениях сопоставь изменения вручную; не заменяй весь MainActivity чужой версией.
3. Примени changes.patch локально. Он содержит изменённые файлы, тесты и документацию. Файлы в files/ нужны для сравнения, а не слепой массовой перезаписи.
4. Выполни проверки из BUNDLE_AGENT_HANDOFF.md и START_HERE.md (в корне переданного архива). Первая сборка — локальный Debug. Проверь закреплённые jar SHA и написанный маршрутный код пункта 36 до Release/финальных Actions. Доведи ошибки Kotlin/JVM/lint до исправления; нельзя выдавать подготовленный пакет за проверенное приложение.
5. Если публикация и CI поручены, обнови handoff и описание результата, отправь одну финальную пару APK/Release на проверяемом code SHA. Docs-only последующее уточнение не требует повторной Android-сборки при неизменном коде. Проверь реальный event/SHA/attempt/conclusion каждого run.
6. После успешной сборки выполни согласованные проверки телефона/провайдера. Успех mock-сервера не доказывает acceptance и стоимость реального API.

## Обязательные контракты

| Область | Что сохранить | Негативный сценарий |
| --- | --- | --- |
| Stop | Удаление ровно отменённого user request в выигравшей SQLite-транзакции; завершённый ответ при победе финализатора сохраняется | Stop одновременно с Worker/Runtime, затем stale UI snapshot и переоткрытие |
| Claude SSE | JSON null не является строкой; незавершённые tool calls не исполняются | content/name/arguments:null, length с текстом/без текста, отсутствующий finish_reason |
| Протокол | Канонические assistant/tool сообщения, порядок reasoning_details и подписи сохраняются отдельно от UI | Новый turn после reopen, изменение system/tools/model/API account или раннего сообщения |
| Файлы | Успешно созданный UTF-8 файл в ledger и пузыре; не имитировать бинарный формат текстом | null content, имя .pdf, Stop сразу после создания, удалённый чат |
| Контекст | При отсутствии/изменении локальных байтов ошибка до отправки; не молчаливое исключение вложения | file_ids есть, file_paths=[""]; отсутствующий оригинал/изменившийся SHA |
| Память устройства | Потоковый JSON и дисковое извлечение, без удаления истории | Несколько больших фото/PDF в истории; нехватка heap до Base64 |
| Длинные ответы в SQLite | Чтение TEXT по байтовым частям в одной транзакции; полный текст и подписанный протокол | Мегабайтный ответ/протокол, Unicode через границу части, NUL, составной ключ checkpoint, reopen |
| Шторка | Жест поверх текста; удержание следует пальцу; отпускание выбирает ближайший край | Вертикальная прокрутка, вложенный горизонтальный scroll, выделение текста, Back |
| Клавиатура | Компактное вложение в composer; ввод и Send выше IME | Фото + многострочный текст + большая системная гарнитура; отправленное preview не уменьшать |
| Фон/уведомления | Runtime принадлежит приложению; только выигравший финализатор уведомляет; клик открывает правильный чат | onStop/recreate/Stop; permission denied; process kill; Xiaomi battery restrictions |
| GitHub range | 40 000 символов на вызов с EOF, строгий UTF-8, ref allow-list; ETag для согласованности страниц | Файл меняется между страницами, бинарные байты, отмена владельца |
| Оформление ответа | Общая native панель Claude/OpenAI; канонический source/protocol неизменен, block fallback отдельно | Chunks внутри ограды/таблицы/file, malicious Markdown/HTML/URL, 380dp/dark, large text |

Новые дополнительные текстовые tool_trace намеренно компактны. Канонический подписанный протокол может содержать полные tool arguments/replies: его нельзя обрезать ради экономии токенов и одновременно обещать сохранность подписей. Не дублировать этот протокол ещё раз как обычный текст отчёта.

reasoning_details — протокол конкретной модели, не долговременная пользовательская память. Потеря этих данных не доказывает неправильность каждого обычного следующего ответа. Сохранение особенно существенно для продолжения Claude с инструментами и моделями, использующими подписанное мышление. При несовместимом префиксе старые блоки сбрасываются с видимым предупреждением; новая память пользователя — отдельная будущая задача.

## Ответ, мышление и ограничения

- Сохранён аварийный бюджет одного OpenRouter complete(): 5 часов и 5 млн суммарных reported usage tokens. Повторно отправляемый вход каждого раунда учитывается в этой сумме; это не обрезание истории сессии. Если usage не пришёл, точное количество токенов неизвестно; время остаётся защитой.
- Неявных 128 000 нет. Пустое поле max_output и старое значение 128000 означают отсутствие параметра. Положительное вручную выбранное другое значение передаётся в API. Отсутствие параметра не отменяет ограничения самого провайдера/модели.
- Ручные low/medium/high/xhigh/max не понижать молча. Для Anthropic verbosity не отправлять, чтобы не переопределять effort.
- OpenAI compaction после 800 000 токенов включается/выключается настройкой. Не обещать бесконечный контекст при выключении: контекстное окно провайдера сохраняется.
- Лимиты страниц файлов, ячеек, распаковки и heap защищают устройство/парсер; они не ограничивают общий доступный файл. Превышение — явная ошибка, не скрытое усечение.
- OpenAI 4g относится к RAM контейнера Python, не к памяти модели и не к effort.

SqliteLargeTextQuery ограничивает размер строки CursorWindow и одной SQL-части, не длину ответа. Полный String/JSON всё равно занимает heap. Внутренний reader применяется только к TEXT/scalar полям с небольшими ненулевыми ключами, UTF-8 DB и закрывается на создавшем cursor потоке. Его транзакция защищает от смешивания разных версий при чтении частей; проверить длительность блокировки writer на большой реальной истории. Ownership SSE проверять через isActiveRequest, без загрузки всего активного ответа.

По последнему уточнению владельца текущий рабочий этап длится не более 40 минут; всегда учитывать более новое ограничение в разговоре. Сохрани результат и продолжение в новом кратком WORK_CHECKPOINT.md перед остановкой; новый этап начинается после «Продолжай». Это не изменение пятичасового лимита ответа Android-приложения.

Требования к отображению кратко суммированы в BUNDLE_AGENT_HANDOFF.md, подробности реализованы в исходниках и тестах. Новый Markwon 4.6.2 использует native TextView; сетевых загрузчиков картинок нет. HTML/SVG preview — отдельный WebView с JS/network/file/content/storage off, opaque sandbox и CSP. API/test/library запросы не делать из рендерера. Скрипты, raw HTML и custom file tags не должны получать Android bridge или доступ к secrets. Pinned KaTeX/Mermaid assets и offline preview проверены в полном CI на `cceacdd8c392b2a69338f2313c6db137d210f307`; Debug device test прошёл на API34. Exact run IDs и оставшиеся phone/live границы — в каноническом handoff. Renderer guard не должен становиться тайным ограничением ответа. `<file>` парсить только для UI/явного сохранения, никогда не переписывать подписанную историю. JSON.parse/JSONObject не считать полной проверкой JSON Schema.

## Провайдеры и реальная ошибка фото

Скриншот показывает server-tool invalid request (400); точная первопричина без upstream-проверки не установлена. Фото около 8 МБ превышает 10 МБ после Base64 и представляет отдельную подтверждённую границу Claude. Пакет добавляет предварительные проверки, диагностику параметров ошибки и выбранную пользователем совместимую копию. Не объявлять реальную 400 исправленной по mock-тесту; не выключать web_search/web_fetch вслепую и не делать автоматические повторные платные запросы.

Выбор оригинала сохраняется по умолчанию. Совместимая копия может уменьшить геометрию/качество, прозрачность и анимацию; оригинал и отображение в чате не заменяются. Native PDF по умолчанию сохраняет способ передачи; дополнительные OCR/parser выбираются явно, потому что меняют стоимость/представление.

Документация top-level cache_control расходится: опубликованная страница OpenRouter описывает прямой Anthropic, более свежий официальный исходник включает дополнительные endpoints (Bedrock/Vertex/Azure). Не считать доступность/исключение этих маршрутов доказанными по Android-коду. Direct Anthropic выбран явно и отправляет provider.order с allow_fallbacks=false; Auto не обещает конкретный endpoint. Документированный 5m: {"type":"ephemeral"} без ttl; 1h добавляет ttl="1h"; off не отправляет marker. Описанные множители записи 1h/5m — ×2/×1,25 базового input; фактическую цену и маршрут подтверждает usage/биллинг. Источник: https://openrouter.ai/docs/guides/best-practices/prompt-caching; перед сменой политики сверить страницу и официальный исходник. Default 1h остаётся решением владельца.

Замечания к предыдущему архиву реализованы в исходниках; актуальные границы см. BUNDLE_AGENT_HANDOFF.md. OpenRouter image/PDF wire values теперь FileBackedDataUrl: копировать через JsonProtocol, сериализовать только StreamingJsonRequestBody, не JSONObject.toString() и не getString() для внутренних file values. Outbound wire и protocol fingerprints по-прежнему содержат полный Base64 тех же байтов; в persisted signed tail входят assistant/tool сообщения, а не локальные file objects. Переключение способа сериализации не должно сбрасывать thinking prefix при неизменных байтах.

Уведомление поставлено в SQLite answer_notifications в той же winning transaction, что assistant. После notify — durable acknowledgment; при падении повторный пост использует тот же Android tag/id. Это at-least-once доставка, не обещание транзакционной exactly-once внешней операции. Permission denied оставляет очередь; просмотр чата/выключенные уведомления явно закрывают её. Queue/FK и preferences replay проверить на Android, включая kill между DB commit, notify и acknowledgment. Не повторять API ради доставки уведомления.

GitHub range использует приватный UTF-16 disk snapshot, atomic metadata, conditional ETag на каждом вызове и прямой seek к offset. Согласованность не подменять устаревшим cache при HTTP error/изменении ETag. Contents API не поддерживает >100 MB; disk budget — явная ошибка, не усечение. OpenAI upload reuse проверяет SHA bytes, MIME/name/purpose и hash credential; expired/deleted IDs не являются поводом для автоматического платного resend. В UI есть явный сброс локальных reuse ссылок. Автоматические Code Interpreter containers по-прежнему не выдавать за бессрочно переиспользуемые.

## Проверки телефона

Проверить Android 13+ permission/channels, включённые и выключенные уведомления, открытие нужного чата, обычный фон и блокировку, гибель процесса, режим батареи Xiaomi и системные ограничения background/dataSync. Не обещать продолжение Chat Completions после kill: провайдер не даёт безопасный ID для восстановления того же потока. Не добавлять повторную платную отправку как «восстановление».

Android может остановить foreground service по своим правилам. Проверить API 35 onTimeout и реальные permission/settings на устройстве. Настройки автозапуска/батареи меняет пользователь; не выдавать их наличие за доказанную пятичасовую работу.

## Официальные справки

Сверены 10 октября 2026; перед изменением wire-контрактов перечитать актуальные страницы:

- https://openrouter.ai/docs/guides/best-practices/reasoning-tokens
- https://openrouter.ai/docs/guides/best-practices/prompt-caching
- https://openrouter.ai/docs/guides/overview/multimodal/pdfs
- https://platform.claude.com/docs/en/build-with-claude/preserved-thinking
- https://platform.claude.com/docs/en/build-with-claude/vision
- https://developers.openai.com/api/docs/guides/pdf-files
- https://developers.openai.com/api/docs/guides/tools-code-interpreter
- https://developer.android.com/develop/background-work/services/fgs/timeout

## Контракты после ревью Claude 1–32

Сначала прочитать BUNDLE_AGENT_HANDOFF.md и затем сверить актуальные исходники. Не принимать reviewer conjecture за API факт. Лимиты images/direct/partner и native request envelope различаются; preflight не гарантирует upstream acceptance. OOM recovery best effort, VM Error пробрасывается. Диагностика живёт в error metadata и не дописывается в assistant.text. JSON detection строгий независимо от Android org.json. Math/Mermaid prompt допускается только при наличии APK renderer assets и включённом preview; до публикации выполнить actual engine tests.

Не включать idle timeout, не менять 5M accounting, default 1h и не сокращать signed tool/reasoning replay без отдельного решения владельца. Не «оптимизировать» точный SHA через объединение fragment digests или кэш только mtime/size. При manifest repair использовать targeted CAS в SQLite, сохранять прочую историю и явно сбрасывать невалидную response/protocol chain. П.20/26/29 реализованы в исходниках, Android не принял их. Для п.27 добавлены manifest CAS и lease/journal; kill/race/cleanup требуют Android проверки.

Large render worker не доказывает отсутствие ANR/heap проблем; проверить stale parse cancellation, table state, широкий grid и Android main work. Тесты новых JSON/status/JPEG/XLSX/index/locks/outbox путей обязаны реально выполниться в полном checkout. Следующую миграцию добавлять после текущих, не в начале onUpgrade.

## Дополнение: восстановление вложения

Используй AttachmentMessageState и updateMessageAttachments, не full-chat rewrite.
Кэш описания не меняет исходные file/image ссылки и не сбрасывает контекст.
Выборочное удаление/замена/новая кодировка требует attachment CAS и отсутствия
активного ответа/подготовки; send и mutation marker сериализуются на UI.
Сброс OpenAI chain и protocol_context_state=invalidated относятся к старому
контексту; signed protocol остаётся сохранённым, не удаляется.
v22 добавлена в конце onUpgrade; v17 metadata loop исключает новые колонки.

FileBackedText нельзя превращать в JSONObject.toString для отправки или логов.
Запросы с ним сериализует только StreamingJsonRequestBody. Порог 64 KiB — способ
записи, а не скрытый предел ответа/контекста. Manual decoder должен сохраняться
при повторном создании text_path и в Code Interpreter upload; guessed flag нельзя
выводить только из имени Windows-1251. Orphan abort/kill cleanup проверять на
Android; до этой проверки не считать пункт 27 полностью принятым.

## Подготовка файлов и streaming presentation

PreparedAttachmentFiles регистрирует только новые derived_ файлы. Не добавлять
в этот журнал оригиналы; не удалять при недоступной DB/composer/runtime ownership
и не считать mtime доказательством отсутствия владельца. Проверить kill между
журналом/записью/DB commit и runtime completion; journal commit остаётся best effort.
UI pages и глубина details ограничивают работу Views, а не длину ответа/контекста.
Cancellation callback не меняет текст, signed protocol или provider effort. Native
HTML допускает только br/sup/sub без attributes; details parser не исполняет HTML.
Проверить capability gate math/mermaid prompt по наличию assets и preview preference;
не выпускать приложение без actual engine test. Полный source сохранить независимо от render fallback.

## Scientific preview и сохранение большого source

Выполнить :app:prepareResponseRendererAssets, verify_response_renderer_assets.py
и ScientificRendererDeviceTest. SHA реальных jar уже закреплены: сравнить их с
config/response-renderer-sha256.properties и выполнить Gradle Release guard;
generated SHA list сам по себе не заменяет pins. Не добавлять network/CDN/allowFileAccess/Android bridge
для обхода module/font failure. Scientific viewer выполняет только собственный
скрипт; HTML/SVG viewer сохраняет JS off. Никаких model script/html insertions.
ScientificMathText делает inline/display typesetting prose по явному нажатию;
автоматическая streaming/table/page-crossing typesetting ещё открыта. Для formatted
view Html.toHtml получает только очищенное native Markdown дерево, не сырой model
HTML; атрибуты удаляются, случайные tokens заменяются только в text nodes.
WebView создаётся по нажатию, уничтожается при возврате/detach; stale callback не
может закрывать следующий renderer. Проверить это на Android, не включать JS в
обычный HTML/SVG viewer. Viewer guards не меняют response/API/token budget.
Полный UTF-8 source сохраняется через существующий ledger/SAF. Не заменять chunked
write на whole toByteArray ради удобства; проверить surrogate boundary и NUL.

## Дополнение после ревью 34–37

Все четыре пункта написаны; BUNDLE_AGENT_HANDOFF.md и RENDERER_DISTRIBUTIONS.md имеют
приоритет над историческими checkpoint. Min Mermaid assets проверены по реальным
jar (82 modules), SHA-256 двух скачанных Maven Central jar уже закреплены; первые
ключи не заполнять заново и не отключать guard. Actual Gradle/API/WebView checks
ещё выполнить. Auto/Direct route уже реализован в UI, immutable send snapshot,
preflight и каждом tool round; structured provider merged, оригиналы сохранены.
Не поручено заново писать пункт 36. Проверить OpenRouterClaudeRouteTest и новый
production HTTPS lifecycle fixture. JPEG reader уже block-based; выполнить JVM
и реальные image checks, сохраняя SHA. П.33 оставить измерением, не вводить v2 reset.

## Дополнение A–F: файловые кэши

Ключевые ограничения файлового кэша изложены в BUNDLE_AGENT_HANDOFF.md; перед compile проверь реальный код. A–D/F исправлены
по коду; E имеет I/O telemetry, реальный профиль остаётся измерением. Не восстанавливать
импорт исторических OpenAI IDs, optional ETag поздних страниц, глобальную сетевую
блокировку и очистку диска только после скачивания. Composer/full-analysis uploads
используют общий byte/account/name/MIME/purpose cache. Clear/invalidate через generation
guard; missing server ID не запускает автоматический платный resend. Full signed tail,
fingerprint v1 и проверки целостности сохранены. Тесты написаны, но не выполнены.

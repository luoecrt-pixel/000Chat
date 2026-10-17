# AGENT_TASKS — задачи и планы 000Chat (Integral LLM)

Единственный рабочий план. Правила — [NEXT_AGENT_HANDOFF.md](NEXT_AGENT_HANDOFF.md), причины прежних решений — [PROJECT_HISTORY.md](PROJECT_HISTORY.md). Номера сохранены для связи с поручениями; выполненные части исключены из активных задач.

## Исходное состояние и очередь

- Сверено 17.10.2026 с `main` `e096b8be2961c113a75694de9f37bd166de9c019` (J / PR #174 влит). Перед новой реализацией повторно проверить actual main, PR и CI.
- Реализованы: основа OpenAI Responses/instructions/recovery и сохранение outputs (A); защитные срезы OpenRouter/файлов/ключей; основные навигация и жесты F; темы, настройки и бренд G; локальные tools GPT, мульти-фото и автоматические научные preview H/I. Остатки прежних критериев перечислены ниже. Непустые черновики видны как «Черновик», пустые новые сессии скрыты — это актуальное уточнение правила #2.
- **J / #28 принят в PR #174:** разные bytes и порядок мульти-фото, retry дочернего GET без повторного платного POST, lifecycle/Stop ownership, живой статус восстановления, telemetry tool-раундов и ручные научные preview. Не назначать этот пакет заново; визуальная и live/device-приёмка остаётся в #9.
- **#21 + K / #29 + #18.1–2 выполнены и закрыты в рабочем плане:** snapshot платных настроек на Send, отложенный upload без дублей, атомарная компактизация checkpoint с сохранением оплаченных данных, идемпотентный учёт раундов, диагностика и честный unknown/zero cost. Код — [PR #175](https://github.com/luoecrt-pixel/000Chat/pull/175), ветка `feat/package-k-atomic-accounting-and-uploads`; Debug/Release CI успешны. PR пока Draft, в main эти изменения ещё не влиты; независимый review/merge остаётся процессом PR. Матрица доказательств и точный code SHA — в PR. Не назначать завершённую реализацию повторно.
- Следующая реализация — восстановление чтения (#3) и прочие остатки B/C/D. Темы в M (#31) завершают старые требования; экспорт чата N (#32) и GitHub для GPT в L (#30) — расширения.
- Затем завершать B/C/D (#12–16, #18.3, #22, остаток #24) по риску и зависимости; небольшие UI-доработки #5/#31 допустимы параллельно. Не начинать L или крупные новые transports/миграции до контрольного аудита ниже.
- Последнее уточнение владельца: #26/#27 отложены до практической потребности в Messages-only возможностях. Текущий transport — OpenRouter Chat Completions с provider Anthropic. Compose Android (будущий №22) не зависит от KMP/iOS (будущий №23).
- Физический телефон, production signing и публикация — только по отдельному поручению (#9/#10).

### Контрольный рубеж: отдельный полный аудит

После короткой стабилизации зафиксировать один baseline `main` SHA и таблицу известных открытых работ. Два новых независимых агента отдельно проверяют Android/lifecycle/SQLite/files и GPT/Claude protocols/security/accounting; P0/P1 перепроверяются перекрёстно, результат — один отчёт без дублей. Прежняя Issue #161 удалена (GitHub возвращает 410); её ссылка не является доступным заданием. Доказательств выполнения этого этапа при сверке не найдено.

Условия старта: A принят, релевантные CI/регрессии подтверждены для baseline, нет незакрытого подтверждённого неотложного P0/P1. Некритичный backlog не должен бесконечно откладывать аудит. Известные остатки не выдавать за новые находки. Настоящая сверка плана и обычные PR-review не заменяют полный аудит; его не автозапускают после каждого PR.

### Граница готовности PR

До реализации зафиксировать scope и матрицу `контракт → фактический Activity/Worker/SQLite/HTTP путь → positive/negative test → harness → SHA/CI`. Проверять затронутые Stop/Delete/recreate, ownership, recovery, файлы и учёт. Runtime-изменения требуют релевантных исполняемых тестов и успешных Debug/Release на code SHA; docs-only — Verify handoff. Доказательства хранить в PR. Live API, billing и физическая приёмка — отдельные уровни проверки. Не объединять независимые функции в непроверяемый мегапатч.

## Текущие задачи

### 3. [F · P1/P2, остаток] Позиция чтения при пересоздании Activity — **следующий**

Anchors/scroll lock реализованы в памяти экземпляра Activity, но `perChatScrollAnchors` / `userScrollLockedChats` не сохраняются в saved state; RecyclerView создаётся без стабильного view ID. Поэтому recreate/rotation не закрыты.

Сохранять по чатам устойчивую identity сообщения, offset и follow/reading mode; восстанавливать после commit `AsyncListDiffer` и layout, учитывая высокие bubbles и изменение rich heights. Обычные background/recreate/task restoration сохраняют место; только настоящий cold launch после полного завершения допускает переход в конец.

**Приёмка:** Activity tests A→B→A, середина высокого последнего ответа, background delta, rotation/recreate/saved-state restore, theme/font resize, отдельный cold launch. Тот же читаемый текст без прыжка/«рогатки»; фактическое расстояние до низа, отдельная политика Send/явной кнопки перехода.

### 12. [C · P1/P2, остаток] Claude route/cache и сохранение сброса signed reasoning

Default Anthropic внутри OpenRouter и уважение явно сохранённого Auto реализованы. Осталось:

- Wire matrix `AUTO/ANTHROPIC × 1h/5m/off × tools/system/history`; согласовать top-level automatic cache с реально поддерживаемым маршрутом Auto, не смешивать несовместимые nested 5m/top-level 1h. Сохранить explicit breakpoints, историю и route/fallback policy.
- После `checkBinding()` сохранять в SQLite факт безопасного сброса несовместимых `reasoning_details`, чтобы warning не повторялся каждый turn; не менять оригинальную хронологию/подписи без причины. Тестировать model/key/tools/route/system mismatch и reopen.
- При signed-thinking/provider HTTP 400 предлагать совместимый маршрут/новую ветку без молчаливого удаления истории и платного автоповтора. Mock не доказывает live cache hits/цену.

### 13. [D · P1] PDF annotations и лимиты медиа OpenRouter

Сохранять серверные PDF `annotations[].file.hash/content` и citations в protocol tail в актуальном поддерживаемом wire-формате, с привязкой к исходнику, без потери originals и исполнения недоверенных данных. Mock двух turn: правильный parsed payload переиспользуется; отсутствующий/подменённый hash не позволяет взять чужой результат. Live отсутствие повторной оплаты отдельно не доказано.

В `OpenRouterAttachmentPreflight` считать изображения/PDF pages **по всей отправляемой истории**, с пределами модели/маршрута и явной ошибкой вместо усечения. Проверить границы и unreadable PDF, не вводить единый выдуманный лимит. Для native/auto/mistral-ocr/cloudflare-ai ясно сообщать о возможной цене OCR; не подменять engine незаметно.

### 14. [E · P1/P2, остаток] Capabilities и reasoning-only progress

Расширить `/api/v1/models` сверх `input_modalities`: `supported_parameters`, reasoning capabilities и `reasoning.mandatory`, если поле действительно есть в текущей схеме. Статический alias не доказывает возможности модели. Матрица: mandatory/optional reasoning, effort, допустимые sampling/forced tool параметры, отсутствие неподдерживаемого assistant prefill, missing/stale metadata. Ручной effort молча не снижать.

При `reasoning_details` без visible content показывать прогресс/ожидание tools без раскрытия hidden thinking и без превращения reasoning в assistant text. Проверить Stop, пустой visible ответ и existing `length/content_filter/error → incomplete`; эту обработку не реализовывать заново.

### 15. [B · P1/P2, остаток] Retention, удаление server files и request-wide limits OpenAI

Локальная fail-closed защита общих файлов/канонических путей реализована. Определить policy retention/`expires_after` для поддерживаемых purpose и сообщить пользователю сроки. Безопасный `DELETE /v1/files/{id}` — при последней ссылке согласно policy, с учётом других чатов, drafts/active requests, Vector Store и account binding. Cache clear не считается server delete. Remote error/expired ID/account switch не запускает скрытый upload/resend и не удаляет original.

Сохранить исходный критерий проверки `purpose` по MIME (`user_data`/`vision`) и лимитов **реально отправляемого запроса**, включая replay/history/local manifests, а не только текущий выбор. Нынешняя сумма `requestFileSizes` сама по себе полноту budget не доказывает. Сначала сверить текущую API-схему/лимиты; это acceptance investigation, не доказанный live отказ.

**Приёмка:** shared references, credential mismatch, remote failure, expired IDs, reopen; actual wire purpose/полный request budget на history replay, без тихого усечения и повторного платного вызова. Неопределённые ссылки обрабатывать fail-closed.

### 16. [D · P2, остаток] Web citations, engine и dev-only диагностика OpenRouter

`X-Generation-Id` до SSE и per-round reconciliation реализованы. Осталось хранение/отображение web_search/web_fetch annotations/citations (согласовать с #13), явный выбор поддерживаемого engine и совместимость route/tools/images.

`debug.echo_upstream_body` — только отдельный явно разрешённый dev-only режим на synthetic/контролируемых данных, off по умолчанию; production/обычная диагностика не получает сырые keys/messages/paths. Флаг не нужен основной пользовательской функции citations.

### 18. [D · P2, остаток #18.3] Актуальные тарифы и политика оценок

Unknown/zero/estimate и детализация фактической стоимости (#18.1–2) закрыты в PR #175; повторно их не реализовывать. Осталось:

- Получать HTTPS цены OpenRouter `/models` и подходящего `/models/{author}/{slug}/endpoints`, хранить TTL/дату/статус оценки. Неизвестные tool/cache-write тарифы не выдумывать.
- Заменить захардкоженные оценки Activity/Worker на provider/model-aware policy; тариф OpenRouter не переносить на OpenAI. Оценка не заменяет подтверждённый расход, включая фактический ноль.

**Приёмка остатка:** cache read/write, tiers/tools и отсутствующие тарифы; stale/corrupt metadata, offline, 401/402/429, reopen. Новый preflight или ошибка получения цен не стирают подтверждённые предыдущие раунды и не превращают unknown в ноль. Live сверка тарифа/счёта остаётся в #9.

### 22. [D · P2, остаток] Старый ownership seam и bounded recovery resources

- Удалить действительно мёртвые `openRouterActiveCall`, `registerOpenRouterCallIfOwned`, `beginOpenRouterRequestOwnership`, `githubToolExecutor`; сначала перевести **все** зависимости `MainActivityLifecycleTest` на реальный `OpenRouterResponseRuntime`, без старого числа «4 теста».
- Cancellable backoff перед повторными GET `OpenAiCancelReconciler` с deadline/Retry-After.
- Убрать отсечение reconciliation первыми четырьмя generation IDs, учитывать длинные tool loops через очередь/явные resource budgets; саму генерацию числом rounds не ограничивать.
- Ограничить cached thread pool `OpenRouterResponseRuntime`, с явной очередью/отменой, без потери terminal/usage и отмены другого чата.

Сохранить различие OpenAI resumable background Response и OpenRouter inference: после потери SSE — partial/checkpoint/known-or-unknown cost, без скрытого второго платного запроса. Статусы «генерация / связь потеряна / получение результата / неполный / готово» соответствуют реальности. Existing Stop/Delete/Edit/notification fixes не переделывать без причины.

### 24. [B/H · P2, остаток] Нормализация артефактов и контекст поиска

GPT bridge реализован; не создавать заново. В регистрации `db.listLocalFiles()` использовать общий `classifyLocalFile` или эквивалентный безопасный контракт кодировки/структуры вместо ручного kind и безусловного UTF-8. Проверить каталоги GPT/Claude: доступность message attachments и созданных артефактов своего чата должна быть определена явно, без доступа к чужим файлам.

В `LocalFileTools.search` overlap учитывает длину query **и** обещанный левый snippet context. Проверить длинный Unicode query на границе buffer без бесконтрольного heap. Не дублировать `file_info` ради старого имени, если list/read metadata уже закрывают задачу. Сохранить безопасные IDs/диапазоны, original bytes и Stop/checkpoint semantics J / PR #174 и K / PR #175.

### 5. [G · P2, остаток] Длинные научные ответы и визуальные критерии

`ResponseTextPages` режет по 32 000 символов с защитой только surrogate pairs. Для завершённой формулы на границе страницы переносить целый блок либо давать явный fallback для source больше безопасного UI budget. Не повреждать source/copy/history/API input и не создавать бесконечный WebView.

Ручные preview реализованы в J / PR #174, включая выключенное auto-preview; повторная реализация не нужна. На одинаковых GPT/Claude fixtures довести/проверить компактные typography/copy actions, таблицы со всеми rows/columns и признаком горизонтального scroll, реальные structured tool cards и file card/Save. Auto-preview и адаптивные колонки не доказывают весь дизайн. Tests: narrow screen/font scale, 32K boundary, invalid/incomplete/large source, reopen, sandbox. Физическая visual acceptance — #9.

Шторка #4 реализована: порог от исходного положения, velocity/reversal. CANCEL/multitouch/interruption и визуальная плавность остаются частью приёмки; не объявлять их багами без воспроизведения.

### 31. [M · P2] Остальные диалоги и видимый бренд

1. **Темы, остаток #6.** Переименование/создание/удаление чата, Vector Store, diagnostics, local files/errors, GitHub confirmation, encoding, app-owned attachment/export dialogs и другие окна приложения. Системные Photo Picker/SAF оформляет Android, а не helper приложения. Оформлять custom content **и** window/title/list/action buttons/spinner/dropdown, selected/disabled states. Один `applyDialogControlTheme(box)` не покрывает весь dialog chrome. Проверять фактические foreground/background и контраст в dark/light/gray + font scale; отсутствие WHITE текста недостаточно. Theme switch сохраняет draft/attachments/anchor/request.
2. **Бренд, остаток #25.** Убрать app labels Astra в clipboard diagnostics, предлагаемом имени нового экспорта/Vector Store и других подтверждённых видимых местах. Внутренний speaker label менять только после проверки его видимости. Не переименовывать `gpt-6-astra`, классы/package/БД/старые файлы/compatibility IDs.

### 32. [N · P2, отдельное расширение] Экспорт диалога в Markdown и текст

Текущий TXT-export отдельного сообщения уже сохраняет сырой source; порча формул/таблиц не доказана. Добавить отдельные UTF-8 `.md` и `.txt` экспорты всей беседы с порядком/ролями, code fences/формулами/таблицами и краткими summaries (tool/status/result file). Источник для OpenRouter — structured tool_trace; для OpenAI такого поля сейчас нет: нормализовать безопасные metadata typed function_call/function_call_output из `openai_protocol_output` либо сохранить отдельный durable summary при реализации N. Имена `executed_tools` и общие счётчики K не заменяют поимённые статусы/результаты вызовов для экспорта. Не выдумывать неизвестный статус и не выгружать сырые arguments/output JSON вместо сводки. Для TXT сохранить тот же читаемый исходник Markdown без служебных JSON. Не добавлять hidden prompts/reasoning/encrypted_content/checkpoints/secrets; не удалять пользовательский текст regex. Raw export сообщения сохранить. Tests для обоих providers: кириллица, nested fences, inline math/table, failed/cancelled tools, реальные attachments и открытие обоих файлов без искажения source. Независимо от тем M, без обязательного объединения в один PR.

### 30. [L · P2, отдельное расширение] GitHub read-only tools для OpenAI

После стабилизации bridge и контрольного аудита подключить `get_file`, `get_file_range`, `list_directory`, `get_pull_request`, `list_pull_requests`, `list_issues`, `get_workflow_runs`, `get_workflow_run_jobs` поверх текущего GitHub executor/каталога; сначала сверить фактические имена/схемы. Write-каталог в GPT не копировать.

Разделить registry/dispatch локальных файлов и GitHub: GitHub-only запрос не требует вложения. Явный opt-in, PAT только локально, repo/ref allow-list, безопасные paths, bounded results/pagination/ranges/Stop. Activity/Worker используют один configuration snapshot; привязать durable запрос к исходному OpenAI credential, GitHub credential identity и allow-policy через безопасный hash/version. Текущий executor читает актуальный PAT при каждом вызове; одной OpenAI fingerprint проверки недостаточно. После rotation/revoke/смены прав продолжение не получает иной аккаунт/расширенные права молча; raw PAT не попадает в checkpoint/model/log.

Поиск кода GitHub из дополнительного пожелания — отдельное расширение L: `search_code` сейчас отсутствует в каталоге/executor. Сначала определить поддерживаемый endpoint, repo/ref scope и полноту поиска; реализовать allow-list/rate-limit/pagination/result-size tests. Не выдавать чтение каталогов за готовый индексный поиск по коду и не обещать лёгкое включение готового инструмента.

**Приёмка:** Responses→GitHub mock→function output→child response в Activity/Worker, allow/deny repo/branches, traversal, unknown tool, 401/403/429, pagination/recovery и wire-count без повторного платного POST. Это новая функция, не обязательный незакрытый остаток #24.

### 9. [H-Release, по отдельному поручению] Приёмка APK и live provider

Предоставить APK с точным Build SHA/успешным соответствующим CI. На физическом телефоне принять themes/dialogs, math/Mermaid/SVG/source, cards/tables, drawer/gestures/Back/IME, reading anchor/двухступенчатый scroll, multi-photo, tools GPT/Claude, attachments/Vector Store/cache, Stop/recovery/notifications. Для точного визуального сравнения нужны original references; их нет в репозитории.

Отдельно проверить live OpenAI multi-turn, provider route/cache и фактический usage/cost в согласованных сценариях. Фиксировать устройство/Android/Build SHA, действия/результат/negative case. Mock/эмулятор/CI не заменяют live/device evidence; невыполненное — not verified.

### 10. [H-Release, по отдельному поручению] Production signing и публикация

Определить канал и signing identity обновления существующей установки. Подготовить согласованный подписанный package, проверить version/signature и фактическую ссылку публикации. CI artifact/unsigned Release не равен production release; secrets не включать в repo/logs.

### 26. [E · P2/P3, отложено] Claude Messages через OpenRouter

Возвращаться при доказанной потребности в Messages-only возможностях. При возобновлении: существующий OpenRouter key/баланс, provider Anthropic default/Auto явный; transport на уровне чата, старую signed историю не конвертировать молча. Прямой Anthropic API/отдельная оплата не входят в требование. Не обещать OpenAI-подобный resume той же генерации.

### 27. [E · P2/P3, отложено с #26] Матрица native tools Claude

До подключения проверить модель × Messages × provider × capability flag, отличие native/server/local tools и цену. `openrouter:image_generation` исключён владельцем, не внедрять как fallback.

## Планы на потом

Идеи не являются scope следующего PR. Проверять существующий код и доступность API выбранному аккаунту/transport. Сохранены прежние номера; будущие №9/№10 объединены с активными #18/реализованной #23 и отдельно не повторяются.

### 1. [P2] Версии ответа и обратимость
Альтернативы regenerate/edit и возврат к branch point; сначала pure snapshot/branch contract + DB test.

### 2. [P2] Context Inspector перед Send
Immutable snapshot model/effort/tools/attachments/IDs/context volume/background mode: что реально уйдёт.

### 3. [P2] Поиск по истории
Оценить SQLite FTS5, индексацию/удаление и переход к точному сообщению.

### 4. [P2] Повторное прикрепление вложений
UX поверх upload cache: локальный original, cached/upload-required по аккаунту.

### 5. [P2] Per-chat configuration profile
Snapshot model/tools/settings в SQLite, без незаметной смены условий при переключении чатов.

### 6. [P2] Продолжить/починить ответ
Pure policy состояний/действий: продолжить, повторить, открыть checkpoint; платный новый request явный.

### 7. [P2] Fork чата
Local snapshot до сообщения с новым chatId, сохранностью originals/shared references.

### 8. [P2] Настройки генерации по capabilities
ModelCapabilities/GenerationSettings для обоих builders; temperature отдельно от reasoning effort. System/developer prompts реализованы.

### 11. [P2] Приватность и бюджет OpenRouter
Opt-in data_collection=deny/ZDR, бюджет/отказ, resolved model. Capabilities/цены — #14/#18.

### 12. [P2] Stateless/ZDR OpenAI и rate-limit UX
Проверить store=false, encrypted reasoning/function calls, контекстные границы, 429/503/Retry-After и совместимость durable background.

### 13. [P3] Android 15/Xiaomi и долгие запросы
По поручению на устройстве: notification visibility, FGS budget, process death/reboot, история/drafts.

### 14. [P2, зависит от #26] Messages-only возможности Claude
Citations/context management/per-turn effort/deferred tools после подтверждения поддержки; без скрытого изменения истории.

### 15. [P3] Дополнительные server tools OpenRouter
Datetime/advisor/subagent/tool_search после проверки доступности и явного выбора платных функций. Image generation исключён.

### 16. [P3] Управление маршрутами OpenRouter
Дополнительные routing/privacy options по выбору. X-OpenRouter-Cache:false уже внедрён, повторной задачи нет.

### 17. [P2, направление одобрено] Долговременная память
Неизменяемая хронология + индекс выбранных фактов/заметок с первоисточником + durable stages агентной работы, без тихой обрезки истории.

### 18. [P2/P3, исследование] Расширенные OpenAI tools
Tool Search/defer_loading, programmatic calling, Skills, File Search filters/Embeddings поверх локального bridge; проверить доступность/контекст/цену.

### 19. [P2, исследование] Agents Sessions и Responses WebSocket
Сравнить с background Responses + durable checkpoints: recovery/cost/limits/context. Миграция не назначена.

### 20. [P3, опция] Голос
STT audio/transcriptions, TTS audio/speech, затем Realtime WebRTC с краткоживущим токеном.

### 21. [P3, исследование] Изображения, самостоятельные API и административные функции
Дополнительные image edit/generation параметры, отдельные API и административные возможности; административные credentials не размещать в Android.

### 22. [P2, Android-only] Jetpack Compose
Выборочно ComposeView после стабилизации: settings/themes/cards, затем лента по прототипу. Без зависимости от iOS/KMP.

### 23. [P3, отложено] KMP-ready ядро и опциональный iOS
Постепенно чистая бизнес-логика/типизированные модели/repository/view-model без Android; commonMain/iOS — отдельные решения после стабилизации и контрольного аудита.

## Карта визуальных референсов

Оригиналов в репозитории нет. Имена — карта наблюдений, не доказательство принятого UI.

| Файлы | Критерий |
| --- | --- |
| 1000269189.jpg, 1000269190.jpg | Широкая таблица в двух позициях, все колонки доступны |
| 1000269191.jpg, 1000269192.jpg | Code/nested fences, copy actions, LaTeX и $45.50, checklist/blockquote |
| 1000269193.jpg, 1000269194.jpg | Mermaid/HTML/SVG source+preview, реальные file/tool cards |
| 1000269200.jpg | Typography Markdown/code/table |
| 1000269202.jpg, 1000269204.jpg | Формулы/диаграммы/безопасный preview, статусы/actions tools/files |
| 1000269206.jpg | Gray: фон #CED8DA, шапка #8199A5; палитра реализована |

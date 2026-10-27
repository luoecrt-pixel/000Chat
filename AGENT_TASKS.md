# AGENT_TASKS — задачи и планы 000Chat (Integral LLM)

Единственный рабочий план. Правила — [NEXT_AGENT_HANDOFF.md](NEXT_AGENT_HANDOFF.md), причины прежних решений — [PROJECT_HISTORY.md](PROJECT_HISTORY.md). Номера сохранены для связи с поручениями; выполненные части исключены из активных задач.

Область `000Chat` — прямые API OpenAI и DeepSeek, затем отдельно проверяемые прямые интеграции других провайдеров. OpenRouter/Claude развивается в [форке `000Chat-OR`](https://github.com/luoecrt-pixel/000Chat-OR). Из этого плана исключены его route/cache/server-tools/Claude-only задачи; они не объявлены выполненными. В нынешнем коде OpenRouter ещё исполняется: его удаление и общие исправления составляют следующий пункт. Старые OpenRouter сообщения, metadata, локальные файлы и fingerprint v1 должны читаться после удаления транспорта.

## Текущие задачи

### 34. [P1] Общие исправления и удаление OpenRouter — **следующий**

В текущем `ModelCatalog` selectable/live провайдеров три: OpenAI Responses, OpenRouter/Claude и прямой DeepSeek. Цель реализации — оставить OpenAI и DeepSeek, сохранить общие механизмы и старые данные; дальнейшие прямые провайдеры добавлять отдельными задачами после сверки их API. Этот пункт — задача с критериями, а не утверждение, что runtime уже очищен.

**Сначала исправить общие подтверждённые проблемы.**

- **Legacy-история и SQL-конфликты.** В `ChatDatabase.migrateLegacyPrefs()` malformed JSON сейчас заменяется пустым массивом, не-object элементы пропускаются, затем raw `chat_store` удаляется и маркер выставляется. `upsertChatObject()` заменяет существующего родителя через `CONFLICT_REPLACE` и удаляет его сообщения. Валидировать весь источник до SQL-записи, сохранять raw и незавершённый маркер при ошибке, обеспечивать атомарность и защиту настоящей более новой SQL-истории. Одновременно исправить bootstrap `MainActivity.ensureChatStore()`: повторный импорт может занимать только явно помеченный приложением и всё ещё неиспользованный placeholder. Не считать обычный пустой чат placeholder по заголовку/времени/числу сообщений; любое пользовательское использование навсегда снимает право замены. Обновлять допустимый placeholder на месте без родительского `REPLACE`; rollback сохраняет возможность retry. Учесть все таблицы оригинала, включая `local_artifact_intents` и незавершённые операции DeepSeek, а не применять whole-file замену из форка.
- **GitHub credential binding.** `GitHubToolExecutor` читает текущий PAT при запросе после подтверждения записи. Зафиксировать исходный credential/identity от UI/request до executor, проверять rotation/revoke до HTTP, после подтверждения и перед повтором; другой аккаунт не получает прежнее разрешение. Секреты не писать в SQLite/model/log. Сохранить существующие one-shot write body, allow-list, unknown outcome и запрет небезопасного повторения.
- **Постоянная debug-подпись.** Workflow сейчас продолжает без `DEBUG_KEYSTORE_BASE64` и может выдать APK с ключом нового runner. До выдачи установочного APK требовать постоянный ключ и проверять реальную криптографическую подпись по полному публичному SHA-256 сертификата; сохранять public signing evidence отдельно от ключа. PR-код не должен получать постоянный ключ или выдавать установочный APK. Локальный временный ключ для тестов не является постоянной подписью. Существующую identity оригинала сначала определить с владельцем; автоматически не менять ключ, applicationId или существующую установку. Создание/перенос ключа и production signing — отдельное поручение.

**Затем удалить исполняемый OpenRouter с сохранением OpenAI/DeepSeek.**

- Убрать выбор OpenRouter и Claude через него, его настройки/сетевые credentials UI, отправку/streaming/tool-loop, сетевое получение моделей/цен/маршрутов/usage и startup/background обращения к OpenRouter. Старый выбранный provider/model не должен молча стать OpenAI/DeepSeek или вызвать исключение: показать историческое состояние и потребовать явный выбор доступного прямого провайдера перед Send. Уже выполненный запрос не отправлять повторно через другой API.
- Сначала разделить/сохранить общее: DeepSeek сейчас использует `OpenRouterResponseRuntime`, `OpenRouterResponseService`, `OpenRouterUiBridge`, `OpenRouterRuntimeRequest`, attachment DTO/store/system builder и `OpenRouterAppToolCatalog`. `ChatCompletionTransport` ещё зависит от OR-named route/result. Общие типы можно переименовать после проверки всех потребителей; не удалять их по префиксу. OpenAI Responses, Worker, files/Vector Store и его собственный recovery остаются действующими.
- Сохранить `openrouter_protocol`, исторические SQLite-столбцы/metadata/allKeys и fingerprint v1, export, originals, drafts, сообщения и tool results, без изменения хешей других провайдеров. Старые active OpenRouter checkpoints разбирать и сохранять локально с truthful partial/unknown, без сетевого resume, повторного inference или tool execution. Завершённые/неопределённые операции и файлы не терять, удалённый чат не воскрешать.
- Сохранить уже реализованные DeepSeek freeze/flush и состояния `not_started/in_flight/completed/outcome_unknown`, CAS/ownership и файловый intent ledger. Отдельно проверить Stop/ошибку SQLite/Delete/crash/reopen и отсутствие повторного инструмента/платного POST для каждого оставшегося провайдера; одного прежнего OR-теста недостаточно.

**Приёмка реализации:** реальные regression fixtures до/после исправления, повторное открытие SQLite и точное сравнение сообщений/metadata/fingerprint, protected conflict и placeholder retry/rollback/использование/удаление; GitHub rotation на барьере подтверждения и проверка фактического Authorization; проверка pin на двух разных signer fixtures и fail-closed отсутствие настройки; OpenAI Responses и DeepSeek integration/Stop/intent/GitHub safety suites; MockWebServer wire-counts и offline startup старой OpenRouter истории; Android lifecycle/FGS/process-death на согласованных scenarios. Получить полные Debug/Release push CI на одном точном code SHA, static/mutation checks и native/host evidence. Не удалять существующие тесты ради зелёного CI: транспортные OR-тесты убрать только вместе с удалённым транспортом, общие гарантии перенести на оставшиеся реальные пути. Live/device/billing/подпись установки не выводятся из синтетических тестов.

План Claude signed-thinking из PR184 и его развитие относятся к [форку OpenRouter/Claude](https://github.com/luoecrt-pixel/000Chat-OR); старые замечания использовать только после сверки актуального кода. После удаления транспорта никакая оставшаяся legacy-запись не является разрешением вернуть OpenRouter API в этот репозиторий.

### 33. [P1/P2] Замечания владельца и проверка присланных решений

- В `ChatDatabase.clearActiveResponse()` не менять `updated_at` и порядок чатов, если активного запроса уже нет. Проверить no-op и повторную очистку через SQLite, включая reopen; сохранить существующее поведение реального append/title/terminal и позднего Worker tail.
- Все темы и app-owned dialogs/buttons/cards используют согласованную контрастную палитру; scientific/rich поверхности отличаются осмысленными оттенками и мягкими рамками. Убрать обычные source toggles, сохранив точный source/copy и диагностический fallback/retry, reattach и sandbox.
- Для web age не скрывать поле и не утверждать неподтверждённое происхождение. Исследовать provider dates/cache/URL eligibility/шум по реальным ответам; отличать tool evidence от заявлений модели. Инструкции используют captured enabled-tool flags, не обещают отключённый search, не обрезают originals.

Присланные full-file replacements проверять относительно актуального кода; не откатывать bounded saved state, renderer lifecycle и ownership ради старых verifier snapshots. Это связка с #5/#14/#15/#30/#31/#32/#34, без автоматического расширения transport/миграций.

### 14. [E · P1/P2, остаток] Capabilities и reasoning-only progress прямых API

Сверять возможности модели с документацией и фактической схемой выбранного прямого API: input modalities, reasoning/effort, допустимые sampling/forced tool параметры, output/context budgets и assistant prefill. Не брать ограничения или цены OpenRouter за контракт OpenAI/DeepSeek; не предполагать, что один endpoint всех провайдеров публикует одинаковые capabilities. Ручной effort молча не снижать; missing/stale metadata обрабатывать явно.

При reasoning без visible content показывать прогресс/ожидание tools, без выдачи hidden thinking и превращения его в assistant text. Проверить provider-specific поля, Stop и пустой visible ответ, сохранение `length/content_filter/error → incomplete`. Максимум выхода и окно контекста различать; `finish_reason=length` — результат, а не настройка или разрешение автоматического нового платного запроса.

### 15. [B · P1/P2, остаток] Retention, удаление server files и request-wide limits OpenAI

Определить policy retention/`expires_after` для поддерживаемых purpose и сообщить пользователю сроки. Безопасный `DELETE /v1/files/{id}` — при последней ссылке согласно policy, с учётом других чатов, drafts/active requests, Vector Store и account binding. Cache clear не считается server delete. Remote error/expired ID/account switch не запускает скрытый upload/resend и не удаляет original.

Сохранить исходный критерий проверки `purpose` по MIME (`user_data`/`vision`) и лимитов **реально отправляемого запроса**, включая replay/history/local manifests, а не только текущий выбор. Нынешняя сумма `requestFileSizes` сама по себе полноту budget не доказывает. Сначала сверить текущую API-схему/лимиты; это acceptance investigation, не доказанный live отказ.

**Приёмка:** shared references, credential mismatch, remote failure, expired IDs, reopen; actual wire purpose/полный request budget на history replay, без тихого усечения и повторного платного вызова. Неопределённые ссылки обрабатывать fail-closed.

### 18. [D · P2, остаток #18.3] Тарифы прямых API и политика оценок

- Получать тарифы из поддерживаемого API или официального источника соответствующего прямого провайдера; хранить источник, TTL/дату и статус оценки. Отдельно сверять OpenAI/DeepSeek input/output/cache и неизвестные tool/cache-write тарифы, не выдумывая отсутствующие цены.
- Заменить захардкоженные оценки Activity/Worker на provider/model-aware policy; тарифы OpenRouter не переносить на прямые API. Оценка не заменяет подтверждённый расход, включая фактический ноль.

**Приёмка остатка:** cache read/write, tiers/tools и отсутствующие тарифы; stale/corrupt metadata, offline, 401/402/429, reopen. Новый preflight или ошибка получения цен не стирают подтверждённые предыдущие раунды и не превращают unknown в ноль. Live сверка тарифа/счёта остаётся в #9.

### 22. [D · P2, остаток] Bounded recovery resources прямых API

Проверить и ограничить очередь/ресурсы общего runtime, используемого DeepSeek; сейчас он называется `OpenRouterResponseRuntime`. Само имя не является основанием удалить его вместе с транспортом OpenRouter. Сохранить отмену и terminal/usage другого чата, не вводить fixed tool rounds.

OpenAI Responses допускает серверное retrieval/resume; прямой DeepSeek после потери SSE сохраняет partial/checkpoint и known-or-unknown cost. Подмена одного механизма другим и скрытый повтор платного POST запрещены. Сохранять уже реализованные в PR185 freeze/flush, intent recovery и Stop/Delete/Edit/notification guards, проверять delta по провайдеру.

### 5. [G · P2, остаток] Длинные научные ответы и визуальные критерии

На сопоставимых OpenAI/DeepSeek fixtures довести/проверить компактные typography/copy actions, таблицы со всеми rows/columns и признаком горизонтального scroll, реальные structured tool cards и file card/Save. Auto-preview и адаптивные колонки не доказывают весь дизайн. Tests: narrow screen/font scale, 32K boundary, invalid/incomplete/large source, reopen, sandbox. Физическая visual acceptance — #9.

На физическом телефоне принять плавность открытия/закрытия шторки и отсутствие сдвига viewport при первом touch selection (#9).

### 31. [M · P2] Остальные диалоги и видимый бренд

1. **Темы, остаток #6.** Переименование/создание/удаление чата, Vector Store, diagnostics, local files/errors, GitHub confirmation, encoding, app-owned attachment/export dialogs и другие окна приложения. Системные Photo Picker/SAF оформляет Android, а не helper приложения. Оформлять custom content **и** window/title/list/action buttons/spinner/dropdown, selected/disabled states. Один `applyDialogControlTheme(box)` не покрывает весь dialog chrome. Проверять фактические foreground/background и контраст в dark/light/gray + font scale; отсутствие WHITE текста недостаточно. Theme switch сохраняет draft/attachments/anchor/request.
2. **Бренд, остаток #25.** Убрать видимые `Astra diagnostics`, `Astra Files` и временную подпись `Astra` у активной карточки OpenAI до появления объекта сообщения. Проверить header во время генерации и после terminal. Не переименовывать `gpt-6-astra`, классы/package/БД/старые файлы/compatibility IDs.

### 32. [N · P2, остаток] Файл результата инструмента в экспорте

Сохранять безопасную связь конкретного вызова локального инструмента с именем созданного файла и экспортировать `tool → status → result file` в `.md` и `.txt`. Проверить существующий общий путь DeepSeek и экспорт старых OpenRouter сообщений; отдельный список вложений не заменяет связь файла с вызовом. Не выводить raw arguments/output, paths, hidden reasoning или секреты; не выдумывать неизвестный результат.

Проверить несколько вызовов/файлов, failed/cancelled/unknown results и реальное сохранение обоих форматов через UI поддерживаемых прямых API. Сохранить raw source, порядок сообщений и существующую OpenAI-связь по `call_id`; исторические OpenRouter результаты читаются без сетевого вызова.

### 30. [L · P2, отдельное расширение] GitHub read-only tools для OpenAI

После стабилизации bridge подключить `get_file`, `get_file_range`, `list_directory`, `get_pull_request`, `list_pull_requests`, `list_issues`, `get_workflow_runs`, `get_workflow_run_jobs` поверх текущего GitHub executor/каталога; сначала сверить фактические имена/схемы. Write-каталог в GPT не копировать.

Разделить registry/dispatch локальных файлов и GitHub: GitHub-only запрос не требует вложения. Явный opt-in, PAT только локально, repo/ref allow-list, безопасные paths, bounded results/pagination/ranges/Stop. Activity/Worker используют один configuration snapshot; привязать durable запрос к исходному OpenAI credential, GitHub credential identity и allow-policy через безопасный hash/version. Текущий executor читает актуальный PAT при каждом вызове; одной OpenAI fingerprint проверки недостаточно. После rotation/revoke/смены прав продолжение не получает иной аккаунт/расширенные права молча; raw PAT не попадает в checkpoint/model/log.

Поиск кода GitHub из дополнительного пожелания — отдельное расширение L: `search_code` сейчас отсутствует в каталоге/executor. Сначала определить поддерживаемый endpoint, repo/ref scope и полноту поиска; реализовать allow-list/rate-limit/pagination/result-size tests. Не выдавать чтение каталогов за готовый индексный поиск по коду и не обещать лёгкое включение готового инструмента.

**Приёмка:** Responses→GitHub mock→function output→child response в Activity/Worker, allow/deny repo/branches, traversal, unknown tool, 401/403/429, pagination/recovery и wire-count без повторного платного POST.

### 9. [H-Release, по отдельному поручению] Приёмка APK и live provider

Предоставить APK с точным Build SHA/успешным соответствующим CI. На физическом телефоне принять themes/dialogs, math/Mermaid/SVG/source, cards/tables, drawer/gestures/Back/IME, reading anchor/двухступенчатый scroll, multi-photo, tools OpenAI/DeepSeek, attachments/Vector Store/provider-specific cache, Stop/recovery/notifications. Для точного визуального сравнения нужны original references; их нет в репозитории.

Отдельно проверить live OpenAI/DeepSeek multi-turn, поддерживаемый provider-specific cache и фактический usage/cost в согласованных сценариях. Фиксировать устройство/Android/Build SHA, действия/результат/negative case. Mock/эмулятор/CI не заменяют live/device evidence; невыполненное — not verified.

### 10. [H-Release, по отдельному поручению] Production signing и публикация

Определить канал и signing identity обновления существующей установки. Подготовить согласованный подписанный package, проверить version/signature и фактическую ссылку публикации. CI artifact/unsigned Release не равен production release; secrets не включать в repo/logs.

## Планы на потом

Идеи не являются scope следующего PR. Проверять существующий код и доступность API выбранному аккаунту/transport. Номера сохранены для связи с прежними пожеланиями.

### 1. [P2] Версии ответа и обратимость
Альтернативы regenerate/edit и возврат к branch point; сначала pure snapshot/branch contract + DB test.

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
ModelCapabilities/GenerationSettings для прямых провайдеров; temperature отдельно от reasoning effort.

### 12. [P2] Stateless/ZDR OpenAI и rate-limit UX
Проверить store=false, encrypted reasoning/function calls, контекстные границы, 429/503/Retry-After и совместимость durable background.

### 13. [P3] Android 15/Xiaomi и долгие запросы
По поручению на устройстве: notification visibility, FGS budget, process death/reboot, история/drafts.

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
Постепенно чистая бизнес-логика/типизированные модели/repository/view-model без Android; commonMain/iOS — отдельные решения после стабилизации.

## Карта визуальных референсов

Оригиналов в репозитории нет. Имена — карта наблюдений, не доказательство принятого UI.

| Файлы | Критерий |
| --- | --- |
| 1000269189.jpg, 1000269190.jpg | Широкая таблица в двух позициях, все колонки доступны |
| 1000269191.jpg, 1000269192.jpg | Code/nested fences, copy actions, LaTeX и $45.50, checklist/blockquote |
| 1000269193.jpg, 1000269194.jpg | Mermaid/HTML/SVG source+preview, реальные file/tool cards |
| 1000269200.jpg | Typography Markdown/code/table |
| 1000269202.jpg, 1000269204.jpg | Формулы/диаграммы/безопасный preview, статусы/actions tools/files |
| 1000269206.jpg | Gray: фон #CED8DA, шапка #8199A5 |

# AGENT_TASKS — задачи и планы 000Chat

Единый рабочий план на основе требований владельца и присланного им `agent_tasks.md`. Критерии приёмки дополнены из старого handoff; это **не выполненные исправления**. Правила в [NEXT_AGENT_HANDOFF.md](NEXT_AGENT_HANDOFF.md), история решений в [PROJECT_HISTORY.md](PROJECT_HISTORY.md).

## Приоритеты и исходные данные

- **Пакет A (Задачи 1, 7, 20) — PR [#156](https://github.com/luoecrt-pixel/000Chat/pull/156) и PR [#160](https://github.com/luoecrt-pixel/000Chat/pull/160) (SHA `1fe7448`):** исправлены `HTTP 400 Invalid type for 'instructions'`, многотуровый `previous_response_id` с привязкой к SHA-256 ключа/модели/`instructions`, неразрушающий реплей `incomplete`/прерванных ответов и согласие для старых записей, индексированный чекпоинт v24, архив сырого SSE, сохранение 5+ картинок через `temp+fsync+rename` и общий bounded SSE/error декодер (прошли Debug, Release, Verify, 101 instrumentation тест и 5 стадий process-kill). **Не подтверждён live OpenAI** (остаётся для ручной приёмки по задаче 9).
- **Следующий фокус — Фаза II (короткий защитный P1 срез пакетов C, D, B):** закрыть неотложные P1-риски в существующем коде (включая находки предварительного прохода Агента 1) до запуска полного двухагентного аудита #161.
- Остальные пункты остаются открытыми до отдельных проверок. Не считать соседний зелёный CI доказательством нового UI/Android поведения. Телефон и production-подпись — отдельные действия, не доказанные зелёным CI.
- Исходные пользовательские изображения **не находятся в репозитории**. Имена и наблюдения ниже — текстовая карта референсов. Для точного визуального сравнения нужны оригиналы.
- **Android Compose и iOS разделены:** Jetpack Compose — возможная поэтапная модернизация интерфейса текущего Android-приложения (**будущий план №22, P2**), **не требующая iOS, KMP, Xcode или iPhone**. Возможная iOS-версия и KMP-ядро — **отдельный план №23, P3**. iOS не является блокером ни одной из Android UX задач F/G.

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

## Укрупнённые пакеты работ — предпочтительно один PR на связанную подсистему

**Решение владельца:** не делать один PR на каждую строку списка ниже. **Цель — семь содержательных проверяемых кодовых PR (A–G)**, плюс отдельная приёмка/публикация H, когда владелец разрешит. Это **карта исполнителю**, а **нумерованные задачи ниже остаются единственными подробными критериями приёмки**: не удалять требования ради крупного PR, не считать будущие направления автоматически одобренными. Все 27 действующих задач принадлежат ровно одному пакету.

| Пакет | Объединённые действующие задачи | Одна пользовательская цель и граница PR |
| --- | --- | --- |
| **A · готов к merge (#160)** | **1, 7, 20** | **GPT Responses: цепочка/instructions + полная история, SSE/sequence/checkpoint/terminal, сохранение всех оплаченных outputs.** Код и CI завершены в PR #160 (`1fe7448`); live-проверка у провайдера — в рамках задачи 9. |
| **B · P1** | **15, 21, 24** | **OpenAI: файлы и локальные инструменты GPT + платные включения**. Хранение/сроки/удаление remote и общих локальных файлов, полный локальный поиск/чтение из allow-list, привязка consent Web Search/Code Interpreter к фактическому JSON, исключение лишнего 4 GB контейнера и двойной загрузки файлов. |
| **C · P1 (текущий фокус Фазы II)** | **11, 12, 17, 19** | **OpenRouter Chat Completions: безопасный протокол, direct provider Anthropic через OpenRouter default, 1h/5m cache, no hidden middle-out, ошибки/лимиты, signed thinking/tool replay**. До внедрения Messages — защита текущих чатов, `openrouter_protocol.tail` и `ReasoningDetailAccumulator`. |
| **D · P1/P2 (текущий фокус Фазы II)** | **8, 13, 16, 18, 22** | **Надёжность и стоимость провайдеров:** PDF/citation metadata, generation ID, стоимость каждой генерации/инструмента/прерывания, безопасная диагностика, Stop/Delete/Edit/recovery/one terminal owner, связка `localRequestId` для оценок и телеметрии. |
| **E · P1/P2** | **14, 26, 27** | **Claude Messages через OpenRouter + модельные capability tests**, по умолчанию provider Anthropic (не Anthropic API напрямую). Отдельный сохранённый transport для новых чатов; старые Chat Completions чаты не ломать. Проверить native tools vs OpenRouter limitations до подключения новых платных замен; `openrouter:image_generation` исключён. |
| **F · UX** | **2, 3, 4** | **Навигация и чтение:** новый чат виден только после Send, устойчивый per-chat viewport, плавная шторка/жесты, исправление `chatIndex` для редактирования новых сообщений и синхронизация вложений при `SOURCE_CHAT`. |
| **G · UX** | **5, 6, 23, 25** | **Оформление и настройки Integral LLM:** единый рендеринг Claude/GPT, сохранение спанов Markwon в формулах, три темы, модельные настройки в **нижней ⚙**, общие в верхней ⚙, убрать устаревший видимый «Astra» **без миграции внутренних ID**. |
| **H · условный выпуск, не автозапуск** | **9, 10** | **Испытания установленного APK, подпись и канал публикации** — только после согласования с владельцем; без самостоятельного доступа к физическому телефону, замены debug/prod signing keys или магазинной публикации. |

**Очередь по состоянию кода и реальному риску (актуализировано 14.10.2026):**

| Фаза | Когда переходить | Пакет / смысл | Что НЕ нужно ждать |
| --- | --- | --- | --- |
| **I — P0 (завершена на CI)** | PR [#160](https://github.com/luoecrt-pixel/000Chat/pull/160) (`1fe7448`) в `Ready for review` | **A / PR #160**: проверены end-to-end Responses context/replay + SSE/indexed checkpoint + terminal/SQLite/Activity/Worker/recovery, Stop, сохранность outputs и 5 стадий process-kill. Передан независимому аудитору на merge. | KMP, Compose, новый Claude transport |
| **II — короткий защитный P1 срез (сейчас)** | Сразу после merge безопасного A | **C, D и B — только неотложная защита существующих чатов и операций:** C: #17 hidden middle-out, #19 signed/tool replay, `withVisibleTail`, `ReasoningDetailAccumulator` и side-effect ownership, связанные ошибки #11; D: #8 redaction, #22 Stop/Delete/Edit отмена `OpenRouterResponseRuntime`, защита цены раундов от сброса в `unknown`, связка `localRequestId`, критичные #18 billing/unknown vs zero; B: #15 защита общих локальных и remote файлов при Delete, #21 исключение лишнего 4g Code Interpreter на текстовых ходах и двойной загрузки файлов. | Завершения всех пакетов C/D/B, #12 default routing, #13/#16 metadata polish, #24 локального поиска GPT |
| **III — контрольный рубеж** | **Сразу после I и II, до нового крупного функционала** | **[Два новых независимых агента: полный системный аудит #161](https://github.com/luoecrt-pixel/000Chat/issues/161)** на одном зафиксированном `main` SHA. Подсистемы Android/lifecycle/SQLite/files и GPT/Claude protocols/security/cost исследуются отдельно; P0/P1 выводы перепроверяются перекрёстно; один дедуплицированный отчёт. | Всех 27 требований, будущего iOS, ручного release H; не подменять этот этап новым раундом ревью Draft #160 |
| **IV — стабилизация по итогам аудита и функции** | После отчёта и исправления подтверждённых блокирующих P0/P1 | Завершить оставшиеся C/D/B, затем **E** (Claude Messages через OpenRouter). **F/G** (Android UX) можно начинать независимо, когда не ломается общий core; Jetpack Compose может быть Android-only способом реализации части F/G без какого-либо ожидания iOS/KMP (будущее №22); небольшие изолированные UX правки допустимы и раньше рубежа III, если не отвлекают от критического пути. | Полной архитектурной миграции UI и iOS |
| **V — только при необходимости** | Отдельное решение владельца | **H**: телефон/подпись/выпуск; **будущий план №23**: KMP/iOS-ready, эксперимент с общим модулем и iOS-прототипами; Compose Android №22 от этой фазы не зависит. | Не готовить iOS ради гипотетической даты |

**Точное условие запуска этапа III:** нет незакрытого подтверждённого **неотложного** P0/P1 в текущем функционале, A принято независимым аудитором, Debug/Release + релевантные Android регрессии подтверждены для принятого code SHA, и зафиксирован стабильный baseline `main` с короткой таблицей известных ещё открытых задач и их статусов. Оставшиеся некритичные задачи можно не завершать; известные P1, если это не срочный блокер старта, отмечаются отдельно и **не выдаются** за новые находки. Если защита C/D/B затягивается, координатор заново оценивает целесообразность более раннего baseline и явно записывает исключения в #161; не переносить аудит бесконечно из-за расширяющегося backlog. **Нет назначенной календарной даты или автоматического запуска.** Полные criteria/pinned SHA/роли/формат отчёта — в #161.

**Урок независимых аудитов PR #160 — изменить процесс, а не снижать требовательность.** В подобных многофайловых правках `green CI` не поймал цепочки `delta → terminal canonical → serialized SQLite → reopen → finalization`; новые P1 становились видны после частичных исправлений. Причина затяжки — изменяющаяся граница работ и отсутствие перед готовностью полной матрицы межмодульных путей, **не** доказательство бесполезности аудита. Для следующих PR:
- **Сначала scope и критерии:** до реализации перечислить затрагиваемые контракты и позитивные/негативные сценарии `Activity Send ↔ SSE ↔ Stop/reopen ↔ Worker ↔ SQLite/upgrade ↔ tool/attachment/ledger`; определить завершённую тестируемую границу, при необходимости разделить A–G только по самостоятельному безопасному инварианту.
- **Потом авторская pre-review проверка:** таблица `контракт → фактический runtime-путь → executed test/harness → result/CI → SHA`, включая terminal/crash gap, legacy migration и mismatch/fail-closed там, где применимо. Не заменять wire/Android проверку helper-only fixture; недоказанное явно указывать. Не утверждать `Ready`, пока остались блокирующие acceptance gaps. Тестовую матрицу и итоговые доказательства хранить в **самом PR**.
- **Независимое финальное review:** аудитор приоритетно обрабатывает **готовые (не Draft)** PR с завершённым self-review. Для активного Draft допускается **адресный ранний запрос** об одном опасном контракте, но не бесконечный полный re-audit каждого промежуточного commit. После блокирующего замечания разработчик исправляет проблему, проверяет связанные сценарии; аудитор проверяет delta + соседние end-to-end paths и весь PR на финальном SHA, а не заново объявляет старые открытые фичи «новыми P1». P0/P1 не сливать без доказательства.
- **Не путать два аудита:** обычный PR-аудитор проверяет исправления и самостоятельно объединяет успешно принятые PR; **два новых агента #161** позже один раз инвентаризируют весь стабилизированный код (с перекрёстной независимой проверкой) и выдают единый отчёт. Новые большие непроверяемые PR не создавать ради уменьшения числа review.

При появлении новой подтверждённой P0/P1 регрессии пересчитать очередь; до завершения I/II не начинать рискованную смену архитектуры. Будущие iOS-эксперименты не должны блокировать текущую Android CI.

**Разумная граница размера.** **Не** объединять несвязанные функциональности просто для уменьшения числа аудитов. Если в пакете всплывает независимая миграция SQLite/протокола, риск потери чатов/секретов или объём, не поддающийся полной CI/регрессионной проверке одним PR, разрешается **разделить по самостоятельному корректному контракту**, явно указав в описании PR оставшуюся часть пакета. Не создавать один непроверяемый мегапатч. PR считается завершённым, когда пройдены **все применимые acceptance критерии** охваченных задач, либо оставшиеся явно зафиксированы открытыми без ложного «готово».

**Аудитор:** независимый агент проверяет PR целиком, отмечает в **его обсуждении** какие задачи/контракты закрыты, exact SHA, Debug/Release/Android tests, ещё не доказанные live/device границы, и сам объединяет только успешно проверенные PR согласно `NEXT_AGENT_HANDOFF.md`. Один короткий отчёт владельцу на крупный PR; не вести длинный журнал результатов в постоянном handoff. Наличие общего зелёного CI не доказывает, что функциональная ошибка исчезла. Работа с физическим телефоном, подписью и публикацией — отдельные поручения.

## Текущие задачи

### 1. Восстановить GPT: instructions и полный контекст

**Выполнено на уровне кода/mock/CI в PR #156 (`c349025`) и PR #160 (`1fe7448`):** массив в `instructions` устранён; в `GenerationInstructions.applyToOpenAiRequest()` роли `system` и `developer` передаются в `input` только при начале новой цепочки или полном локальном replay; `OpenAiInstructionChainPolicy` связывает `previous_response_id` с SHA-256 отпечатком (`instructions + model + credential`), исключая накопление дубликатов `system/developer` и использование чужого ключа/модели. Прерванные и `incomplete` ответы сохраняют все полученные `typed` элементы без дублирования текста, а старые (`pre-v23`) ответы без `openai_protocol_output` требуют явного согласия пользователя перед отправкой.

**Остаток критериев приёмки:** после merge PR #160 остаётся проверка на реальном живом провайдере OpenAI (многотуровая цепочка и смена `instructions`) в рамках ручной приёмки по задаче 9.

### 2. Новый чат появляется в списке только после первого Send

**Первый шаг:** разделить draft session и опубликованный chat в sidebar. Сейчас `newChat()` сразу зовёт `ensureChatEntry`, `ensureChatStore()` создаёт entry при старте, а `ChatDatabase.listChatSummaries()` читает все строки без eligibility filter. Внутренний ID/DB row допустим для FK/attachment ownership/draft, но не должен автоматически становиться строкой списка сессий.
**Дополнительно (из аудита Агента 1):**
- **[P1] Исправить отказ кнопки «Редактировать» для новых сообщений (`chatIndex == null`):** в `MainActivity.sendMessage()` вызывать `chat.add(sentUserMessage)` **до** `addBubble("Ты", text, true, message = sentUserMessage)`, чтобы `addBubble` находил индекс сообщения сразу без перезагрузки чата.
- **[P1] Исключить потерю вложений/ZIP при срабатывании ветки `SOURCE_CHAT` в текущем чате:** если во время подготовки файла/ZIP изменился `activityVisible` или `generation`, но `sourceChatId == currentChatId()`, синхронизировать `attachedOpenRouterAttachments` в памяти `MainActivity`, чтобы последующий `persistComposerStateNow()` не затирал сохранённое в `SharedPreferences` вложение.
- **[P2] Продолжение и повтор:** в колбэке `continueAnswer` проверять не только пустой текст, но и отсутствие прикреплённых вложений в черновике; в `sendMessage(regenerate)` разрешить повторную генерацию для сообщений, состоящих только из вложения без текста.

**Критерии приёмки:** повторные «＋»/создание сессии, ввод черновика, выбор модели/effort и добавление вложения не увеличивают список чатов; текст/originals/draft IDs сохраняются при switch/recreate. Публикация ровно один раз при первом принятом Send, включая attachment-only message; empty/disabled Send и отказ preflight не публикуют чат. Не ждать первого assistant: если request уже отправлен и API вернул 400, отправленная пользовательская сессия остаётся видимой. Existing реальные чаты не удалять; пустые provisional rows не удалять вслепую вместе с originals/active owner. Нужны DB eligibility/reopen и Activity action tests; существующий `newChatKeepsOldDraftAndStartsWithEmptyComposer` проверяет только draft, не новое listing правило.

### 3. Позиция чтения сохраняется при фоне и переключении сессий

**Первый шаг:** per-chat viewport anchor (stable message identity + offset, для длинного bubble учитывая место внутри содержимого) сохранить до switch/rebuild/фона и восстановить после async adapter commit/layout. Сейчас `restoreCurrentChat()` очищает и пересобирает adapter, `stackFromEnd=true`, `switchChat`/`onStart` вызывают restore; собственного per-chat anchor и offset restore нет. `ChatScrollPolicy` судит только по visible item indices: последний многоэкранный assistant считается «внизу» даже при чтении его начала.

**Критерии приёмки:** в длинном чате A читать середину ответа → свернуть/вернуться, A→B→A, rotation/recreate, приход background answer, обновление rich heights/темы — вернуться к тому же тексту, без прыжка к последнему сообщению; B имеет собственную позицию. Scroll following определять по фактическому расстоянию до конца, не только последнему item; фоновая delta не отнимает позицию читающего выше. Явная «Конец»/Send имеет отдельную политику и не является причиной reset при restore. По поручению владельца переход в конец допустим после **полного выключения и нового запуска**; рабочая трактовка — cold launch после завершения задачи, обычный onStop/onDestroy/rotation не означает выключение. Process/task restoration не путать с cold launch; если жизненный цикл не позволяет надёжно отличить их, зафиксировать выбор до объявления acceptance. Проверки: A/B anchors, tall last bubble, saved-state restore и отдельный cold-start case, без изменения fingerprints/full history.

### 4. Шторка: направление отпускания, 30% и плавные переходы

**Первый шаг:** выделить release/settle policy, используя направление и скорость жеста плюс относительный путь. Сейчас `FingerDrawerLayout` на UP выбирает ближайшее положение по `visibleFraction>=0.5`, velocity tracking отсутствует; animation duration 60–180 ms. Existing `FingerDrawerLayoutTest.textGestureFollowsHeldFingerAndSnapsNearestOnRelease` закрепляет старый 50% результат, это не новая приёмка.
**Дополнительно (из аудита Агента 1):** в `FingerDrawerLayout.hit()` согласовать систему координат касания (`rawX`/`rawY` против оконных координат `getGlobalVisibleRect`), чтобы в split-screen и при смещении окна безошибочно находился `horizontalChild` (горизонтальный скролл таблиц и кода).

**Критерии приёмки:** короткое осмысленное горизонтальное движение и отпускание в направлении открытия/закрытия доводит шторку до цели, даже до 30% пути; micro-jitter ниже touch slop не срабатывает. Медленный drag фиксирует цель после **30% пройденного пути от исходного состояния**: closed→right >=30% открывает, open→left >=30% закрывает; slow <30% возвращает исходное положение. Пока палец удерживается — шторка следует пальцу, плавное завершение после release. Проверить 29/30/31%, короткий flick в обе стороны, reversal/CANCEL/multi-touch и interruption анимации. Сохранить приоритет вертикального чтения, long press/selection, горизонтального scroll таблиц/code и Back/outside tap. Под «плавнее везде» проверить список UI transitions: drawer/menu/sheet/theme/response resize; согласованный easing, без скачка стартовой позиции/блокировки касаний и массового rebind на каждый SSE token. Не включать blindly RecyclerView itemAnimator (сейчас null): streaming/scroll stability отдельно. Full DOWN/MOVE/UP window tests + runtime observation плавности; одни таймеры/geometry не доказывают UX.

### 5. Оформление ответов по референсам, одинаковое для Claude и OpenAI

**Первый шаг:** зафиксировать общие presentation styles и fixtures из изображений 1–9. Причина отличий — renderer приложения: `RichResponsePanel` получает любой assistant независимо от provider; Markwon + native TextView/Button, table columns фиксированы 160 dp, math `ScientificMathText` первоначально показывает исходник и явную кнопку, Mermaid открывается через preview. `RichTextRenderer` не заменяет этот основной assistant path. При одинаковом Markdown/content у OpenAI будет тот же вид; модель не передаёт дизайн приложения Claude. Оригиналы изображений — первая партия; ещё ожидаются, итоговый визуальный scope сверить с дополнением владельца.
**Дополнительно (из аудита Агента 1):**
- **[P1/P2] Сохранение Markdown-разметки в `ScientificMathText`:** `android.text.Html.toHtml(markwon.render(node))` сейчас теряет кастомные спаны Markwon (жирный, курсив, заголовки, списки, код), а `safeTree()` теряет токены формул внутри форматированных подписей картинок (`Image`), вызывая `renderer:failed`.
- **[P1/P2] Синхронизация `ChatMessageAdapter.updateText()`:** обновлять `contentSignature` и состояние `AsyncListDiffer`, а также не терять обновление текста, вызванное сразу после `addItem()` до завершения фонового расчёта `displayPositionById`.
- **[P2] Граничные случаи парсеров:** в `ResponseBlockParser` исключить превращение JSON-хвостов в `Block.Code("json")` при инкрементальном `Session.update`, починить счётчик вложенности `<details>` и экранированный слэш перед разделителем таблицы `\\|`; в `RichTextRenderer` не склеивать абзацы на одиночных `[` и знаках умножения `*`; в `ResponseTextPages.page` не разрезать закрытые формулы пополам на границе 32K символов.

**Критерии приёмки:** компактная согласованная типографика заголовков/текста/списков/цитат, аккуратный code card с небольшим copy action вместо больших серых all-caps controls. Таблицы адаптируют ширину колонок под содержимое/экран; все rows/columns доступны, horizontal scroll имеет понятный признак, число колонок/контент не урезается. По scientific референсу нормальные завершённые формулы должны показываться оформленными в читаемом тексте, Mermaid — видимой диаграммой с доступным source; нынешняя manual-preview функция сама по себе это ожидание не закрывает. Закрытые safe blocks, engine availability, fallback/error/незавершённый stream/очень большой source и высота renderer требуют явной policy без ухудшения original bytes. HTML/SVG preview сохраняет sandbox/no untrusted scripts/no network; модельный markup никогда не выполняет tool call. Tool cards использовать реальные structured tool_trace, file card — действительный файл/Save, а не обещанную моделью ссылку. Тесты одинакового fixture через provider metadata OpenAI/OR, narrow screen/font scale, copy bytes, reopen и source safety; новая visual/device acceptance, не только наличие preview кнопки.

Дополнительное наблюдение из изображений 1–2, P2: CSV и HTML reported как text/plain. Source `LocalArtifactToolExecutor.mimeForFilename` действительно относит csv к text/plain, html попадает в fallback. Проверить metadata/Save/share как отдельный маленький fix (text/csv, text/html), не меняя UTF-8 bytes и не выдавая текст за бинарный Office/PDF. Это не причина GPT HTTP400 и не основание менять inbound file protocol.

### 6. Выбор темы: текущая тёмная, белая и серая по образцу

**Первый шаг:** добавить сохраняемый выбор цвета приложения и единые theme colors для root/header/composer/drawer/cards/dialogs/code/table/selected state/link/preview. Сейчас `MainActivity`, `RichResponsePanel`, `ScientificMathText` и styles.xml используют множество literal colors, а Markwon/WebView часть имеет собственные стили; сменить только фон root недостаточно.

**Критерии приёмки:** белая и серая темы доступны через настройку, выбранная сохраняется после restart; текущая тёмная остаётся вариантом. Серая палитра ориентируется на `1000269206.jpg` (фон около #CED8DA, шапка #8199A5), текст/иконки/состояния читаемы; светлая тема не наследует белый текст на белом фоне/тёмные status-nav gaps. Markdown/table/code/math/diagram/native dialogs переключаются согласованно. Смена темы не меняет model/effort/credentials, не начинает новый request и не теряет draft/attachments/reading anchor/live response. Theme/style assertion плюс screen inspection для всех трёх вариантов и font scale; phone pixels из JPEG не считать готовым полным design token набором.

### 7. Monotonic checkpoint и pre-ID lifecycle ownership

**Выполнено на уровне кода/CI в PR #160 (`1fe7448`):** атомарный предикат `ChatDatabase.updateActiveResponse` защищает текст, статус, `last_sequence_number` и `active_indexed_checkpoint` от регрессии и чужого владельца; `OpenAiCreateCallOwners` удерживает владение живым pre-ID запросом и эксклюзивную resume-lease между Activity и Worker; проверены `STOP_START`, `STOP`, `DELETE`, `REPLACE`, `RECREATE`, `RECREATE_STOP`, `RECREATE_REPLACE` и 5 стадий реального убийства процесса.

### 8. Диагностика: bare key в произвольном error

**Первый шаг:** regression OA-6 на synthetic bare key в error/message, включая provider error, сохраняя safe request IDs. Existing redaction тест покрывает sensitive field/Bearer/query/base64, но не этот вариант. Не использовать реальные credentials в fixture.

**Критерий завершения:** arbitrary error не публикует bare API key через file/Log.d/share; existing diagnostic fields сохраняются. Runtime change требует targeted Android/Robolectric check и общей CI пары; реальной утечки в этом аудите не наблюдалось.

### 9. Ручная приёмка установленной новой сборки

**Первый шаг:** после реализации сверить actual main, Successful Debug/Release на новом code SHA, final docs Verify и новый Debug artifact; предоставить владельцу именно исправленный APK и его Build SHA. Сборка #1282 не содержит будущих исправлений; ориентироваться на самый новый успешный code SHA. После отдельного поручения на телефон выполнить выбранные владельцем сценарии ниже и записать устройство/Android/build SHA, действия, наблюдаемый результат и воспроизводимый negative case. Без поручения не устанавливать APK и не управлять телефоном.

| Сценарий | Наблюдаемый результат / negative case | Имеющийся proof |
| --- | --- | --- |
| Formula/diagram/source | Отдельная формула, формулы в native тексте, Mermaid; source/preview/reopen; invalid LaTeX оставляет полный source, ввод с script не выполняется | API34 packaged engine + JVM/Robolectric fixture; физические размеры/fonts/scroll ещё не приняты |
| История и вложения | Оригинальное большое JPEG, provider switch/history replay, picker после switch/recreate; недоступный файл не исчезает молча, D2/history сохраняются | HTTPS mock bytes, ownership/repair tests; исходное фото владельца и настоящий Claude/OpenAI отдельно |
| Composer/UI | Модель у ввода, ⚡ quick effort и advanced имеют разные click outcomes; drawer против вертикального scroll, Back/IME/large font, Copy/share logs | Geometry/window dispatch/redaction tests; системные касания и настоящий picker/IME не доказаны |
| Stop/recovery/уведомления | Stop/late completion не восстанавливает старый request и не теряет новый draft; reopen не дублирует assistant; denied notification permission не теряет сохранённый ответ | SQLite CAS/outbox/Activity fixtures + 5 стадий process-kill на эмуляторе; физический телефон/reboot и реальная 5h сессия отдельно |
| File/cache/VS | Полная пагинация и Unicode, cache reset/expired ID без скрытого resend, поздний VS callback не меняет другой диалог | JVM/Robolectric ETag/cache/owner predicates; реальные большие provider файлы, VS dialog races и heap отдельно |

**Критерий завершения:** по выбранным сценариям есть конкретные observation records; ошибки оформлены с исходными данными и owner/negative test. При недоступности телефона записать blocked/not verified, а не success. Наблюдения не переносить на непроверенные upstream/телефоны. Далее выбрать ровно одну следующую задачу с acceptance matrix.

### 10. Production-подпись и публикация — по выбранному владельцем каналу

**Первый шаг:** если владелец поручает distribution, определить целевой канал и нужную signing identity для обновления существующей установки. Сейчас Debug APK подписан, Release APK/AAB unsigned; GitHub Releases пуст, магазинная публикация не подтверждена. Не объявлять CI artifact production release и не создавать/заменять signing identity без нужного поручения. Signing credentials не включать в исходники, логи или handoff.

**Критерий завершения:** concrete release package с согласованной подписью, проверкой package/version/signature и фактической ссылкой выбранного канала. При изменении Gradle/workflow/code выполнить новую CI пару по merge-гейту; оставленные unsigned artifacts этого не подтверждают. Этот пункт не блокирует уже завершённую интеграцию #141.


### 11. [P1] OpenRouter: точная классификация ошибок и ограничений — подтверждённый пробел

**Наблюдение по коду (12.10.2026):** `OpenRouterChatClient.upstreamError()` читает `error.type`/`code`, но не `error.metadata.error_type`, `limit_source`, `resets_at` и `Retry-After`. Для 400 изображения могут быть `image_too_large`, `invalid_image`, `unsupported_image_format`, `image_download_failed`, `image_not_found`; для 402 различаются отсутствие средств, лимит ключа и in-flight budget; 403 может означать guardrail, 429 — rate limit, 503 — отсутствие совместимого маршрута. Сейчас пользователь может получить малоинформативное upstream сообщение. Не пересылать `metadata.raw` или `flagged_input` без редактирования.

**Первый шаг:** pure mapping `status + structured metadata + safe headers → безопасная человеческая причина, retry eligibility, retry-after`; регрессии synthetic HTTP 400/402 (с/без `Retry-After`)/403/429/503 и masked 500. `Retry-After` только как информация или явно разрешённая стратегия; **никаких скрытых повторных платных запросов**. Сохранить подробности только в защищённой диагностике и проверить редактирование API-ключей. [Ошибки OpenRouter](https://openrouter.ai/docs/api-reference/errors-and-debugging), [лимиты](https://openrouter.ai/docs/api-reference/limits).

**Уже обработано — не переделывать:** `OpenRouterChatClient.consume()` выбрасывает ошибку из верхнеуровневого `error` внутри HTTP 200 SSE (A1), `SseEventParser` поддерживает комментарии и многострочный `data:` (A3); финальный пустой usage chunk не добавляет текст и `usage` трактуется как snapshot, а не дополнительный платёж (A2). Добавить тесты именно на **первый/единственный** error SSE и повторяющийся `finish_reason`, не заводить эти случаи как отсутствующие функции. Клиент сейчас сам использует `stream:true`; non-streaming 200+error нужно учитывать только если появится non-streaming path.

### 12. [P1] OpenRouter Claude: прямой Anthropic через OpenRouter, кэш и сохранность подписанного префикса

**Исправленное решение владельца (12.10.2026 17:49):** `Anthropic` как **provider внутри OpenRouter** — маршрут Claude **по умолчанию**, не Anthropic API напрямую. Использовать существующий `OpenRouterClaudeRoute.ANTHROPIC` (`provider.order=["anthropic"]` и `allow_fallbacks=false`), с OpenRouter API key/балансом. `Auto` оставить **только явным выбором** пользователя; если Anthropic недоступен, показать причину, не переключать на Bedrock/Vertex молча. Исправить фактические текущие defaults: `OpenRouterClaudeRoute.fromPreference` использует `AUTO` для пустого prefs, а `MainActivity` читает `"auto"` в route spinner/при Send. Миграция: старый явно сохранённый выбор пользователя `auto` уважать, только отсутствие выбора переводить в новый default `anthropic`. При новой интеграции /messages проверить `provider` в wire-body и закрепление маршрута/совместимости signed thinking. Direct Anthropic **API endpoint** не внедрять.

**Наблюдение:** `OpenRouterCachePolicy.configureRequest` выставляет top-level `cache_control` всем `anthropic/*` с включённым кэшем, а старый default `OpenRouterClaudeRoute.AUTO` разрешает партнёрские маршруты. После переключения нового default на direct остаётся проверить как **явно выбранный Auto**, так и direct. По текущей [документации OpenRouter о кэше](https://openrouter.ai/docs/guides/best-practices/prompt-caching) top-level automatic caching поддерживается только у прямого Anthropic; explicit breakpoints имеют иные правила. Это **несоответствие условий маршрутизации**, а не доказанный live HTTP failure или отсутствие cache hits.

**Первый шаг:** unit request-body matrix `AUTO/ANTHROPIC × 1h/5m/off × tools/system/history`; проверить fallback/route и provider cache usage через mock; при наличии отдельного live-результата сравнить поведение с провайдером, не выдавая mock за подтверждение live. Не посылать несовместимые default 5m + top-level 1h. Различать cached read/write по provider usage, без предположения о цене каждого endpoint. При 400 из-за несовместимости signed thinking с pinned provider показать предложение выбрать совместимый маршрут или начать новую ветку без незаметного удаления исходной истории (A10/B8). Существующий `OpenRouterProtocolCodec.checkBinding` — защитный механизм; проверить его тестами при смене model/key/tools/route/system.

### 13. [P1] OpenRouter: PDF-аннотации и реальные пределы медиа

**Наблюдение:** `OpenRouterChatClient` собирает `content/reasoning_details/tool_calls`, но не переносит входящие `assistant.message.annotations` (parsed PDF и citation payload) в `openrouter_protocol.tail`; `OpenRouterResponseRuntime.annotations()` создаёт только локальные `local_file` карточки. На OCR/не-native пути это может приводить к **повторному платному парсингу PDF** на следующем turn (C2). `OpenRouterAttachmentPreflight` проверяет byte limits и размеры изображений, но явно не ограничивает суммарные **количество картинок и страниц PDF** с учётом истории (B11).

**Первый шаг:** mocked 2-turn PDF OCR scenario: ответ с `annotations[].file.hash/content` сохраняется **без потери оригиналов и без исполнения недоверенных данных**, возвращается в точном поддерживаемом форматировании; отрицательный сценарий — отсутствующий/подменённый hash. Сверить с текущей [спецификацией PDF](https://openrouter.ai/docs/guides/overview/multimodal/pdfs). Отдельно counted media/pages preflight на 20/21 картинках, пограничных моделях и документе с большим количеством страниц; не объявлять лимиты одинаковыми для всех моделей/маршрутов. При `native/auto/mistral-ocr/cloudflare-ai` явно сообщать пользователю о возможной отдельной тарификации OCR; текущий UI уже имеет selector.

### 14. [P1/P2] Claude 5.x: возможности модели, reasoning и видимость неполного ответа

**Наблюдение:** `OpenRouterReasoningPolicy` ограничивает effort для **статического списка** Opus/Sonnet/Fable, но не читает `reasoning.mandatory` или `supported_parameters` из API метаданных. Каталог моделей меняется. `OpenRouterChatClient` получает `reasoning_details`, но основной текстовые обновления идут только из `delta.content`; ответ с одними размышлениями может выглядеть молчащим (B1/B5). `OpenRouterFinishPolicy` **уже** переводит `length/content_filter/error` в incomplete, так что B6/B9 не считать отсутствующим обработчиком.

**Первый шаг:** запросная матрица с синтетическими `/models` capabilities: mandatory vs optional reasoning, supported effort, отсутствие недопустимых `temperature/top_p/top_k`, запрет неподдерживаемого forced `tool_choice` (текущий цикл шлёт auto), отсутствие assistant prefill в Claude 5.x. Для tool-only reasoning chunks проверить явное «идёт генерация», доступную диагностику/индикатор без показа скрытых или запрещённых thinking details. Не имитировать `content` из недоверенной `reasoning`. Тест `length` с пустым visible content и отказ `content_filter` обязателен. Параметры и названия моделей дополнительно сверять с `/models`, не объявлять свойства неподтверждённых 5.5-алиасов фактом.

### 15. [P1/P2] OpenAI: срок хранения файлов, удаление и безопасное повторное использование

**Наблюдение:** `OpenAiFileUploadCache` корректно разделяет account/credential/bytes/purpose и умеет `invalidate/clear`; **локальная очистка кэша не удаляет серверный файл OpenAI**. В коде есть reactive eviction при file-not-found, но политика `expires_after`, информирование пользователя об upstream retention и remote delete при удалении чата пока не подтверждены.
**Дополнительно (из аудита Агента 1):**
- **[P1] Защитить общие локальные файлы при `MainActivity.deleteChat()`:** когда два чата ссылаются на один локальный путь (например, через `findLocalFileByFileId` или перенос вложения), `deleteChat(id)` сейчас безусловно удаляет файл с диска. Перед удалением проверять отсутствие ссылок в других чатах (`listAllLocalFilePaths`), черновиках (`allComposerPaths`) и активных запросах (`OpenRouterResponseRuntime.localFilePaths`), а сравнение путей в `LocalAttachmentReferences.isOrphanOldEnough` выполнять через `canonicalFile`.
- **[P2] Пагинация Vector Store:** в `VectorStorePagePolicy.parse` читать `last_id` через `JsonProtocol.nonBlank` (сейчас Android `optString("last_id")` превращает JSON `null` в буквальную строку `"null"`).

**Первый шаг:** определить явную user-facing retention/deletion policy и ownership между чатами/повторно используемыми file IDs. После Delete chat нельзя безусловно удалять remote или локальный файл, если он нужен другому чату; нельзя обещать удаление на сервере после одного local cache clear. Mock server delete/expiry/error с локальными оригиналами и запретом автоматического платного повторного анализа. Верифицировать file input limits и `purpose=user_data/vision` по MIME и request-wide bytes отдельно (D2/D3). **Уже закрыто:** `OpenAiFileId` принимает `file-` и `file_` (D1); `MainActivity` умеет локально скачивать `container_file_citation` (D4) — нужны regression fixtures, не переписывание с нуля.

### 16. [P2] OpenRouter: HTTP generation ID, PDF/web-tool metadata и управляемая диагностика

**Наблюдение:** есть `OpenRouterGenerationReconciler` и учёт `/generation?id=`, а `OpenRouterChatClient` достаёт generation ID из JSON SSE; **fallback из заголовка `X-Generation-Id` не виден**. Это может терять сопоставление расходов, когда генерация оборвалась до JSON frame (A6). `OpenRouterServerToolCatalog` отправляет `openrouter:web_search`/`web_fetch`, но не выбирает конкретный engine, а входящие server annotations/citations явно не выделяются (C3–C5).

**Первый шаг:** mock `X-Generation-Id` без SSE ID, когда headers пришли до disconnect; безопасная запись в уже существующий per-round ledger, а `GET /generation` только как read-only reconciliation и с request ownership. Отдельно mock server-tool annotations/citations на assistant, совместимость изображения с tools и выбор engine/маршрута — не делать вид, что native engine всегда поддерживает обе операции. `debug.echo_upstream_body` допустим исключительно в **явно разрешённой dev-сборке** с synthetic/контролируемыми данными; никогда не включать по умолчанию, не записывать в logs/CI реальные keys/messages/file paths.

### 17. [P1] Исключить скрытую OpenRouter middle-out compression до отправки истории

**Подтверждено кодом и документацией, но не live-сокращением:** `OpenRouterChatClient.requestMessage()` отправляет `plugins` только для выбранного PDF engine, **не** указывает `{"id":"context-compression","enabled":false}`. Согласно [OpenRouter Message Transforms](https://openrouter.ai/docs/guides/features/message-transforms), endpoints с контекстом до 8192 токенов **по умолчанию** используют middle-out compression; администратор может также включить default plugins на уровне аккаунта. Это потенциально **молча исключает середину разговора**, включая вложения и важный подписанный префикс, вопреки требованию владельца «полная история без тихого обрезания». Для нынешних основных Claude это не свидетельствует о фактическом сокращении; риск возникает при смене модели/маршрута, в том числе будущими роутерами.

**Первый шаг:** добавить явное отключение компрессии в сформированном wire JSON одновременно с выбранным `file-parser` PDF plugin, не теряя его; mock request-body tests для малого context window, tools/history с image/file, всех cache modes и provider routes. При переполнении контекста должна приходить **явная ошибка без скрытого повторного платного вызова**, а не потеря частей беседы. Учитывать, что принудительная plugin policy уровня workspace может перекрывать request override: проверить effective behavior безопасным способом и показывать ограничение. **Не включать** server-side compact/edit/context-management, пока нет отдельного согласия пользователя на изменение истории. [Plugin precedence](https://openrouter.ai/docs/guides/features/plugins/overview).

### 18. [P2] Разделить подтверждённую общую цену и дополнительную стоимость server tools/tiers

**Наблюдение по коду:** `OpenRouterUsageSnapshot` читает токены и `usage.cost`; `OpenRouterChatClient.accumulateUsage()` суммирует стандартные поля, но **не сохраняет разложение** `usage.cost_details.server_tool_cost`, `upstream_inference_cost`, `iterations[]`, `server_tool_use` и `service_tier`. При текущих включаемых `openrouter:web_search`/`web_fetch` это снижает объяснимость расходов на инструменты; **не доказано**, что существующий итог `cost` занижен или неверен. Восстановление общей фактической цены из /generation уже есть (см. задачи 9 и 16).
**Дополнительно (из аудита Агента 1):**
- **[P1] Исключить сброс подтверждённой цены прошлых раундов в `unknown`:** `OpenRouterChatClient.requestMessage()` сейчас вызывает `onRoundObserved(roundIndex, null, null)` до `validateRequestBytes` и до HTTP-вызова. Если новый раунд падает или останавливается до получения SSE `id`, строка раунда с `generation_id = NULL` заставляет `recomputeOpenRouterTotalsInDb` стереть подтверждённую сумму всех предыдущих раундов в `unknown`.
- **[P2] Перенос догруженных токенов и обновление UI после сверки:** в `RequestStopEventPolicy.enrich()` переносить `input_tokens`, `cached_tokens`, `output_tokens`, `reasoning_tokens` из `request_accounting`; в `recomputeOpenRouterTotalsInDb()` суммировать токены раундов; вызывать `OpenRouterUiBridge.changed()` после `OpenRouterGenerationReconciler`; в `RequestEventFormatter` использовать `JsonProtocol.nonBlank` вместо `optString`.

**Обязательная часть: актуальные цены без выпуска новой версии APK.** Перед отображением предварительной стоимости загружать по HTTPS цены модели из [GET /api/v1/models](https://openrouter.ai/docs/api/api-reference/models/get-models) и подходящего провайдерского endpoint из [GET /api/v1/models/{author}/{slug}/endpoints](https://openrouter.ai/docs/api/api-reference/endpoints/list-endpoints), с ограниченным TTL-кэшем/временем проверки и явным статусом «оценка на дату». При выполнении получать **фактический** `usage.cost`, `cost_details`, `service_tier` и, где есть ID, [GET /api/v1/generation?id=…](https://openrouter.ai/docs/api/api-reference/generations/get-generation) для total_cost, включая обрывы и несколько tool rounds. Цену server tools / подписок/записи кэша, которую публичный metadata API не возвращает, не выдумывать: показать «тариф неизвестен» и явный выбор. Изменения тарифов на сервере видны без обновления APK; при изменении **схемы API** код, возможно, всё же придётся обновлять.

**Тесты:** fixture двух и десяти раундов с `usage.cost` + `cost_details` + `server_tool_use` + `iterations`, pinned Anthropic provider vs user-selected Auto, cached-token read/write и отдельные provider tools/OR tools, стартовая оценка vs подтверждённая сумма, округление валюты, испорченные/устаревшие metadata, 401/402/429, offline и отмена. Суммарную стоимость **не складывать второй раз** с входящими в неё cost details. Отсутствие подтверждённой цены — **«неизвестно»**, не $0. Продолжать показ цены в истории даже после закрытия приложения. [OpenRouter Server Tools](https://openrouter.ai/docs/guides/features/server-tools/overview).

### 19. [P1] Контракт OpenRouter tool calls: безопасный replay, роли, права и SSE — **следующий**

**Новые основания из присланного владельцем аудита 12.10.2026 (NEW-01–09, 15–19) и прохода Агента 1:**
- `OpenRouterChatClient.consume()` без запрета принимает `delta.role` в assistant message; присваивает новый `tool_call.id` тому же индексу, конкатенирует полную повторённую `function.name`; выполнение `tool_calls` допускается при `finish_reason="stop"` или отсутствии причины, а tool calls исполняются до сохранения checkpoint раунда.
- **[P1] Повреждение `openrouter_protocol.tail` в `OpenRouterProtocolCodec.withVisibleTail`:** при падении на 2+ раунде `withVisibleTail` дописывает в конец `checkpointTail` полный склеенный текст всех раундов (дублируя текст прошлых раундов), а при пустом `saved` добавляет невалидный `{"role":"assistant","content":""}`.
- **[P1/P2] Вечное срабатывание `onProtocolWarning` и баг `ReasoningDetailAccumulator`:** после однократной смены настроек `checkBinding` удаляет `reasoning_details` только в памяти, не помечая запись в БД (предупреждение всплывает на каждом следующем ходе); в `ReasoningDetailAccumulator` чанк с `index: 0` без `type` и следующий чанк с `type` создают два ключа и вызывают `IOException("Ambiguous reasoning block identity")`, а при склейке по `id` без повторения `type` разрывается подпись.
- **[P2] Отмена и ресурсы инструментов:** `LocalFileTools.execute` и `GitHubToolExecutor.execute` ловят исключения отмены внутри себя и возвращают `{"ok":false}` вместо прерывания; `GitHubToolExecutor` создаёт незакрываемый `OkHttpClient` на каждый запрос, использует `path.contains("..")` вместо проверки сегментов пути и теряет `interrupt` в `Thread.sleep`; в `MainActivity.githubConfirmationText` превью `create_or_update_file` обрезано до 3000 символов и из-за приоритета `+` теряется вопрос `"Разрешить?"`; `FileBackedDataUrl`/`FileBackedText` при двух вызовах `sha256(history)` читают каждый файл с диска до 8 раз и 3 раза гоняют Base64 в пустой поток.

**Первый шаг:** mock SSE + fault injection: повторный/изменённый tool ID и name, duplicate ID разных индексов, `role=system`, вызов отсутствующего в разрешённом каталоге инструмента, tool call при `stop`/missing finish, crash после побочного эффекта и до checkpoint (NEW-01–09); исправить `withVisibleTail`, `checkBinding`, `ReasoningDetailAccumulator`, проброс отмены и закрытие ресурсов в `LocalFileTools`/`GitHubToolExecutor`, а также кэширование хеша в `FileBackedDataUrl`/`FileBackedText`. НЕ включать автоповтор платных запросов.

### 20. [P1] OpenAI Responses: целостность потоков, текста и оплаченных файлов

**Выполнено на уровне кода/CI в PR #160 (`1fe7448`):** внедрён общий ограниченный декодер SSE/ошибок, сборка по `(output_index, content_index)` без потери блоков при `refusal.done`, сохранение всех сгенерированных изображений (без `take(4)`) через `AtomicGeneratedImageFile` (`temp+fsync+rename`) с карточкой статуса для каждого изображения и сохранением сырого потока `OpenAiRawSseArchive` при лимитах памяти.

### 21. [P1] OpenAI: согласованность платных инструментов, полного анализа файлов и доверенных источников

**Наблюдение и находки Агента 1:**
- Настройка `Web Search` создаётся как `chk("web")` с default=false, но request builder читает `prefs.getBoolean("web", true)` — пока настройка не сохранена, UI и реальный HTTP расходятся (OA-N22).
- `file_analysis_tools` по умолчанию **true**; при подготовленных файлах включает Code Interpreter даже если отдельная настройка `code_interpreter=false`, и всегда посылает `memory_limit="4g"` (OA-N23), хотя стандартный tier OpenAI 1 GB дешевле.
- **[P1] Лишняя активация 4 GB контейнера на текстовых ходах и двойная загрузка файлов:** `OpenAiFileAnalysis.prepare()` включает в `containerFiles` даже `image` (загруженные с `purpose="vision"`) и `pdf` из всей истории чата, активируя Code Interpreter `4g` на всех последующих текстовых ходах и передавая `vision` IDs в контейнер; текстовые файлы загружаются в `/v1/files` дважды (оригинал при выборе и `.utf8` с `+.txt` при Send); `.doc/.docx/.ppt/.pptx` при выключенном анализе отправляются как неподдерживаемый `input_file`.
- **[P1/P2] Экранирование и EXIF в `OpenAiLocalContextEncoder`:** текст файлов вставляется между `<file name="..."> ... </file>` с неполным экранированием имени и без экранирования `</file>` внутри содержимого (OA-N36/37); вызов `classifyLocalFile` без `Context` пропускает нормализацию EXIF-ориентации JPEG; в `StructuredDocumentExtractor` из `.docx` попадают удалённые правки `w:delText` и поля `w:instrText`, а в `.xlsx` `URI.resolve` падает на пробелах и делает N+1 транзакций `SqliteLargeTextQuery`.

**Первый шаг:** один snapshot настроек на Send, выравнивание UI/request defaults; платные tools никогда не включаются неожиданно отдельным флагом; ясная видимая opt-in настройка «Анализ файлов с Python (OpenAI)», размер контейнера по умолчанию 1g или выбранный пользователем, исключить `image`/`pdf` из `containerFiles`, убрать двойную загрузку в `/v1/files` и починить `OpenAiLocalContextEncoder` / `StructuredDocumentExtractor`.

### 22. [P1/P2] Сбой/Stop/учёт: единая модель надёжного ответа OpenAI и Claude

**Не путать provider execution с UI streaming.** OpenAI background Responses `background:true` поддерживает server-side continuation, retrieve/poll и resume по `response_id/sequence_number` после reconnect; **ни текущий OpenRouter Chat Completions, ни отдельный OpenRouter /messages не документируют такое же гарантированное возобновление того же provider-side inference при разрыве SSE**. При потере соединения сохранять частичный ответ, цель задачи, уже законченные инструменты/их side-effect idempotency, последний безопасный checkpoint и known/unknown cost, не помечать как завершённый, **не запускать второй платный запрос автоматически**.

**Аудит из файла и находки Агента 1:**
- **[P1] Остановка `OpenRouterResponseRuntime` при `deleteChat()` и `editMessage()`:** оба метода сейчас вызывают только устаревший `cancelOpenRouterRequest()`, не вызывая `OpenRouterResponseRuntime.cancel(...)` — генерация и инструменты продолжают работать в фоне. Удалить мёртвые поля `openRouterActiveCall`/`githubToolExecutor` из `MainActivity` и перевести 4 теста в `MainActivityLifecycleTest` на реальный `OpenRouterResponseRuntime`.
- **[P1] Связка `localRequestId` для оценок 👍/👎 и телеметрии:** в `MainActivity.sendMessage()` в `recordEffortMetric` и `recordRequestEvent` передаётся заголовок `x-request-id` вместо `localRequestId`, из-за чего `recordFeedback` не находит запись; в `ChatDatabase.upsertCurrentChatInDb` и `recordFeedback` учитывать `role`, а при обновлении путей вложений сбрасывать `content_hash`.
- `OpenRouterResponseRuntime` при fatal/failure передаёт `usage=null` в terminal при уже оплаченных ранних tool rounds (NEW-32); `OpenRouterGenerationReconciler.maxLookups=4` недостаточно для десятков rounds (NEW-33); неконтролируемый cached thread pool (NEW-34–37).
- **[P2] Уведомления, `Retry-After` и `OpenAiCancelReconciler`:** в `ResponseNotifications.finished()` очищать `answer_notifications` при отсутствии `POST_NOTIFICATIONS` и вызывать `finished` в `finishBackgroundRecovery()`; в `sleepBackgroundRetry` не урезать `Retry-After` до 30 с через `jitteredDelayMs`; в `OpenAiCancelReconciler` добавить паузу перед `GET`; в `OpenRouterResponseService` не убивать параллельные запросы вызовом `stopSelf(startId)`.

**Наблюдаемость:** отдельные user statuses «генерация идёт», «связь потеряна — ответ сохранён», «получение результата», «неполный ответ», «готово»; provider outage отличать от локального Wi-Fi. Существующую важную функцию **«Уведомлять о готовом ответе» сохранить** в общих настройках приложения (задача 23).

### 23. [P2] Разнести настройки между нижней шестерёнкой модели и верхней шестерёнкой приложения

**Последнее уточнение владельца по скриншоту 12.10.2026 16:46:** на главном экране есть **две разные ⚙**. **Нижняя**, справа от ⚡ effort и названия модели — `MainActivity.showResponseSettings()`; в ней разместить **все model/API-specific функции**, разделённые на категории *для текущего API* (Claude / OpenRouter или GPT / OpenAI): усилие мышления, режим и формат ответа, JSON Schema, поддерживаемые параметры, инструменты, server tools, attachments/PDF engine, кэш Claude 1h/5m/off и model/provider-specific route, context control.

**Верхняя ⚙** в шапке вызывает `MainActivity.showSettings()`: в ней оставить ключи и аккаунты, общие настройки приложения (внешний вид, уведомления, диагностика), GitHub credential/allow-list, локальные файлы и другое app-level администрирование. Синхронизировать `SecureStore.key()` и защитить `setSecret()` от сбоев Keystore.

Удалить из UI две кнопки «Настройки уведомлений Android / Xiaomi» и «Фоновая работа: настройки приложения», не удаляя системный запрос `POST_NOTIFICATIONS`; убрать из UI вводящую в заблуждение галочку «Продолжать ответ, если Astra закрыта», **но не удалять** рабочие OpenAI background Responses, Android foreground service/WorkManager для OpenRouter или восстановление. Вывести `openai_auto_compaction` в **явный opt-in** (попутно обновив строковый ассерт в `tools/verify_v4_17_0.py`).

### 24. [P1/P2, пожелание владельца] GPT: выборочный локальный поиск и чтение файлов как у Claude

**Подтверждённый функциональный разрыв:** для OpenRouter/Claude уже есть `LocalFileTools` с `file_list`, `file_info`, `file_search`, `file_read_range`, `file_table_stats` и `LocalTextPageIndex`. Для OpenAI Responses добавить разрешённые GPT-функции `file_list/file_info/file_search/file_read_range/file_table_stats` с существующими реализациями и безопасным `file_id`, передавать initially только краткие descriptors/metadata, а в `function_call_output` — лишь найденные snippets/ranges/results. Реализовать полный Responses tool loop с `call_id`, сбором streaming arguments до JSON, учётом reasoning/protocol items, bound sizes, ownership/Stop/recovery. При регистрации локальных артефактов чата (`db.listLocalFiles()`) нормализовать кодировку/структуру через `OpenRouterAttachmentStore.classifyLocalFile`, а в `LocalFileTools.search` увеличить буфер `carry`, чтобы не обрезать левый контекст сниппета.

### 25. [P2] Очистить лишний пользовательский бренд «Astra» без опасного переименования данных

**Решение владельца: новый публичный бренд — `Integral LLM`.** В `MainActivity.kt`, `ResponseNotifications.kt` и `BackgroundResponseWorker.kt` менять только видимые пользовательские строки (название в launcher, уведомлениях, экспорте, заголовках) на `Integral LLM` или нейтральное «Модель отвечает…», «Продолжается генерация…», «Локальные файлы», «Фоновые ответы». **Не переименовывать без миграции** `AstraApplication`, `AstraDiagnostics`, `AstraResponseModel`, `astra_chats.db`, SQLite/SharedPreferences keys, notification channel IDs, file paths, package name, JSON schema name и реальные model slugs `gpt-6-astra`.

### 26. [P1/P2, одобрено владельцем] Claude Messages через OpenRouter — второй transport

**Решение владельца 12.10.2026:** добавить `POST https://openrouter.ai/api/v1/messages` через **существующий OpenRouter key и кредиты**, не создавать прямой Anthropic API/оплату. **По умолчанию provider Anthropic внутри OpenRouter** (`order: ["anthropic"]`, `allow_fallbacks:false`), а **Auto — только по ручному выбору**. Для новых Claude чатов после проверок выбрать Messages, уже существующим Chat Completions чатам оставить исходный формат без невидимой трансформации подписанной истории.

### 27. [P1/P2, обязательная проверка] Claude: выяснить реальные ограничения native инструментов через OpenRouter Messages

Проверить матрицу (модель Claude 5.x × /messages × pinned Anthropic provider × включённый capability flag) для нативных инструментов Anthropic, серверных аналогов OpenRouter и создания графики/анимаций через код согласно полному описанию. Пользователь **не хочет `openrouter:image_generation`** — не внедрять и не предлагать как автоматический fallback.

## Планы на потом

Эти идеи не являются автоматически назначенным scope следующего PR. Проверять существующий код перед реализацией.

### 1. Версии ответа + обратимость
Сейчас regenerate/edit — точечные действия над линейным чатом. Следующий уровень — сохранять альтернативы и уметь вернуть чат к состоянию «до этого ответа». Первый шаг: pure domain contract для snapshot/branch point + DB test, без UI.

### 2. Context Inspector перед отправкой
Показывать по запросу компактный «что реально уйдёт»: модель, effort, tools, attachments, примерный объём контекста, background mode, выбранные IDs файлов. Первый шаг: immutable preflight snapshot из уже существующего sendMessage().

### 3. Поиск по всей истории
Для длинной локальной истории нужен мгновенный full-text search с переходом прямо к сообщению, а не только список чатов. Первый шаг: оценить FTS5 на messages(text) и тесты по индексации/удалению/поиску.

### 4. Переиспользование вложений
Контентный upload reuse уже реализован в `OpenAiFileUploadCache`. Возможный следующий UX — явное повторное прикрепление локального оригинала и понятное состояние cached/upload-required по выбранному аккаунту.

### 5. Per-chat configuration profile
Snapshot настроек модели/инструментов на уровне отдельного чата в DB, чтобы переключение между чатами не меняло незаметно условия эксперимента.

### 6. «Продолжить / починить ответ» как отдельный UX
Описать состояния response и разрешённые пользовательские действия («Продолжить», «Повторить», «Открыть checkpoint») как pure policy.

### 7. Fork / ветвление чата
MVP: «Ветвить отсюда» на выбранном сообщении создаёт независимый local snapshot до этой точки с новым `chatId`.

### 8. Настройки генерации, зависящие от источника и модели
**Системный промпт реализован в #98 (v1)**: глобальное поле, отделённое от существующего Developer instructions; PR #156 перенёс system/developer из отвергаемого массива instructions в отдельные роли input; PR #160 закрепил многотуровую цепочку и восстановление полного контекста. **«Градус» = temperature (sampling/randomness), а не reasoning effort**: первый шаг — описать pure `ModelCapabilities`/`GenerationSettings` contract для обоих request builders, затем regression matrix.

### 9. Расходы отменённых запросов
Показывать фактическую сумму отменённого запроса только при доказанном provider usage/cost; если сумма неизвестна — явно «неизвестно», не ноль.

### 10. Более компактная организация настроек
Конкретизирована действующей задачей 23: **нижняя ⚙** открывает `showResponseSettings()`, **верхняя ⚙** открывает `showSettings()`.

### 11. [P2] OpenRouter бюджет, отказ, приватность и модельные возможности
Безопасные подсказки для `402/403/429/503` после задачи 11; настройка приватности `provider.data_collection=deny` / ZDR по выбору пользователя; `/models` как источник возможностей и точный resolved `response.model`.

### 12. [P2] OpenAI: stateless reasoning, billing/rate и границы контекста
Проверка режима `store=false`/ZDR и переноса encrypted reasoning/function_call; безопасная UX-классификация `429`/`503` и `Retry-After`.

### 13. [P3] Android долгие запросы — отдельно от уже работающего onTimeout
На реальном Android 15/Xiaomi проверить видимость уведомления, последствия исчерпания суточного FGS budget, убийства процесса и доступность истории/черновика.

### 14. [P2, после базовой интеграции] Дополнительные Messages-only возможности Claude
Дополнительные возможности `/api/v1/messages` (citations, opt-in `context_management.edits`, per-turn effort, deferred tools) после завершения базового транспорта в задаче 26.

### 15. [P3] Дополнительные OpenRouter server tools — маленькие опции, большие расходы по согласию
Кандидаты `openrouter:datetime`, advisor/subagent/tool_search; `openrouter:image_generation` исключён по решению владельца.

### 16. [P3] Управление OpenRouter: маршруты, response caching, доступ и безопасность
Параметры маршрутизации, opt-in `X-OpenRouter-Cache` и повышенная приватность по явному выбору пользователя.

### 17. [P2, направление одобрено владельцем] Расширять долговременную память и выборочное извлечение для сложных задач
Раздельные уровни: неизменяемая полная хронология + индексируемые выбранные факты/заметки с точными ссылками на первоисточник + сохраняемые промежуточные результаты/этапы агентной работы.

### 18. [P2/P3, исследование] OpenAI Responses: расширенный выбор инструментов без потери контекста
Исследование Tool Search (`defer_loading`), Programmatic Tool Calling, Skills, фильтров File Search и Embeddings после реализации задачи 24.

### 19. [P2, исследование] OpenAI Agents API и Responses WebSocket для долгих задач
Сравнительная архитектурная матрица для существующего OpenAI background Responses + local durable checkpoints против Agents Sessions и Responses WebSocket.

### 20. [P3, продуктовая опция] Голос OpenAI: транскрипция, озвучивание и Realtime
Поэтапное расширение: `/audio/transcriptions` STT, `/audio/speech` TTS и Realtime WebRTC с короткоживущим токеном.

### 21. [P3, исследование] OpenAI: изображения, самостоятельные API и административные функции
Исследование расширенных параметров image edit/generation; не класть admin/management ключи в Android-клиент.

### 22. [P2, Android-only] Jetpack Compose — выборочная модернизация Android UI без iOS
Самостоятельное направление модернизации интерфейса Android (`ComposeView` для настроек, тем, карточек и прототипа ленты чата) после стабилизации P0/P1, без зависимости от iOS или KMP.

### 23. [P3, отложенная архитектурная возможность] KMP-ready ядро и опциональная версия для iOS
Поэтапное выделение чистой бизнес-логики и протокольных кодеков без Android-зависимостей с перспективой выноса в `shared/src/commonMain` (Kotlin Multiplatform) после стабилизации P0/P1 и разбора аудита #161.

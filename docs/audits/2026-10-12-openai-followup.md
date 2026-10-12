# OpenAI и интерфейс: проверка и план исправлений

Последнее уточнение: 2026-10-12 11:18 +03:00. Исходный аудит проверен 2026-10-12, 09:36 +03:00. База: main `41a02fa8fc83a25c8c5dcf400b4c5c228cefe86d` после merged #142. Runtime/code/build/tests совпадают с `cceacdd8c392b2a69338f2313c6db137d210f307`. Исходный аудит менял только Markdown; исправления приложения ещё не внесены. Подробный план перенесён сюда при очистке документации. Лимит этапа — 40 минут.

## APK и разница во времени CI

Для установки брать последний **Successful Build Android APK с доступным артефактом**, сейчас [#1282](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698456), [Debug ZIP](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698456/artifacts/11716107416). Распаковать и установить `app-debug.apk`. Новые docs-only runs со статусом Skipped не создают другой APK. Release APK/AAB unsigned, это другой пакет для distribution.

По job timestamps и полным логам:

| Этап | #1243 / run 37998337416 | #1282 / run 38175698456 |
| --- | --- | --- |
| Весь job | 15:02 | 10:48 |
| Test, lint and build | 4:37 | 3:42 |
| Android lifecycle instrumentation, включая подготовку и сборку | 9:41 | 6:16 |
| Собственно device tests | 34 теста, около 5:35 | 39 тестов, около 3:42 |
| Gradle cache | Restored | Restored |

Основная измеренная разница — emulator/instrumentation stage. В #1282 проверок больше. Отсутствие cache в #1243 логами не подтверждается. Причина различия скорости runner/emulator до уровня CPU contention не установлена; не выдавать её за доказанный факт. #1243 — старый code `fe0b5b2597226f5690b502dc462474d07a25292b`; #1282 — актуальный code SHA выше. Debug artifact `11716107416`, ZIP SHA-256 `adc5f47445aeeb468ed2ef5e8662d0f8f29b8d82f1311e59f00f0967a43c0cc9`, expiry 17 октября 00:27:30 МСК.

## Список гипотез до исходной проверки

Сначала составлены восемь групп гипотез; найденные варианты внутри группы перечислены ниже отдельно, чтобы не путать их с восемью установленными ошибками.

| № | Проверяемая гипотеза | Результат этого прохода |
| --- | --- | --- |
| 1 | Activity/Worker теряет или дублирует assistant при handoff | Local request ID в обоих finalizers корректен; terminal CAS один победитель. Но same-request checkpoint может откатываться: OA-2. Есть pre-ID cleanup риск: OA-5 |
| 2 | Старые callbacks после Stop/new Send меняют новый request | Expected local request ID/CAS и immutable UI generation есть; pure ownership tests прошли. Полный process-death/phone callback сценарий заново не выполнен |
| 3 | SSE неверно завершает ответ, пропускает delta или теряет terminal text | Reader/reducer/cursor tests прошли: DONE/EOF без terminal не Success, terminal body восстанавливает полный текст, duplicate/gap различаются. Это не проверка всех реальных server events |
| 4 | previous_response_id теряет реплики/инструкции | OA-3: после foreground incomplete/failed остаётся старый in-memory cursor; OA-4: credential/TTL не участвуют в пригодности chain. Instructions повторно отправляются, но есть OA-1 |
| 5 | Чужой/expired file_id или preflight теряет originals/draft | File cache привязан к bytes/name/MIME/credential/purpose, eviction и preflight ownership покрыты существующими Android/Robolectric tests. Нового подтверждённого дефекта в этом проходе нет; suite здесь заново не запускался |
| 6 | Stop неправильно отменяет completed response или приписывает usage | Actual reconciler проверен local HTTP: 404 cancel → GET completed usage, 401 → без GET/attribution. Existing DB cancellation suite отдельно; synchronous cancel по документации — закрытие соединения |
| 7 | Effort/model/compaction меняются незаметно или создают invalid request | ModelRequestPolicy tests сохраняют effort и не объявляют mode для unknown model; configuration_update исключает auto-compaction в builder. Совместимость конкретной модели на upstream не доказана; основной schema risk OA-1 |
| 8 | Resume сбрасывает deadline или диагностика раскрывает данные | Persisted 5h origin и silent-socket timeout tests прошли; будущий timestamp fail-closed. Bearer/query/base64 redaction есть; bare key внутри error остаётся: OA-6 |

## OA-1 — подтверждённый HTTP400 instructions, P0

`GenerationInstructions.openAi()` возвращает JSONArray, если есть system prompt **или** presentation instructions. Последние включены по умолчанию в `presentationInstructionsForRequest()`. MainActivity помещает результат непосредственно в `body.instructions`.

Актуальная официальная [Create response schema](https://developers.openai.com/api/reference/resources/responses/methods/create.md), раздел **Body Parameters**, описывает `instructions: optional string or null`. Раздел возвращаемого **Response** на этой же странице разрешает string/array. Именно request и response schemas здесь различаются; наличие массива в Response не доказывает его acceptance в Create.

Host-проба выполнила настоящий `GenerationInstructions` и JSON serialization: default presentation и system+developer дают `JSONArray`; developer-only — `String`. Existing `GenerationInstructionsTest` проверяет ожидаемый массив, поэтому зелёный тест не проверяет официальный Create contract.

**Теперь подтверждено:** владелец прислал реальный HTTP400 от 2026-10-12 10:14:03.633 МСК: instructions expects string, got array. Независимый paid/live запрос агент не повторял; причина подтверждена actual builder probe. Сохранить distinct system/developer/presentation смысл допустимым request transport и добавить strict request-contract regression. Старый offline тест массива не доказывает API compatibility.

## OA-2 — same-request checkpoint может откатываться, P1

`ChatDatabase.updateActiveResponse()` обновляет text/status/sequence с WHERE `id=? AND active_request_id=?`. Сравнения с уже записанным sequence нет. Foreground, Activity resume и Worker могут писать для **одного** local request. `onStart` вызывает асинхронный WorkManager cancel и сразу начинает Activity resume; cancellation сама по себе не является fencing token для уже выполняющегося callback.

SQL-проба с тем же SET/WHERE воспроизвела: writer A сохраняет prefix/sequence 20, запоздавший writer B сохраняет prefix/sequence 10, итог — 10 и более короткий text. Другой request ID запись не меняет. Это подтверждённый дефект SQL boundary; фактическая Android scheduling interleaving здесь не запускалась.

Последствие — регресс durable partial/cursor и лишний replay после process death. Полный canonical terminal response обычно восстанавливает текст; постоянная потеря final answer во всех случаях **не** доказана. Нужен monotonic/owner fence вместе с атомарностью text+cursor и явной семантикой равных sequence; не достаточно `max(sequence)` при записи старого text. Android DB regression должен записать 20 → 10, а также terminal → late in_progress и Stop/new request.

## OA-3 — foreground failed/incomplete пропускает последнюю реплику в следующем запросе, P1

`MainActivity` меняет in-memory `currentResponseId` после terminal только для `completed`. При failed/incomplete assistant добавляется в chat с provider `openai`, но chain predicate проверяет только ненулевой cursor и provider последнего assistant. Если уже был completed ответ R1, после incomplete R2 следующий text-only запрос **до reload чата** отправляет `previous_response_id=R1` и только новый user input: user R2 и partial assistant R2 не входят в payload.

Source-derived scenario проверен с guard assertions на точные ветви builder/finalizer. Это не исполнение всего Activity. DB terminal writer может сохранить R2; UI snapshot не перезаписывает response metadata, поэтому reload способен изменить результат. После disconnected foreground fallback DB также может сохранить прежний cursor. Не объявлять локальную историю удалённой: проблема именно в outbound context.

Нужен единый chain-eligibility contract: cursor соответствует последней включённой реплике и допустимому terminal state; иначе полный typed replay, включая historical files. Regression: completed → incomplete/failed/disconnected → следующий Send, как без reload, так и после reopen, без скрытого resend/платного retry.

## OA-4 — chain не учитывает смену account/key и исчезновение response, P2

Settings сохраняет новый API key без invalidation текущего response cursor. Chain predicate не учитывает credential identity, возраст или server availability; resume берёт актуальный key из SecureStore. File upload cache имеет credential boundary, а response cursor — нет. Stored Responses по документации имеют default retention 30 дней.

**Подтверждён кодовый путь:** смена key/project не отключает previous_response_id; отсутствующий ID не переводит initial-send builder на full replay. **Не воспроизведён live:** cross-project 404/403/expired response. Не любой поворот ключа ломает chain: ключи одного проекта могут сохранять доступ. Нужен controlled missing/foreign response scenario с понятным отказом/explicit replay, полными оригиналами и сохранением нового draft; автоматический повтор generation без решения пользователя не вводить.

## OA-5 — pre-ID cleanup после 120 секунд может отозвать ещё живой owner, P1 для воспроизведения

`onStop` сохраняет foreground stream, если background enabled и response ID ещё неизвестен. `resumeBackgroundResponses` при возврате очищает active owner без response ID и с `age>120_000L`, не проверяя живой `currentCall`. Если valid request ещё ждёт response.created, его последующие checkpoint/finalize уже не проходят CAS. Это отдельный путь раньше общей 5h boundary.

**Подтверждено чтением и source assertions:** обе ветви существуют, живой call в cleanup не учитывается. **Не выполнено:** полный Activity/local server interleaving. Следующий Android test: delayed response.created, Stop/Start с persisted started_at старше 120s, затем terminal; ответ должен сохранить owner/assistant. Отделить живой pre-ID request от действительно orphaned process-death state; не вводить idle cutoff.

## OA-6 — bare API key в error не редактируется, P2

Настоящий `AstraDiagnostics.sanitizedValue` скрывает sensitive field names, Bearer, query credentials и base64 data URI. Однако значение `error="Rejected credential sk-proj-SYNTHETIC_AUDIT_VALUE"` сохраняет synthetic key без изменения. MainActivity передаёт exception/provider error text в `error`/`message`.

Host-проба выполнила exact sanitizer method, извлечённый из production file; Android filesystem/Log.d wrapper не выполнялись. Это подтверждённый пробел redaction, **не наблюдавшаяся утечка реального ключа** и не утверждение, что OpenAI всегда возвращает полный key. Нужен regression для bare key/token в произвольном error и сохранение безопасных diagnostic IDs. Не заносить настоящий key в fixture, отчёт или логи.

## Выполненные проверки и границы

- Host JDK17/Kotlin2.0.21, JUnit4.13.2, JSON20250517, OkHttp/MockWebServer4.12: **46 tests PASS** из `OpenAiSseStreamReaderTest`, `ResponseEventReducerTest`, `ResponseStreamSequenceCursorTest`, `GenerationInstructionsTest`, `ResponseDeadlinePolicyTest`, `ResumableResponseRequestTest`, `ActiveResponseWritePolicyTest`, `ModelRequestPolicyTest`, `ResponseCheckpointPolicyTest`. Использованы реальные production pure files; exact exception declaration отдельно извлечена из Android-dependent client file.
- Actual `OpenAiCancelReconciler` local HTTP probes: **PASS** для 404 → terminal usage и 401 → no retrieve; без OpenAI network generation.
- Instructions type и exact production sanitizer probes: **воспроизведены** OA-1/OA-6 на уровне wire serialization/function.
- SQLite SET/WHERE contract: **воспроизведён** OA-2; source-asserted scenarios OA-3/OA-5 выполнены как модели ветвей, не как Android lifecycle.
- Source review: local request ownership, cache credential/byte identity, error eviction, request body/effort/compaction и persisted deadline. Existing Android/Robolectric CI proof остаётся на code SHA; новый Android/SDK run в этом проходе не выполнен.
- Official Create Markdown получен напрямую с developers.openai.com; проверялся request section отдельно от response section. [Text/instructions](https://developers.openai.com/api/docs/guides/text), [conversation state](https://developers.openai.com/api/docs/guides/conversation-state), [background](https://developers.openai.com/api/docs/guides/background) также сверены. Sources checked 2026-10-12; схема может измениться.
- Платные provider calls, физический телефон, production signing/publication не выполнялись. Зелёные mocks не закрывают эти границы.

Воспроизведение SQL boundary: после `setActiveResponse` записать `(request=A,text=new,sequence=20)`, затем `(request=A,text=old,sequence=10)` через `updateActiveResponse`; текущий итог — old/10. Negative case: `expectedRequestId=B` не меняет A. Для следующего исправления запускать реальный `ChatDatabase` Robolectric/Android test, а не считать host SQL probe достаточным Android proof.

Доступный повтор baseline pure suite в полном Android build окружении: `gradle --no-daemon :app:testDebugUnitTest --tests 'com.example.openaichat.OpenAiSseStreamReaderTest' --tests 'com.example.openaichat.ResponseEventReducerTest' --tests 'com.example.openaichat.ResponseStreamSequenceCursorTest' --tests 'com.example.openaichat.GenerationInstructionsTest' --tests 'com.example.openaichat.ResponseDeadlinePolicyTest' --tests 'com.example.openaichat.ResumableResponseRequestTest' --tests 'com.example.openaichat.ActiveResponseWritePolicyTest' --tests 'com.example.openaichat.ModelRequestPolicyTest' --tests 'com.example.openaichat.ResponseCheckpointPolicyTest'`. Новые OA regressions ещё предстоит добавить; существующие 46 tests не доказывают исправление этих находок.

## Поручения владельца и критерии приёмки

Редакция 18: владелец 2026-10-12 около 10:24 МСК поручил **проверить новые наблюдения и записать план**, реализацию он передаст агенту, после неё нужна отдельная проверка. Ранее merged #141–#143/CI не закрывают новые требования. Первый приоритет — реальный GPT blocker OA-1, затем независимые UI slices; OA-2…OA-6 и distribution остаются pending. Исходный [аудит восьми гипотез](2026-10-12-openai-followup.md) описывает состояние до нового owner log; статус OA-1 выше теперь сильнее. Controller/Compose/accounting rewrite автоматически не добавлять.

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


План перенесён сюда из handoff по уточнению владельца. Реализация остаётся pending; текущий проход очищает документацию. Скриншот `1000269214.jpg` содержит поручение сократить handoff/убрать неактуальные информационные файлы и оставить PR владельцу для merge; это не дополнительный референс дизайна. Нового архива или справочника не создаётся.

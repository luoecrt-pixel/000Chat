# OpenAI: гипотезы, проверки и следующий срез

Проверено 2026-10-12, 09:36 +03:00. База: main `41a02fa8fc83a25c8c5dcf400b4c5c228cefe86d` после merged #142. Runtime/code/build/tests совпадают с `cceacdd8c392b2a69338f2313c6db137d210f307`. Этот проход меняет только Markdown; исправления приложения ещё не внесены. Лимит этапа — 40 минут.

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

## Список до проверки

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

## OA-1 — несовпадение instructions с документированной Create schema, P1

`GenerationInstructions.openAi()` возвращает JSONArray, если есть system prompt **или** presentation instructions. Последние включены по умолчанию в `presentationInstructionsForRequest()`. MainActivity помещает результат непосредственно в `body.instructions`.

Актуальная официальная [Create response schema](https://developers.openai.com/api/reference/resources/responses/methods/create.md), раздел **Body Parameters**, описывает `instructions: optional string or null`. Раздел возвращаемого **Response** на этой же странице разрешает string/array. Именно request и response schemas здесь различаются; наличие массива в Response не доказывает его acceptance в Create.

Host-проба выполнила настоящий `GenerationInstructions` и JSON serialization: default presentation и system+developer дают `JSONArray`; developer-only — `String`. Existing `GenerationInstructionsTest` проверяет ожидаемый массив, поэтому зелёный тест не проверяет официальный Create contract.

**Установлено:** расхождение outgoing JSON с документированной схемой. **Не установлено:** настоящий HTTP 400 — платный вызов не выполнялся, сервер может поддерживать больше опубликованной схемы. Сначала определить корректный wire transport с сохранением distinct system/developer roles, затем request-contract regression; не молча склеивать/терять инструкции ради теста. Для окончательного разрешения документационного расхождения нужна отдельная разрешённая live проверка либо явный authoritative contract.

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

## Решение по плану / checkpoint

Следующий один узкий кодовый срез — **OpenAI request contract и полная история после неуспеха** (OA-1/OA-3, с eligibility cases OA-4). Затем ownership/monotonic checkpoint и delayed pre-ID lifecycle (OA-2/OA-5); OA-6 — отдельный короткий privacy regression. В ручной acceptance 4.17 добавить эти наблюдения. UI features/radar/Compose/controller/accounting redesign сейчас не важнее этих рисков.

Все исправления остаются pending. Перед runtime/test change нужны acceptance matrix, воспроизведение на целевой границе и новый push Debug+Release CI по каноническому handoff; APK #1282 не содержит исправлений OA-1…OA-6. Текущий Markdown PR требует только final-head Verify. Конкретные final head/Verify/merge SHA записываются в описании этого PR, чтобы не делать self-referential docs commit ради собственного SHA.

# Дополнение 19.10.2026 — ограниченная проверка присланных решений

Актуальный отчёт — [PROPOSED_FIX_REVIEW_2026-10-19.md](PROPOSED_FIX_REVIEW_2026-10-19.md). Он приоритетнее старых статусов ниже. Входные файлы проверены по SHA256; два узких source review и isolated JVM counterexamples показали, что full-file replacements применять нельзя. Новые пожелания сохранены в AGENT_TASKS #33/#32.

Safety ветка получила code commit `3f82418`: oneShot=true. Реально выполнено 24 targeted tests:23PASS/1attachment failure; retry4/4, checkpoint4/4, classifier5/5. Отдельно два counterexample probe присланных классов ожидаемо падают, existing accumulator test проходит. Safety verifier_v4 всё ещё требует старое имя helper — pending адаптация с mutation tests. UI targeted run ещё идёт; его итог смотреть в отчёте. UI и safety по-прежнему НЕ интегрированы.

После завершения этого короткого окна снова остановиться. Продолжение — только по новому поручению; не принимать старое «исправление oneShot ещё не внесено» ниже за текущее состояние. Никаких main/merge/release/device действий.

---

# Точка продолжения — работа приостановлена владельцем 18.10.2026

Владелец попросил: «Сохранись и приостановись». После этой точки не продолжать реализацию или тесты без нового поручения. Это временный checkpoint незавершённой работы, а не замена AGENT_TASKS.md/NEXT_AGENT_HANDOFF.md и не утверждение готовности.

## Рабочие деревья и порядок продолжения

- `/workspace/000Chat-stability`, ветка `fix/request-and-attachment-safety`: отдельный safety PR первым.
- `/workspace/000Chat`, ветка `fix/interaction-telemetry-design`: отдельный UI PR после safety; затем перенести safety через merge/rebase с аккуратным разрешением MainActivity.kt/ChatDatabase.kt. Сейчас обе ветки начинаются от `d51516151d560687b00d5e349fb6bcabfc5c314d`, UI ещё НЕ содержит safety.
- Не сливать в main и не переводить Draft→Ready без прямого поручения владельца. Никаких PR пока не создано. WIP сохранение не является release/build approval.

Перед возобновлением перечитать AGENTS.md/NEXT_AGENT_HANDOFF.md/AGENT_TASKS.md, проверить актуальные GitHub main/PR/CI и этот checkpoint. Для остановленной работы один фиксированный baseline уже просмотрен двумя независимыми агентами; не начинать этот аудит заново без причины. Итоговый единый audit report и обновление активного плана ещё не оформлены.

## Что проверено до реализации

GitHub: репозиторий `luoecrt-pixel/000Chat`, открытых PR на старте не было. Baseline main `d51516151d560687b00d5e349fb6bcabfc5c314d`. Runtime/test/build файлы идентичны `ec0c823039d990b02a05308d63309bd180912847`; разница только AGENT_TASKS.md/PROJECT_HISTORY.md. Проверены реальные CI на этом code SHA: push attempt1 Debug `39127645765` и Release `39127645722`, оба success. Их результаты НЕ доказывают нынешний WIP.

На baseline реально выполнены `python3 tools/verify_handoff.py`, `verify_v4_17_0.py`, `verify_implementation.py`: PASS. Для WIP лишь git diff --check; новые статические/Android проверки ещё нужны.

## Safety: подтверждённые проблемы и незавершённые исправления

1. **Скрытый платный POST retry OpenRouter.** Client создаёт `StreamingJsonRequestBody(body)` с `oneShot=false`; OkHttp повторяет HTTP408 и HTTP503 Retry-After:0 внутри execute. Реальный MockWebServer baseline-red получен в `OpenRouterPaidPostSafetyTest`: 4 tests, 2 failures (408,503), 0 skipped; ambiguous disconnect и explicit successful tool continuation PASS. **Production исправление `oneShot=true` ещё НЕ внесено** из-за остановки. Первое действие после разрешения продолжить: исправить и получить green, сохранив explicit tool continuation.
2. **OR partial checkpoints теряются.** Runtime.onTextDelta вызывал OA updateActiveResponse без sequence, чей CAS требует incoming text == saved text. Новый owner/provider/nonterminal/increasing-cursor метод `checkpointOpenRouterResponse` и Runtime integration подготовлены. OpenAI CAS не менялся. `OpenRouterRuntimeCheckpointTest` содержит delayed SSE→SQLite→MainActivity prefix до terminal, replacement, Stop/archive, cursor/terminal/OA protection. **Ещё не запускался.**
3. **Поздний download исторического вложения пишет в изменившийся index / возвращает удалённое image.** Подготовлены `HistoricalAttachmentDownloadTarget`, immutable identity CAS, bool result и уникальные fresh download paths, Main download/UI guards; `HistoricalAttachmentDownloadTest` (первоначально 11 tests) на normal/reopen и Remove/Reorder/Replace/Edit/Delete/switch/destroy/UI repair. **Работа остановлена во время доводки**: проверить наличие `persistComposerStateNow()` перед success restoreCurrentChat (защита свежего <300ms draft/attachments), завершённость optional uiOwner predicate в вложенных async image/text/PDF preview helpers и их final callback guards. Некоторые эти правки уже в файле, нельзя считать завершёнными без просмотра diff/компиляции/tests. Warning Kotlin2.0 о data class private constructor exposing copy в HistoricalAttachmentDownloadTarget — рассмотреть обычный class/consistent visibility.
4. **Immutable signed-thinking HTTP400.** Конкретная причина приведённого владельцем отказа не доказана без исходного provider envelope. Normal signed content/reasoning replay по чтению не обрезается. Добавлена узкая exact-error классификация в OpenRouterErrorClassifier с guidance вернуть совместимые исходные model/route либо новый чат, без удаления истории/платного повтора. Новые tests raw string/object/outer envelope, unrelated errors/HTTP500, redaction **ещё не запускались**. Это не реализация всего backlog #12.

Последний единственный Gradle session87003 завершён exit1 на ожидаемом baseline-red, активных сборок нет. Локальный production/test compile был успешен на промежуточной версии safety WIP до последних attachment edits. XML red proof: `app/build/test-results/testDebugUnitTest/TEST-com.example.openaichat.OpenRouterPaidPostSafetyTest.xml` в stability tree; сырые логи в публичный отчёт не переносить.

## UI: подготовлено, но НЕ проверено сборкой/tests

- FingerDrawerLayout: не перехватывает horizontal code/table контейнеры даже на краю, активное выделение/long press/editable/embedded controls; соблюдает child disallow; симметричный slow threshold20%, fling280dp/s; CANCEL/multitouch/animation interruption/scrim semantics.
- ChatMessageLayoutManager интегрирован в MainActivity. Touch focus noneditable selectable TextView не должен прокручивать tall bubble; keyboard/editable/explicit scrolling сохранены. **Причина первого скачка пока гипотеза:** baseline/fixed runtime test обязан воспроизвести отличие, тест ещё не исполнен.
- Ordering DB: неизменившийся snapshot при switch не меняет chats.updated_at; real append/title/terminal продолжают менять порядок. Unsent draft timestamp специально не используется для sort. 5 DB + 1 actual Activity switch/settings/newChat tests подготовлены.
- Semantic palette code/math/diagram/tool/file/log/error, контраст вычислением >=5.58:1, мягкие асимметричные рамки. Основные Main buttons/cards/labels переведены с жёстких dark colors. AppThemeViews общий helper buttons/native dialog chrome/spinners, нужно проверить реальные selected/disabled states и отсутствие смены spinner selection.
- Обычные math/Mermaid/SVG source toggles убраны, raw copy/export сохранены; failed/timeout renderer показывает полный исходник и требует явный retry после reattach. Scientific theming ограничен app-owned CSS interval, не replace model source. Sandbox/CSP/offline assets/JS separation не ослаблены.
- Лог: active model/progress в framed preview; terminal accounting вместо progress; expanded preview становится заголовком, события chronological с latest только внизу; убраны зелёный EXACT и кнопка «Закрыть». Ошибки в status сохраняются рядом с расходом. Нужно проверить empty/active/no-events и все Activity/Worker completion paths; последняя root правка SessionPresentationTest ещё не компилировалась.
- В верхнем status после model picker теперь только подтверждение выбора, модель остаётся в нижнем picker. Picker и settings имеют одинаковую высоту40dp; проверить narrow/font scale и фактическую геометрию.
- Под каждым assistant response добавлены Copy/Save TXT, source читается из нового adapter snapshot. Export helper вынесен, новое имя integral_message. Нужны проверки exact raw source, сохранения/поздних lifecycle callbacks.
- Уведомления о мышлении заменены silent/minimum/deferred системным индикатором фонового соединения для OR service и OA Worker. **Полностью скрыть обязательный foreground-service indicator Android при сохранении длительной фоновой работы нельзя.** Result channel сохранён; конечному владельцу эту границу честно сообщить. Tests channel/notification подготовлены, не исполнены.

UI targets: FingerDrawerLayoutTest, ChatMessageLayoutManagerTest, ChatDatabaseChatOrderingTest, MainActivityChatOrderingTest, SessionPresentationTest, AppThemePaletteTest, RichTextRendererTest, RichResponsePanelTest, ScientificRendererRegressionTest; соседи MainActivitySettingsTest/MainActivityScrollLifecycleTest/MainActivityLifecycleTest/ChatDatabaseChatSummaryTest/StopDraftAndArtifactsTest/ChatDatabaseActiveFinalizationTest/RequestEventFormatterTest. Android instrumentation ScientificRendererDeviceTest обновлён, не исполнен.

## Веб-пожелания и оставшиеся требования владельца

Сначала были изучены GitHub/план/handoff, затем замечания. Всё перечисленное ниже сохраняется до доказанного закрытия:
- Не мешать выделению/горизонтальной прокрутке кода шторкой; плавное лёгкое открытие/закрытие; первый selection не двигает экран.
- Все кнопки/карточки корректны в dark/light/gray; более осмысленные разнообразные цвета и мягкие рамки scientific/rich блоков без обычной кнопки исходного вида.
- Убрать выбранную модель из верха; progress/usage в особом log preview; expanded latest не дублируется; без зелёного/Close; ошибки дополняются accounting.
- Размер settings рядом с model picker и компактная рамка; действия под ответами; стабильный порядок сессий по фактическому изменению; только result alerts.
- Разобраться с age (НЕ скрывать), шумом search/fetch, URL eligibility/domain/cache/full-page claims; отличать реальные tool calls от заявлений модели.
- Разобраться с immutable thinking HTTP400 без порчи подписанной истории.

Web search/fetch в приложении — server-managed OpenRouter tools (`OpenRouterServerToolCatalog`). Собственных age calculation/HTML-fetch/cleanup нет. Причины конкретных неверных age/кешированных страниц и BBC/Reuters/URL restrictions **ещё не установлены**; цитированные самоотчёты модели не доказательство. Не придумывать provider limitations, не скрывать age и не обрезать originals. Native annotations/citations сейчас известный backlog #16 (PDF #13); новый transport не добавлять самовольно. Надёжный следующий шаг: сверить актуальный upstream contract и происхождение дат/annotations на контролируемом материале, не публикуя raw messages/keys. Live provider/billing/телефон не проверялись.

## Окружение и дальнейшие проверки

Установлены `/workspace/toolchains`: JDK17.0.20.1, Gradle8.9, Android SDK35 rev2, build-tools35.0.0, platform-tools37.0.1. В checkout wrapper нет. Перед Gradle `. /workspace/toolchains/android-env.sh`; сохраняет proxy/CA TLS и inherited JAVA_TOOL_OPTIONS. SHA256 JDK/Gradle сверены. Не печатать env/секреты. 8GiB RAM: один Gradle build одновременно, `--no-daemon --max-workers=2`.

После разрешения продолжить: закончить safety→target tests→релевантный полный Debug/Release→CI exact SHA→отдельный PR evidence matrix. Затем интегрировать safety в UI, разобрать compile/runtime failures и реальные acceptance gaps, выполнить static/mutation/targeted/Debug/Release/Android checks, оформить второй PR. Не claim «всё исправлено» на текущем WIP; не делать merge/публикацию/подписание/физический телефон без отдельного поручения. Audit report должен разделять inspected code/tests от реально выполненных harness и known backlog от новых подтверждённых P1.

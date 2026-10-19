# Продолжение 19.10.2026 — завершение acceptance после9e6fbb9

Remote9e6fbb9 Release [39208525990](https://github.com/luoecrt-pixel/000Chat/actions/runs/39208525990), push attempt1 — **success**. Debug [39208526375](https://github.com/luoecrt-pixel/000Chat/actions/runs/39208526375), push attempt1 — JVM/lint/build success, isolated10PASS, Android152cases/1failure/3expected fixture skips. Новые DialogThemeDeviceTest и actualSend progress проверки не падали; единственное падение в OpenAiLocalToolsIntegrationTest:433 — native InsetDrawable при ожидании semantic input surface. Полный workflow red, последующий process-recovery harness не выполнен.

Это выявило production пробел AppThemeViews: EditText менял только текст/hint и tint, не получая inputBg поверхности. Response Settings сохранял native Material background; accent tint мог красить и custom fill. Code64c202f задаёт явную inputBg surface со stateful focus/default/disabled border, без tint; native editing/padding/selection/type/listener сохранены. Исходная device-проверка сохранена и дополнена no-tint assertion. Новый regression использует native Canvas во всех3 темах.

Реально выполнено на64c202f: SessionPresentation8/8PASS и **полный Debug755/755PASS**,0errors/skips,113 XML suites; static verify_handoff/v4/implementation PASS. Финальная локальная команда `testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin` **BUILD SUCCESSFUL за7m55s**: все755 JVM tests, lint, APK и Android Kotlin test compile завершены. Нового локального full Release не запускали; его вместе с Debug выполняет новая пара CI. Новая code-граница требует новой пары CI: прежний Release не объявляется её доказательством. По прямому поручению владельца ожидание Debug/Release CI оставлено ему; агент не ожидает conclusion и не запускает rerun. Новая пара стартует финальным `[ci]` push. Активный snapshot checkpoint упрощён до текущих фактов; прежние записи не потеряны — Git и нижняя историческая часть этого отчёта сохранены.

---

# Дополнение 19.10.2026 — #32/#33 и интеграция, окно 05:57–06:12 UTC

**Фактическая остановка06:11UTC:** final production Debug и Debug unit-test compile PASS; полный testDebugUnitTest и lintAnalyzeDebug были запущены, но не завершились до лимита. Локальные Gradle daemon/worker остановлены; exit1 от принудительной остановки не считать установленной новой регрессией. Полного final Debug green, завершённых lintDebug/assembleDebug/AndroidTest compile и local Release на последнем code нет. Последний runtime/test code —5b6a7ad; этот финальный docs checkpoint запускает новую пару CI через `[ci]`. Работа снова приостановлена, продолжение только по новому поручению.


Владелец явно принял прежние решения и разрешил 10–15 минут на продолжение. Main повторно d515161, open PR нет. Safety Debug39201447117 и Release39201447163 на c918ed9, push attempt1 — **оба success**.

В UI выполнены whole-dialog `.md`/`.txt` из меню выбранного чата, snapshot history+active в одной SQLite transaction, UTF-8 snapshot→SAF и очистка по cancel/nullURI/ошибке запуска/потере lifecycle. Export не переключает видимый чат и не затрагивает draft. Formatter включает только user/assistant raw text и разрешённые безопасные metadata, не сериализует prompts/reasoning/raw arguments/tool output/credentials. Неизвестный статус остаётся неизвестным; active marker и same-request partial/terminal dedupe покрыты отдельными тестами.

Footer теперь 👍/👎/Копировать/Повторить; single-message TXT удалён из footer/обоих long-press. Retry проверяет актуальный adapter, row/request identity в UI и SQLite, latest user turn и draft; historical показывает объяснение без Send. Latest positive test достигает реального sendMessage missing-key preflight, без платного API. Copy читает свежий source.

Интеграция: UI b7201f7 объединён с safety c918ed9 коммитом51cb852. Main/DB auto-merge, конфликт только checkpoint разрешён сохранением актуальной записи. Независимый read-only review подтвердил сохранение safety download/preview методов и CAS; новых подтверждённых blockers не нашёл. После просмотра укреплено совпадение request_id и lifecycle сообщений о результате экспорта. Дополнительно active без нового chunk не теряет явную незавершённую пометку.

Новые исходники тестов: DialogConversationExporterTest9, MainActivityConversationExportTest2, SessionPresentationTest7. Это ещё не утверждение PASS: общий Gradle testDebug/lintDebug/assembleDebug/AndroidTest compile/testRelease/lintRelease выполняется. На06:04UTC static verify_handoff/v4/implementation PASS, v4 mutation18negative+positive и implementation7negative+positive PASS. Старые33/49PASS не заменяют новую интеграционную проверку. Финальный результат записывается при остановке.

Промежуточный51cb852 push запустил Debug39207589120 и Release39207589053 (attempt1, ещё in_progress); после последних code-уточнений им потребуется новая пара на финальном head. Main/PR creation/Ready/merge-to-main/release/sign/device/live API не выполнялись. Ниже сохранён прежний отчёт, его незавершённые #32/#33 пункты относятся к старому checkpoint.

## Результат ограниченного продолжения (сохранение перед 06:12 UTC)

- Реализация #32/#33 и интеграция сохранены: b7201f7 → merge51cb852 → уточнения5b6a7ad. Safety-ветка не переписана; main без изменений. Новые runtime-требования остаются в AGENT_TASKS до финального acceptance, не удалены как будто полностью приняты CI.
- Integrated51cb852: Release push [39207589053](https://github.com/luoecrt-pixel/000Chat/actions/runs/39207589053), attempt1 — **success**. Workflow реально содержит `test lintRelease assembleRelease bundleRelease`; это проверка основной реализации, не доказательство последующих code-уточнений. Debug [39207589120](https://github.com/luoecrt-pixel/000Chat/actions/runs/39207589120) ещё выполнялся при последнем просмотре.
- Первый локальный общий run остановлен после нового baseline-red `activeWithoutANewChunkStillMarksTheStoredPartialAsUnfinished`: он использовал production, скомпилированный до уточнения активной пометки, и новый test. Не считать этот run final green или полным выполненным набором. На текущем5b6a7ad исправление есть; повторный run вынесен в `/workspace/scratch/000chat-integrated-final-debug.log`.
- Production Debug compile на finalcode5b6a7ad завершён; full local Debug unit/lint/assemble/AndroidTest compile на06:10UTC ещё выполняются. Полного финального Debug/Release acceptance пока нет. Проверка новой semantic boundary по точному последнему head требует новой пары CI; финальный checkpoint push содержит `[ci]`.
- Verifiers и mutation18+7 PASS описаны выше; два независимых read-only delta review не нашли подтверждённых blockers. Никакого live API/платного inference, main merge, PR создания/Ready, production signing, физического телефона или релиза не было. Стандартные debug/release сборки CI — проверочные артефакты, не публикация.
- После сохранения приостановиться по границе окна. Следующее продолжение: сначала exact-final-head CI и локальные результаты, затем только исправления доказанных failures/приёмка. Больше не переносить старые «#32/#33 не реализованы» из отчёта ниже на нынешний код; **реализованы, финальная проверка ещё не закончена**.

---

# Углублённая проверка — 19.10.2026

Продолжение по новому поручению владельца «Сделай более глубокую проверку, чтобы сразу выявить потенциальные проблемы». Проверка начата 04:40 UTC; прежняя ограниченная проверка и её красные результаты сохранены в [PROPOSED_FIX_REVIEW_2026-10-19.md](PROPOSED_FIX_REVIEW_2026-10-19.md). Этот документ уточняет их, а не выдаёт прежние падения за зелёные. Входные решения остались вне исходников; full-file replacements не применялись. Main и PR повторно сверены до работы: main `d51516151d560687b00d5e349fb6bcabfc5c314d`, открытых PR не было.

## Подтверждённые проблемы и изменения

| Область | Доказательство и изменение | Место |
| --- | --- | --- |
| Отложенное скачивание исторического вложения | Источник захватывался до очереди, а ключ читался внутри executor. Ротация ключа могла направить старый file ID новому аккаунту. Теперь credential захватывается до очереди, смена до HTTP запрещает операцию; уже начавшийся HTTP использует исходный ключ. Тесты различают эти случаи, проверяют число запросов/Authorization и сохранность владельца. Это **не** доказывает привязку всех старых исторических file ID к исходному аккаунту. | Safety `MainActivity.kt:7686`, `:7755`; `HistoricalAttachmentDownloadTest.kt:219` |
| Шторка: логическое направление и скорость | Baseline-red: START при RTL двигался в неверную сторону; density ниже 1 ошибочно приравнивалась к 1. Используются реальная сторона панели и положительная density. RTL доказан на компоненте; manifest не включает supportsRtl, это не приёмка RTL всего приложения. | UI `FingerDrawerLayout.kt:60`, `:84`; тесты `:340`, `:366` |
| Шторка: восстановление и потерянный pointer stream | Baseline-red: save при незаконченной анимации восстанавливал ранний кадр вместо команды; новый DOWN после потерянного UP мог оставить панель наполовину; resize продолжал старый drag с прежней геометрией. Теперь сохраняется settle intent, старое владение прекращается при смене геометрии, новый DOWN сохраняет pending settle. Animator регистрируется до start для синхронного окончания при animation scale 0. | UI `FingerDrawerLayout.kt:123`, `:152`, `:187`, `:315` |
| Шторка: доступность | Baseline-red: фон оставался доступным во время частичного открытия; исходный importantForAccessibility восстанавливался неправильно. Modal действует при любой видимой доле, исходное значение сохраняется. Проверено значение importance; полноценный TalkBack traversal ещё не принят. | UI `FingerDrawerLayout.kt:106`; тест `FingerDrawerLayoutTest.kt:406` |
| Цвет кнопки закрытия шторки | Новый тест красный до изменения: кнопка × находится за пределами rootLayout, обход темы её пропускал. Теперь перекрашивается и drawer subtree; специальный стиль «Новый чат» сохраняется последующим применением. | UI `MainActivity.kt:655`; `SessionPresentationTest.themeSwitchAlsoRethemesTheDrawerCloseControl` |
| Пустая строка инструментов | Новый тест красный до изменения: начальный пустой toolLabel имел VISIBLE. Blank теперь GONE, предупреждения/статусы остаются видимыми, все существующие присваивания текста учитываются watcher. | UI `MainActivity.kt:948`; `SessionPresentationTest.emptyToolLabelDoesNotConsumeSpaceButWarningsRemainVisible` |
| Начало запроса OpenAI | Source review показал: active/accounting уже созданы, но preview до первого события сервера не перерисовывался. Добавлен один render после создания accounting. Новый instrumentation-тест удерживает сервер до первых SSE и проверяет progress с захваченной моделью, затем accounting и отсутствие второго inference POST. | UI `MainActivity.kt:3168`; `OpenAiRequestSnapshotAndUploadTest.actualSendShowsCapturedModelProgressBeforeAnyResponseAndThenAccounting` |

UI текущие file:line относятся к рабочему diff; safety — к `c918ed996b31af460c882bb9a5b0a6f845580eb6`. Названия тестов — устойчивые указатели при изменении строк.

## Разбор прежних пяти падений

- Историческое вложение: Edit переносит владение оригиналом в composer; Delete последнего владельца законно удаляет файл. Один общий fixture ошибочно требовал сохранения обоих случаев. Разделены Edit, Delete last owner и Delete shared owner; CAS не ослаблен.
- Вертикальный scroll: fixture ScrollView задавал высоту child через LayoutParams, но UNSPECIFIED measurement оставлял фактическую высоту около 41 px. Установлен настоящий minimumHeight, проверка реального scroll сохранена.
- Прерывание анимации: idle уже завершал анимацию до проверяемого прерывания, а вторичная relayout окна меняла ширину. Используются стабильные реальные размеры и currentPlayTime, предикат незавершённой анимации проверяется.
- Accounting: fixture создавал terminal event, оставляя authoritative request ledger running. Исправлен fixture через observeOpenRouterRound и terminal finalizer; production ledger не подменён event-only расходом.
- Footer/dialog: lifecycle fixture не был запущен, хотя callback намеренно защищён foreground lifecycle. Он теперь start/resume/visible. Требование Robolectric decor.isAttachedToWindow убрано как недостоверная модель native attach timing. Builder.show дополнительно синхронно применяет chrome после установки native controls; create+show сохраняет attach hook и caller OnShow. Отдельный DialogThemeDeviceTest проверяет реальный Android, три темы, enabled/disabled, selected spinner и draft.

## Матрица реально исполненных проверок

На safety `c918ed9` выполнены **49/49** targeted JVM/MockWebServer/Robolectric tests, 0 failures/errors/skips:

| Контракт | Настоящий путь / тесты | Результат |
| --- | --- | --- |
| Исторические download/repair | Activity download → HTTP → transactional SQLite CAS → posted UI; Remove/Reorder/Replace/Edit/Delete/switch/destroy, repair между commit и UI, нормальный reopen, credential до/после HTTP, большой UTF-8/NUL, ABA с прежними request/file IDs | HistoricalAttachmentDownloadTest 18/18 |
| Единственный inference POST | HTTP408, 503 Retry-After:0, ambiguous disconnect, 307/308; явный tool continuation остаётся отдельным допустимым раундом | OpenRouterPaidPostSafetyTest 5/5 |
| Checkpoint/Stop/replacement | Delayed SSE → SQLite → Activity partial до terminal, чужой request, Stop/archive, increasing cursor и OpenAI CAS | OpenRouterRuntimeCheckpointTest 4/4 |
| Signed replay и accounting соседи | Существующие protocol regression, accounting transport и exact error classifier; исходная причина provider400 этим не доказана | 15+2+5 PASS |

UI final targeted: **33/33**, 0 failures/errors/skips: FingerDrawerLayoutTest 23, ChatMessageLayoutManagerTest 4, SessionPresentationTest 6. Помимо touch fixtures выполнены реальные Editor.performLongClick/hasSelection и LinkMovementMethod/clickable span; actual detach/reattach и animation scale 0. Narrow selection-scroll fix сохраняет editable, keyboard, explicit rectangle scroll и accessibility ACTION_FOCUS/ACTION_SHOW_ON_SCREEN. Blanket requestChildRectangleOnScreen=false не применялся.

Предварительный полный UI Debug run: **707 tests, 1 failure** (описанный выше detached-dialog fixture). Это промежуточный результат до последних четырёх drawer-тестов, двух controls-тестов и исправления native Builder.show; его нельзя выдавать за финальный full green.

Static UI: verify_handoff, verify_v4_17_0, verify_implementation PASS; mutation controls v4 10 negative + positive, implementation 7 negative + positive PASS. Safety: те же verifiers PASS, v4 расширен до 18 negative + positive (включая ownership/credential guards), implementation 7 + positive PASS. Устаревший лексический helper заменён проверками настоящего immutable-target/CAS пути; unsafe helper не возвращался.

Независимый read-only review выполнен: drawer автором safety-изменений, safety и Main/theme изменениями — автором drawer. Новых подтверждённых blockers в просмотренных delta не найдено. Это не финальное одобрение собственного PR и не замена runtime/CI.

## CI и интеграция

- Safety code `c918ed996b31af460c882bb9a5b0a6f845580eb6`: push Release [39201447163](https://github.com/luoecrt-pixel/000Chat/actions/runs/39201447163), attempt 1, **success**. Push Debug [39201447117](https://github.com/luoecrt-pixel/000Chat/actions/runs/39201447117), attempt 1: build/lint/JVM success, instrumentation ещё выполняется на момент записи.
- Финальная локальная команда UI full Debug/Release/lint и Android test compile **прервана по просьбе владельца сохранить и приостановиться, 05:00 UTC**. Gradle daemon/worker завершены; exit 1 вызван остановкой, это не установленная регрессия. Полного финального green нет. Новые instrumentation-тесты пока не считать скомпилированными или исполненными. Возобновление требует нового поручения.
- Ветки пока не интегрированы; отдельные green не доказывают совместный MainActivity/ChatDatabase diff. В main ничего не переносилось, Draft→Ready/merge/release/sign/physical-device действий не было.

## Остающиеся границы и требования

1. Signed reasoning: по-прежнему нельзя определить причину исходного messages.19 отказа без provider envelope. Присланный seal-on-first-signature/data ломает фрагментированные значения, sanitizer удаляет replay; контрпримеры прежней проверки сохраняют силу. Ничего подписанного автоматически не отбрасывается и не исправляется эвристически.
2. age/URL eligibility/cache/noise: конкретный источник дат и отказов у приведённых сайтов не установлен. Не скрывать age, не выдавать самоотчёты модели за реальные calls. Проверять официальный contract отдельно от гипотезы о конкретной ошибке; live paid providers не вызывались.
3. Старые historical file ID не имеют доказанной persisted-привязки к аккаунту. Queue credential fix закрывает смену ключа после захвата операции, но не решает эту историческую границу.
4. Не воспроизведён возможный orphan-window между snapshot удаления владельца и поздним download commit; не объявлять его найденным багом. Требуется отдельный управляемый runtime counterexample перед production-изменением.
5. UI #33/#32 остаётся незавершённым: текущий footer ещё Copy/Save TXT; нужны 👍/👎/Копировать/Повторить, удаление message TXT в footer/long-press и whole-dialog export в меню шторки. Для historical Retry нельзя молча повторять последний другой запрос. Эти пожелания не потеряны в [AGENT_TASKS.md](AGENT_TASKS.md).
6. Обязательный Android foreground indicator сохранён: он silent/minimum/deferred, result alerts отдельны. Полностью скрыть его без пересмотра долгой background работы нельзя.
7. Физическая плавность, selection handles/floating toolbar, TalkBack traversal, узкий экран/увеличенный шрифт и live provider/billing остаются отдельной приёмкой. Наличие Robolectric/эмулятора не выдавать за проверку телефона.

## Уточнение по официальным контрактам

Независимый агент прочитал публичную документацию, без provider API/оплаты. [OpenRouter preserving reasoning](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens#preserving-reasoning) требует передавать последовательность без перестановок/изменений. [API shape](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens#reasoning-details-api-shape) задаёт optional index и optional signature у reasoning.text, включая пример signature:null; streaming требует конкатенации chunks по порядку. Наличие первого signature/data не объявлено sealing marker. Эти страницы не доказывают конкретный merge алгоритм каждого поля, но blanket удаление неподписанных text-блоков общей схеме не соответствует.

[Native Anthropic streaming thinking](https://platform.claude.com/docs/en/build-with-claude/thinking#streaming-thinking) описывает один signature_delta перед content_block_stop. Это не доказывает структуру нормализованных OpenRouter chunks или причину ошибки владельца. [Official thinking troubleshooting](https://platform.claude.com/docs/en/build-with-claude/thinking-troubleshooting#error-thinking-blocks-modified) приводит точную ошибку и требует echo assistant turn verbatim, включая thinking; фильтрация redacted_thinking/пересборка — типичные причины, не диагноз конкретного случая.

[Anthropic search results](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool#search-results) определяет page_age как «When the site was last updated». Эквивалентность пользовательского age этому полю и объяснение возрастом индексации шаблона не установлены. [Native URL validation](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-fetch-tool#url-validation) разрешает также URL из client-side tool results; появления лишь в assistant/system недостаточно. [OpenRouter web-fetch engines](https://openrouter.ai/docs/guides/features/server-tools/web-fetch#engine-selection) включают другие engines, поэтому Anthropic eligibility нельзя переносить на все OpenRouter fetch-вызовы.

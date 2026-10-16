# WIP: #21 + K/#29 + #18.1–2 — продолжение

База main e096b8be2961c113a75694de9f37bd166de9c019 (J/#174 merged).
Ветка feat/package-k-atomic-accounting-and-uploads, workspace /workspace/000Chat-k.
Владелец: реализовать в репозитории, архив не нужен, ожидание Debug/Release CI оставить ему. Автор не merge.

## Текущая проверка
Первый полный прогон:604 tests,6fail. Исправлены все6 (legacy отсутствующие таблицы, пустой usageledger, тестfixturepartial).
Повторный testDebugUnitTest:608 tests,0fail/errors/skipped по XML. Основной код и Androidtests компилировались; lint/assemble могли ещё выполняться: /tmp/package-k-final-checks.log, exec session33985.
ВАЖНО: ПОСЛЕ компиляции этого прогона внесены последние изменения ниже — они ещё не собраны/не протестированы. Не объявлять финальный HEAD зелёным.
Четыре Python/static/mutation verifier проходили до последних изменений; повторить после них. git diff --check PASS.

## Реализовано
Main immutable Send tools/options snapshot+hook, локальный ordered picker без eageruploads, provider capture, exact uploadcache, корректные filenames/SHA/originals.
Ledger raw perresponse отдельно от aggregate marker; legacybase; durable terminal/prefix recovery; ancestorID rejection; marker propagated Main/Worker/failure/DB.
DB v25 owner-aware atomiccompaction, Stop paid archive, >2MB chunked TEXT, rollback/stale/corrupt tests, snapshotmigration24/25.
Unknown/zero/estimate costs separated; SSE breakdown, late reconciliation banners/events/metrics; IO counters and bounded redacted diagnostics Summary/Copy/Export.
Main finished catch prefers durable terminal over stale parent envelope. No unconditional OpenRouter finally/recovery checkpoint clear.

## Последние дополнения, обязательно проверить
1. Новый internal OpenRouterUsageAccumulator в OpenRouterCompletionTelemetry.kt: mergeSnapshot + aggregate. Клиент переведён на общий helper. DB хранит usage_snapshot TEXT в openrouter_request_rounds и агрегирует полные cache/cachewrite/reasoning/costdetails перед Stop/terminal. Добавлено accounting.cache_write_tokens. Проверить SQL creation/upgrade/onOpen, selectorsindices, sparse/repeated/reconcile snapshots.
2. DB seals OR rounds ВНУТРИ Stop/complete tx. Новые тесты уже есть для seal/rollback. Последнему usage_snapshot добавить тест Stop/reopen с cache/reasoning/breakdown и повторным rawsnapshot без дублей.
3. recoveryArchivePaths включён в listAllLocalFilePaths/per-chat paths: проверить сохранность и delete cascade/owners архивных generated_images/annotations. Дописать тест для последнего owner helper.
4. FileBackedIoTelemetry merges delayed counters via max + toolsunion; новый merge regressiontest сохранён, нужен прогон.
5. Прочитать diff и снова провести cross-file review только новой delta; текущие helpercalls собраны ещё не были после последних edits.

## Далее
. /workspace/toolchains/env.sh
В проекте нет ./gradlew; использовать Gradle8.9/JDK17/SDK35.
gradle --no-daemon --max-workers=2 :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug
python3 tools/verify_handoff.py
python3 tools/verify_v4_17_0.py
python3 tools/verify_implementation.py
python3 tools/test_verify_v4_17_0_mutations.py
Эмулятора/KVM нет: instrumentation только compile локально. Не заявлять live/device/billing proof.
После PASS удалить этот временный note, обновить PRbody с точной матрицей/tests/SHA, commit [ci], push. Оставить Draft до acceptance; ожидание pushDebug/Release владельцу, не ждать самому.

# SESSION_CHECKPOINT — актуальное состояние 19.10.2026

19.10.2026 владелец прямо разрешил merge PR #177 при успешной проверке. Новое P2-замечание о временных экспортах подтверждено и исправляется в этом же PR. Новые runtime/test изменения требуют новой успешной пары Debug/Release: прежний green1dacac9 их не доказывает. По прежнему поручению ожидание CI остаётся владельцу; до новых green не переводить в Ready и не сливать. Release/production signing/физический телефон не поручались. Исторические checkpoints сохранены в Git; подробные разборы — DEEP_REVIEW_2026-10-19.md и PROPOSED_FIX_REVIEW_2026-10-19.md.

## Новое P2: временные копии экспорта, 08:52 UTC

- Orphan после смерти процесса подтверждён. ConversationExportSnapshots ограничивает файлы namespace dialog/message_UUID.md/txt в filesDir/exports; cleanup на старте и перед экспортом: возраст24h, максимум256 просмотренных записей/32 удаления за вызов. Pending из saved state удерживается до cleanup; process-wide pins защищают текущие writer/picker/SAF copy. Cancel/success/error/lifecycle cleanup переведены на store; paths outside/неизвестные файлы/symlinks не используются как snapshots.
- DialogConversationExporter.writeTo(Appendable) пишет source/title <=8KiB UTF-16 chunks без разрыва surrogate pair; production snapshot использует bufferedWriter UTF-8. Дополнительный whole-export String убран. JSON history и прежние active/stored deep copies пока остаются в памяти; это не полностью streaming SQLite.
- Целевой Gradle: exporter12 + snapshot-store4 + Activity/SAF3 = **19/19 PASS**,0failures/errors/skips. После safe invalid-path/iteration уточнений полный финальный testDebugUnitTest: **763/763 PASS**,114 XML suites,0failures/errors/skips. `lintDebug assembleDebug` **PASS / BUILD SUCCESSFUL**, полная последовательная команда6m57s; активных локальных Gradle нет. Static3verifiers и diff-check PASS.
- Независимый bounded read-only delta review не нашёл blockers; exact final SHA/новые CI ещё не проверены. JVM fresh registry моделирует process death; Activity recreation→restored picker→SAF исполнен в Robolectric. Новый реальный Android process-kill для экспорта не исполнялся.
- Логи: /workspace/scratch/000chat-export-cleanup-targeted.log и 000chat-export-cleanup-full-debug.log. Новый runtime commit с [ci] должен запустить обе CI; ожидание владельцу. Не ссылаться на прежний755/755 как на новый code proof.

## Ветки и точный результат CI

- `/workspace/000Chat`, `fix/interaction-telemetry-design`: интеграция safety выполнена merge51cb852, export/footer b7201f7, active/retry уточнения5b6a7ad, проверенный runtime head1dacac9f693a9c4ad437d768c4ed267726a900b0. Code64c202f исправляет оформление полей диалогов; baseline1dacac9/20f7cc0 подтверждён ранее, новый runtime export-fix описан выше и требует собственной пары CI.
- `/workspace/000Chat-stability`, `fix/request-and-attachment-safety`: c918ed996b31af460c882bb9a5b0a6f845580eb6 уже включён в UI. Обе safety CI success, ветка сохранена.
- Main d51516151d560687b00d5e349fb6bcabfc5c314d повторно проверен. PR #177 открыт, Draft, head до нового export-fix20f7cc0; mergeable clean, reviews пока нет. В main ничего не переносилось.
- Exact1dacac9 Debug39213376233 и Release39213376235, оба push attempt1 — **success**, проверены metadata и реальные job logs. Debug: full Debug/Release JVM+lintDebug+assembleDebug; isolated Android10PASS, full Android152cases с0failures (3 служебных fixtures пропущены в обычной suite), отдельные5 host-driven process recovery scenarios PASS: terminal, expired_partial, stopped_pre_id, replaced, background_created. Release: test/lintRelease/assembleRelease/bundleRelease success.
- Прежнее Android падение9e6fbb9 с native InsetDrawable исправлено64c202f и больше не воспроизвелось на exact1dacac9. Исторические красные runs не относятся к нынешнему runtime.
- Два новых независимых bounded read-only review diff1dacac9..d515161 (safety и UI/export) не нашли подтверждённых blockers/new regressions. Это не обещание0bugs и не повтор полного аудита всего main. Важная граница: отдельная задержанная гонка внутри второго async preview не запускалась (download tests openAfter=false); source/UI/preview guards дополнительно просмотрены по коду. Подробности и матрица помещаются в PR.

## Исправление найденного сбоя

AppThemeViews.content задавал EditText только text/hint и accent backgroundTintList. Response Settings оставались с native dark Material background, а tint мог окрашивать целиком custom fill других полей. В64c202f app-owned EditText получают GradientDrawable inputBg surface; focus border accent, default/disabled divider, backgroundTintList=null. Сохраняются padding absolute/relative, text, hint, inputType, selection, native focus listener/editing. Instrumentation проверка не ослаблена: добавлено требование отсутствия tint. Новый native-graphics test проверяет все3 темы, fill/border/disabled и editing state.

## Фактически выполнено локально на64c202f

- SessionPresentationTest **8/8 PASS**, включая native Canvas regression.
- Полный testDebugUnitTest **755/755 PASS**,0failures/errors/skips (113 XML suites), включая exporter9, exportSAF2, drawer23, layout4, presentation8, historical download18 и остальные интегрированные проверки.
- verify_handoff, verify_v4_17_0, verify_implementation PASS; git diff --check PASS. Verifier guards не менялись; mutation18+7 прежде PASS на9e6fbb9.
- Вся последовательная Gradle command `testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin` **PASS / BUILD SUCCESSFUL**,7m55s. APK собран, lintDebug завершён, Android Kotlin tests скомпилированы. Лог `/workspace/scratch/000chat-dialog-input-full-debug.log`; targeted `/workspace/scratch/000chat-dialog-input-targeted.log`. Активных локальных Gradle builds нет. Warnings о Java nullable types в прежних test helpers не являются errors.
- Пара на1dacac9 завершилась успешно, включая process recovery. Docs-only20f7cc0 не требовал Android rebuild, но последующий новый runtime export-fix требует отдельной пары CI; Verify handoff нужен на итоговом PR head.

## Реализованный scope и оставшиеся границы

- Footer👍/👎/Копировать/Повторить, без messageTXT в footer/long-press; retry проверяет actual latest user request, durable row/request identity, текущий чат/lifecycle и draft. Whole-dialog UTF-8 .md/.txt — из меню выбранного чата, immutable SQLite history+active snapshot→SAF, raw text, безопасные summaries, active marker/dedupe, cancel/lifecycle cleanup. Эти функции **реализованы**, не повторять старое «ещё Copy/SaveTXT» из исторических отчётов.
- Safety oneShot POST, OpenRouter partial checkpoint, immutable historical download CAS/credential queue guard, ordering, drawer/selection и log/theme изменения интегрированы. Signed reasoning не фильтровался и не заменялся присланными full-file вариантами.
- Причина исходного provider400 и конкретных age/cache/noise/eligibility случаев по-прежнему не установлена; server-managed engines различаются. Legacy historical fileId→исходный аккаунт не доказан. Эти прежние границы не выдавать за новые регрессии.
- Физическая плавность/selection handles, TalkBack traversal, узкий экран/увеличенный шрифт, live providers/billing отдельно не приняты. Обязательный silent/minimum/deferred Android FGS indicator сохранён.
- PR #177 создан из fix/interaction-telemetry-design в main. Новое прямое разрешение merge действует после независимого заключения и успешной новой пары CI на исправленном коде. Актуальные head/CI/main перепроверить перед squash merge с expected HEAD. Авторское оформление PR и bounded independent delta review не заменяют отдельного аудита Ready PR.

## Среда и входные решения

`. /workspace/toolchains/android-env.sh`, JDK17/Gradle8.9/SDK35, один локальный Gradle, `--no-daemon --max-workers=2`. Не печатать env/keys/private logs; HTTP только mocks/synthetic credentials. Входные1/2/3fix byte-for-byte и MANIFEST/REVIEW_REQUEST остаются в `/workspace/shared/000Chat-review-2026-10-19/`; сырые файлы не публиковались. Старые save-state snapshot строки не возвращались, bounded/current-chat-priority anchors сохранены.

# WIP: #21 + #29/K + #18.1–2 — продолжение

База: main e096b8be2961c113a75694de9f37bd166de9c019 (J/#174 merged).
Ветка: feat/package-k-atomic-accounting-and-uploads.
Владелец попросил реализовать в репозитории; архив/монолит присылать уже не нужно.
Ожидание Debug/Release CI оставить владельцу. Merge НЕ выполнять автором.
Работа прервана по сообщению владельца «минута или менее до лимита».
Это НЕ готовый патч: сборка и тесты ещё не запускались.

## Сделано, но не проверено
- Main: immutable OpenAiRequestOptionsSnapshot всех отложенных prefs и previous_response_id; openAiPreparationHook; picker локальный для обоих провайдеров, provider захватывается до async.
- OpenAiUsageLedger: raw usage по response ID отдельно от aggregate, legacy unattributed base, terminal snapshot protection. FIELD=openai_usage_ledger.
- Bridge: durable raw received_response/usage ledger; aggregate marker; idempotent attachReceivedPrefix; durableResponse и extractTelemetrySummary.
- DB: schema25, атомарный terminal cleanup, telemetry/ledger, stopped recovery archive вместо возврата удалённых bubbles, late perresponse reconcile, costkind/usageTotals.
- OpenRouter: cost breakdown SSE accumulation, nullable unknown, IO helpers/deltas, runtime telemetry, убрать finally clear, event formatter/policy.
- FileAnalysis: SHA-safe original/derivative, no redundant .txt, exact cache, image preflight.
- Worker/failure paths начали интеграцию ledger/summary и сохранности output.

## Сначала завершить
1. Сверить все сохранённые edits/агентские отчёты; выполнить compileDebugKotlin, исправить несовместимости API. Только git diff --check выполнялся.
2. Main пока НЕ завершён: propagate OpenAiUsageLedger.FIELD из effectiveResponse в normal/partial/resumed assistant ДО DB finalize, attach compact tool+FileIO telemetry, persist IO через chatDb.recordRequestTelemetry перед handoff, передавать aggregate usage/summary в later accounting/event (не responseState.usage raw!). Helper API от FileBackedIoTelemetry уже есть.
3. Main: убрать clearOpenRouterCheckpoint в recoverInterruptedOpenRouterRequests; showDiagnostics structured per-request summary с captured chat/UI ownership и redaction Copy/Export; formatUsageStats distinct confirmed/estimated/unknown. Старые eager picker fallback ветви теперь недостижимы для pickAny/image — проверить/убрать безопасно.
4. DB regression tests пока отсутствуют. Проверить Stop legacy100 + child20 twice=120; repeated aggregate marker120 не становится220; finished150 vs child50; >2MB Unicode cp; rollback/fault/staleowner/reopen; terminal/noassistant paid output; corrupt data failclosed. DB агент отметил: recordRequestAccounting late unmarked raw usage может затереть aggregate; event insert после раннего reconcile может не взять ledger цену — проверить.
5. Новые actual Activity snapshot/picker tests в работе: openAiPreparationHook reflection; flip paid prefs; zero picker uploads; one note/xlsx upload, no image; repeatcache/credential/Stop/missing source. Existing mixed J test tightening saved. Проверить наличия нового test файла (агент мог не успеть).
6. Worker regression/instrumentation assertions cp deleted + summary durable; убедиться BackgroundFailure preserves full final_response before checkpoint cleanup.
7. OpenRouter добавить real SSE multi-round cost_details/unknown+zero/IO/typederrors tests; all 7 owned prod files saved, новых тестов ещё нет. Current typed error metadata contract должен сохраниться.
8. FileAnalysis formats/cache tests сохранены, не запускались. UploadCache exact key не менять на content-only.
9. Прочитать AGENTS/NEXT_AGENT_HANDOFF/AGENT_TASKS; актуализировать план без заявления о CI/приёмке до доказательств. PROJECT_HISTORY не журнал.

## Проверки/окружение
. /workspace/toolchains/env.sh
Gradle8.9/JDK17/SDK35 доступны, wrapper в репозитории НЕТ.
gradle --no-daemon --max-workers=2 :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug
python3 tools/verify_handoff.py
python3 tools/verify_v4_17_0.py
python3 tools/verify_implementation.py
python3 tools/test_verify_v4_17_0_mutations.py
Эмулятора/KVM нет: instrumentation compile не execution. Не утверждать обратное.
После исправлений и проверок — отдельный commit [ci], push и Draft PR. Не ждать CI, ссылки владельцу.

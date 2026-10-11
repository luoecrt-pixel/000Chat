# BUNDLE_AGENT_HANDOFF — дополнительный контекст пакета

**Этот документ не заменяет `NEXT_AGENT_HANDOFF.md` в репозитории, который остаётся главным.**

## Где мы

Продукт: Android `000Chat` (бывший OpenAIApiChat), публичный GitHub репозиторий `luoecrt-pixel/000Chat`. Накопительный patch этого пакета построен от **старого** base `4111b83a215da03af544e8c0477246962c43395b`. `main` на момент подготовки пакета: `120ac217c2a9f06a8b0b062b8571800c52682f33` (поздние коммиты в основном security/CI/artifact cleanup); сначала сверить HEAD и дерево, затем переносить код безопасно. пакета содержит только изменённые/new файлы, не полный checkout.

## Что написано, но не доказано полной Android сборкой

- Исправления обзоров 34–40, файловых кэшей A–F, renderer assets и streaming presentation.
- Исправлена потеря OpenAI terminal assistant при несовпадении HTTP `x-request-id` и локального request ID; сохранение SQLite дополнительно защищено CAS от чужого ID. Добавлен device-регрессионный тест.
- Данные `org.json` null/number/object перестали попадать в text/response-id/error через SSE и модель; пустые/ошибочные статусы показываются в UI, не сохраняются как ответ модели. Добавлен Robolectric-тест.
- XLSX пустая shared-formula остаётся формулой без cached value; исправлены корректность JPEG test fixtures, не production JPEG-validation.
- Источник README и pinned SHA рендерера не менялись. `RENDERER_DISTRIBUTIONS.md` сохраняет правила расследования mismatch.

## Что реально проверяли (старый этап)

Python `verify_implementation.py`, отдельные Kotlin host-пробы JSON-null и JPEG; верность base Git blobs и apply patch в **частичной синтетической базе**. Нет подтверждения полного Android Gradle/JVM/Robolectric/lint/instrumentation, реальных API, Xamarin/Xiaomi device или релизного APK.

## Что делать сейчас

1. Читать `NEXT_AGENT_HANDOFF.md` из текущего `main`, затем `AGENTS.md` и `AGENT_IMPLEMENTATION_RULES.md`. Не исполнять старые устаревшие чек-листы из прежних отчётов.
2. Сверить текущий `main` и текущие PR/ветки, определить конфликты и не переписывать security/Actions изменения, появившиеся после base.
3. Восстановить полный checkout старой базы; запускать `verify_bundle.py` строго на чистом checkout; `git apply --check` от базы, затем переносить 111 подготовленных файлов/изменения на новую ветку актуального `main` с сравнением контекста.
4. Реально выполнить Gradle compileDebugKotlin, compileDebugUnitTestKotlin, testDebugUnitTest, lintDebug, compileDebugAndroidTestKotlin; решить инфраструктуру Robolectric SDK, а не отключать тесты. Затем рассмотреть instrumented tests, renderer assets и release.
5. Документировать фактические команды, ошибки и пробелы. Не говорить, что release готов, без нужных проверок. Соблюдать 35-минутные рабочие этапы пользователя.

## Что было исключено из архива

Исторические REVIEW_*, AUDIT_RESPONSE, VALIDATION, FILE_CAPABILITIES, RESPONSE_PRESENTATION_PLAN и многостраничный WORK_CHECKPOINT. Они содержали исторические диалоги и повторяющиеся статусы, которые путали исполнителя. Технические constraints, исходный код, тесты, pinned hashes, применение patch и короткие правила сохранены. Подробная история остаётся в старых пакетах/коммитах; этот архив намеренно короткий.

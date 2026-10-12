# NEXT_AGENT_HANDOFF

**Постоянные правила проекта 000Chat.** Действующие задачи, все критерии и планы на потом — в [AGENT_TASKS.md](AGENT_TASKS.md). Существенные технические причины прежних решений — в [PROJECT_HISTORY.md](PROJECT_HISTORY.md), читать при необходимости, не на каждом старте. Это не журнал предыдущих агентов.

## Перед работой

1. Сверить текущие main/ветки/PR, реальный код, тесты и соответствующий CI. Старые заявления «исправлено» и «прошло» не заменяют проверку конкретного SHA.
2. Взять один согласованный срез из плана; сначала воспроизвести проблему и негативный сценарий, затем исправить и проверить. Критерии пользователя не подменять похожим готовым функционалом.
3. Main изменять через PR, кроме прямого поручения владельца записать в main. Перед записью перепроверять head/blob, не перезаписывать чужие изменения, не force-push вслепую.
4. Не выполнять реальные платные API-вызовы, действия с физическим телефоном, изменение публичности/подписывающих ключей без соответствующего разрешения. Не публиковать секреты, приватные данные и незаредактированные логи.

## Архитектура и контракты, которые легко нарушить

- Локальный multi-chat Android клиент: OpenAI Responses + SSE/WorkManager recovery и отдельный OpenRouter Chat Completions/SSE/tool loop. `MainActivity.kt` координирует UI; `ChatDatabase.kt` хранит сообщения/metadata/active state; существующие seams — `ResponseEventReducer`, `ResumableResponseStreamClient`, `ActiveResponsePersistence`, `ResponseCheckpointPolicy`, `BackgroundResponseUiOwnershipPolicy`, `AttachmentUploadCompletionPolicy`. Точную версию SQLite/SDK получать из исходников.
- Async UI/DB операции требуют принадлежности к captured `chatId + generation`, при редактировании конкретного ответа ещё `assistantItemId`. Поздний callback после Stop/Delete/switch/recreate/Worker не имеет права оживить старое состояние или завершить другой request. UI-гейт должен соблюдать main thread/lifecycle и восстановление актуального состояния при `onStart`.
- **Данные не усекать и не терять:** полный текст и все исторические вложения, локальные оригиналы bytes, signed Claude reasoning/tool replay, порядок сообщений, `fingerprint v1`, request-aware SQLite CAS, drafts. Preview/derived artifact не заменяет original/wire source; другие аккаунты не могут пользоваться чужими server file IDs.
- **Без скрытых лимитов генерации:** не вводить короткие idle timeout, silent context trimming, fixed tool-round count или неявный `max_output_tokens`. Аварийная граница — 5 часов на отдельный response/recovery; OpenRouter также 5 млн reported usage tokens на один complete/tool-loop cycle. Не путать с лимитами модели, сеанса и безопасными byte/page budget файлов (они должны давать явную ошибку).
- Ручной reasoning effort не снижать молча. OpenRouter 1h cache policy не должна конфликтовать с nested default 5m marker. Не делать автоматические платные повторы при утрате server response/файла. Если стоимость не подтверждена, это **unknown**, не ноль.
- Не ослаблять TLS, SHA pins, GitHub write confirmation/allow-list, security checks JSON/SSE, EXIF/JPEG, HTML/SVG sandbox, изоляцию renderer/Android WebView или redaction ради прохождения тестов. Источник pin/provenance — [RENDERER_DISTRIBUTIONS.md](RENDERER_DISTRIBUTIONS.md); секреты — согласно [SECURITY.md](SECURITY.md), лицензии — [NOTICE.md](NOTICE.md).
- Compose/массовая controller extraction — отдельные проекты, не универсальное исправление багов lifecycle. Новые требования владельца берутся только из актуального плана, а не из старого аудита.

## Проверки и GitHub Actions

- Сначала проверить доступность JDK 17, Gradle 8.9, Android SDK 35 и при необходимости adb/API34 emulator. В checkout нет Gradle wrapper. При недоступности Android отделять static/JVM proof от CI и физического устройства.
- Из корня: `python3 tools/verify_handoff.py`; `python3 tools/verify_v4_17_0.py`; `python3 tools/verify_implementation.py`; плюс изменённые тесты и mutation checks. Для runtime изменения нужны релевантные JVM/Robolectric/Android instrumentation и Debug/Release CI. Чтение кода не считается исполнением теста, статический PASS не является компиляцией.
- `.github/workflows/build.yml` и `release.yml`: push только на ветках **кроме main**; полный job выполняется по `[ci]` в commit message или `CI_AUTO_RUN=true` (ручной `workflow_dispatch` существует). Docs-only после runtime code SHA не требуют Android rebuild; `Verify handoff` обязан пройти на финальном PR head. Runtime/test/build-step изменения требуют новой успешной пары push Debug/Release на соответствующем code SHA.
- Проверять event, head SHA, attempt, conclusion и failing step. `Skipped`/`Cancelled`/`queued` — не Successful; не повторять детерминированный провал без диагноза. Docs push той же ветки может отменить незавершённый run из-за concurrency. Перед merge проверять фактический diff и head.
- Mock HTTP/эмулятор, статическая проверка, реальный провайдер, billing и ручная приёмка телефона — разные доказательства. Не писать «полностью проверено», когда какая-либо обязательная граница ещё не испытывалась.

## Как поддерживать три документа

- **`AGENT_TASKS.md`** — единственное место для активных/будущих задач владельца. Не удалять непринятые требования. После доказанного исправления удалить завершённую часть из активного плана; не переносить туда историю CI или случайные идеи агента.
- **`NEXT_AGENT_HANDOFF.md`** — краткие стабильные правила; не писать текущие SHA, runs, описание каждого PR, исторические чеклисты или списки готовых функций.
- **`PROJECT_HISTORY.md` — не журнал действий.** Добавлять запись **только** когда появился значимый неочевидный инвариант, объяснение трудновоспроизводимой регрессии или долгосрочный архитектурный/security компромисс, который не понять по одному коду. Формат: проблема → принятое решение/причина → ссылка на тест/код/PR. Не более нескольких строк на тему; обновлять существующую запись вместо копирования. **Не добавлять** рядовые фиксы, все PR подряд, runs, окружение, «что я сегодня сделал» и выполненные планы. Если нет такого знания — историю не менять: Git и PR уже хранят полную хронологию.
- `AGENTS.md` — короткий указатель для агентов, читающих его автоматически. `LastREADME.md` исторически устарел, без отдельного запроса не менять. Сохранять специальные `SECURITY.md`, `NOTICE.md`, `RENDERER_DISTRIBUTIONS.md`.

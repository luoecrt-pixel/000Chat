
## v4.16.2

Attachment UI now shows upload progress immediately, previews images, reads text/JSON/PDF in-app, preserves sent attachment metadata across restarts, restores cached generated files, and keeps ZIPs unopened until Send.
# OpenAI API Chat — Astra v4.15.1

Нативный Android-клиент для собственного OpenAI API key, ориентированный на GPT-6 Astra.

## Реализовано

- Чат-интерфейс и несколько локальных чатов
- SQLite история с миграцией старого `chat_store`
- лёгкий список чатов без загрузки всей истории
- Responses API streaming + `previous_response_id`
- `gpt-6-astra`, reasoning low/medium/high/xhigh/max + Auto
- `configuration_update` для смены reasoning effort в standard mode
- `web_search` + кликабельные `url_citation`
- File Search + `file_citation` с явным скачиванием
- Code Interpreter + реальные `container_file_citation` и локальный cache
- Image Generation + локальное сохранение результата PNG и preview
- Images input через Files API `file_id`
- Markdown/bare HTTP(S) links
- ZIP import с потоковой распаковкой и лимитами
- диагностика, usage/effort metrics, 👍/👎 feedback
- live tool activity statuses
- local Astra file library (Open/Save/Share/Delete)
- background Responses with automatic resume after app restart
- persistent composer drafts and uploaded attachments per chat
- отмена генерации и защита от устаревших callback
- экспорт/сохранение/поделиться через Android Storage Access Framework
- GitHub Actions build workflow

## Сборка

Канонический workflow: `.github/workflows/build.yml`.

Он устанавливает JDK 17, Android SDK 35, build-tools 35.0.0 и Gradle 8.9, затем запускает `tools/verify_v4_14.py` (совместимое имя verifier) и `assembleDebug`.

## Ограничения

Полноценный event-by-event renderer всех Responses API событий, Computer Use executor, MCP UX и Realtime voice — отдельные функции. Финальный ответ уже проходит через `AstraResponseModel`, а live streaming display использует быстрый текстовый буфер. Background Responses автоматически восстанавливаются через polling; cursor-based stream resume (`starting_after`) пока не используется.

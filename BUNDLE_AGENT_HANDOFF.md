# BUNDLE_AGENT_HANDOFF — дополнительный контекст пакета

**Главный источник истины — `NEXT_AGENT_HANDOFF.md` из актуального main. Этот файл его не заменяет.**

## Фактический результат

Релиз 4.17.0 (`versionCode=64`) интегрирован в PR [#141](https://github.com/luoecrt-pixel/000Chat/pull/141), код `cceacdd8c392b2a69338f2313c6db137d210f307`. Исходный ZIP был частичным cumulative patch: 111 путей от старой базы `4111b83a215da03af544e8c0477246962c43395b`, перенесённых на main `120ac217c2a9f06a8b0b062b8571800c52682f33`. Повторно применять ZIP не нужно. README, workflows, .gitignore и SECURITY сохранены из main; канонический handoff актуализирован после проверок.

- Исправления request-aware OpenAI terminal persistence, SQLite v22, JSON null/SSE, file/attachment processing, локальных файловых инструментов, уведомлений и rich renderer прошли полный CI.
- Научное превью обслуживает принадлежащий конкретному WebView HTML из памяти по точному HTTPS URL; остальные main frames, data/file/content и внешние запросы блокируются. SHA ресурсов, TLS, Android image/JPEG validation и ownership не ослаблялись.
- Device fixture отдельно проверяет безопасный HTML-like текст, отсутствие script execution/elements и сохранность полного исходника при неизвестной LaTeX-команде.

## Выполненные проверки

- Debug push [38175698456](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698456): Successful, JVM tests/lint/assembleDebug и 39/39 device tests, artifact [11716107416](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698456/artifacts/11716107416).
- Release push [38175698458](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698458): Successful, tests/lintRelease/assembleRelease/bundleRelease, artifact [11714939668](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698458/artifacts/11714939668).
- Verify [38175702106](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175702106): Successful на кодовом SHA. Final docs-only head требует отдельного Successful Verify.
- Локально: source/handoff verifiers, positive/negative mutation matrices, Python fixtures, pinned Maven bytes и inventory 82 modules/fonts/licenses; host Kotlin генерация и Node syntax/KaTeX probes. Локального Android/WebView runtime не было: Android assertions выполнены в Actions.

Оба push runs и кодовый Verify: `cceacdd8c392b2a69338f2313c6db137d210f307`, attempt 1. PR-event Skipped не считать сборкой. Debug APK подписан; Release APK/AAB без release signing config. Артефакты хранятся 5 дней; Build SHA сборки отличается от последующего squash SHA.

## Продолжение и границы

Сначала сверить actual main, PR merged/merged_at и final-head Verify по GitHub. Затем выполнять только выбранный владельцем следующий срез из канонического handoff. Физический телефон, реальные большие фото, live provider acceptance/биллинг и 5h реальный SSE не проверены; платных вызовов и доступа к телефону не было. Следовать последнему ограничению времени владельца (в текущем этапе — 40 минут); перед незавершённой остановкой сохранять короткий фактический checkpoint.

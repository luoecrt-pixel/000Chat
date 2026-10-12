# KaTeX / Mermaid — происхождение закреплённых зависимостей

В Android-приложении используются упакованные webjar-ресурсы. Их точные версии и **ожидаемые SHA-256** находятся в [config/response-renderer-sha256.properties](config/response-renderer-sha256.properties), а правило проверки реализовано в `app/build.gradle.kts`. Файл фиксирует provenance первого доверенного baseline от **10.10.2026**, а не текущее состояние всей сборки.

| Maven Central artifact | Версия | Размер исходного JAR | SHA-256 JAR |
| --- | --- | ---: | --- |
| `org.webjars.npm:katex` | `0.17.0` | 1 652 296 байт | `e7dcf511a547a3e353fcc4bd581ac93c9bcfabdde2251920843391a97eafaef9` |
| `org.webjars.npm:mermaid` | `11.14.0` | 16 257 239 байт | `fcc17f377c0f657513a66735d41a3095cad37e8059d80d39f91458cfbed` |

Исходники JAR и опубликованных SHA-1: [KaTeX](https://repo.maven.apache.org/maven2/org/webjars/npm/katex/0.17.0/), [Mermaid](https://repo.maven.apache.org/maven2/org/webjars/npm/mermaid/11.14.0/). На исходной ревизии байты JAR были получены непосредственно с Maven Central по HTTPS, SHA-1 совпали с registry, а SHA-256 вычислены локально. **SHA-1 того же сервера подтверждает только совпадение с registry**, но не является независимой подписью автора.

## Если pin не совпал

1. Не выключать SHA guard и **не** подставлять автоматически хеш упавшей сборки или `distribution-sha256.txt`.
2. Проверить точную координату Maven (`group:artifact:version`) и получить JAR заново по HTTPS из доверенного источника. Проверить опубликованный SHA-1 и, если имеется, `.asc` **с независимо проверенным ключом издателя**. Сам факт наличия `.asc` не доказывает доверие.
3. Сопоставить точные байты и локальный SHA-256, разобраться в причине несовпадения; только после объяснения и code review отдельным diff обновить версию/pin с provenance.
4. Прогнать `:app:prepareResponseRendererAssets`, `python3 tools/verify_response_renderer_assets.py`, требуемые JVM/Android WebView тесты и Debug/Release CI по текущим правилам. Static scanner проверяет известные импорты, лицензии и font URL, но не доказывает runtime/computed imports или безопасность WebView.

Прежний baseline и diff изменений хранятся в Git-истории; разовые числа о размере извлечённых assets и старые отчёты о тестах здесь больше не поддерживаются.

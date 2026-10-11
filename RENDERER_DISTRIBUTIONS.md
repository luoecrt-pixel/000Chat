# Renderer distributions: проверенный первый baseline — 10 октября 2026

Jar bytes получены по HTTPS непосредственно из Maven Central; опубликованный
SHA-1 каждого jar совпал. Вычисленный SHA-256 закреплён в
config/response-renderer-sha256.properties. SHA-1 с того же сервера подтверждает
соответствие скачанного артефакта registry, а не независимую подпись автора.
Это первый доверенный TLS baseline; будущие сборки обязаны совпадать по SHA-256.

## Если проверка закреплённого SHA не проходит (ревью 40)

Не перезаписывать ожидаемый SHA значением из упавшей сборки, не отключать guard
и не принимать generated distribution-sha256.txt за доказательство доверия.
Сначала сверить точные group/artifact/version и jar bytes с Maven Central по
URL ниже; проверить опубликованный .sha1 и, если имеется, соседний .asc.
SHA-1, полученный с того же сервера, подтверждает совпадение registry bytes,
но не является независимой подписью. Наличие .asc тоже не доказывает подлинность:
проверять подпись нужно с проверенным ключом издателя и фиксировать происхождение ключа.
Отсутствие .asc не разрешает автоматически заменить pin.
До объяснения расхождения проверка остаётся ошибкой. Обоснованное изменение
версии/pin оформлять отдельным проверяемым diff с источниками и результатами
сравнения, сохраняя историю прежнего baseline.

| Артефакт | Размер jar, байт | Закреплённый SHA-256 |
| --- | ---: | --- |
| katex:0.17.0 | 1652296 | `e7dcf511a547a3e353fcc4bd581ac93c9bcfabdde2251920843391a97eafaef9` |
| mermaid:11.14.0 | 16257239 | `fcc17f377c0f657513a66735d41a3095cad37e8059d80e2d80d39f91458cfbed` |

Источники bytes и опубликованных checksum:

- https://repo.maven.apache.org/maven2/org/webjars/npm/katex/0.17.0/katex-0.17.0.jar
- https://repo.maven.apache.org/maven2/org/webjars/npm/katex/0.17.0/katex-0.17.0.jar.sha1
- https://repo.maven.apache.org/maven2/org/webjars/npm/mermaid/11.14.0/mermaid-11.14.0.jar
- https://repo.maven.apache.org/maven2/org/webjars/npm/mermaid/11.14.0/mermaid-11.14.0.jar.sha1

## Фактические проверки ресурсов

Python извлёк реальные закреплённые jar теми же правилами отбора, что Gradle task.
tools/verify_response_renderer_assets.py проверил 82 min Mermaid модуля, обнаруженные
literal imports, root licenses и все CSS font URLs: PASS. Исправлен ложный import
внутри строки CSS "@import"; comments/strings больше не принимаются за module statement.
Сканер не доказывает computed imports/template execution; WebView тест остаётся обязательным.

| Assets | До изменения отбора, байт | После, байт |
| --- | ---: | ---: |
| katex | 1372647 | 1372647 |
| mermaid | 11713439 | 3116521 |

Mermaid: исключено 8 596 918 байт исходных assets, без лицензий и min graph потерь.
Это uncompressed asset bytes; уменьшение сжатого APK не измерено. KaTeX состав прежний.

Gradle task ordering/Release guard execution и Android engine rendering здесь не
запускались. Нужны compile/JVM/lint/Android и реальный KaTeX/Mermaid WebView fixture.
Release теперь имеет настоящие ожидаемые SHA; отдельная задача заполнения ключей снята.

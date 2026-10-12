# NEXT_AGENT_HANDOFF

**Last updated:** 2026-10-12 11:18 +03:00
**Следующий рабочий срез:** Восстановить GPT: instructions и полный контекст.
**Code SHA:** `cceacdd8c392b2a69338f2313c6db137d210f307`
**Main baseline:** `4478fa73d54218dc4289089b3ca5a80af76e608b` (#144). Текущий PR уборки владелец merge делает сам; не объединять автоматически.

## Текущий статус

4.17.0, versionCode 64, SQLite v22. Код интегрирован в #141, последующие #142–#144 меняли документацию. GPT сейчас блокируется HTTP400: `instructions` отправляется массивом вместо строки; владелец подтвердил это логом 12 октября в 10:14 МСК. Исправление ещё не сделано.

На code SHA успешно завершены [Debug 38175698456](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698456) (39/39 API34 device tests) и [Release 38175698458](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698458). [Verify #144 38219213572](https://github.com/luoecrt-pixel/000Chat/actions/runs/38219213572) прошёл на docs head `988854b539f520618541a8587fcdbe49e42dab4d`. Mocks/эмулятор не доказывают live acceptance и физический телефон.

Для установки прежней сборки: [Debug artifact 11716107416](https://github.com/luoecrt-pixel/000Chat/actions/runs/38175698456/artifacts/11716107416), `app-debug.apk`, срок до 17 октября 00:27 МСК. Он не содержит будущих исправлений. Release APK/AAB unsigned; production-подпись и публикация не завершены. Не менять signing identity: стабильный Debug key использует `DEBUG_KEYSTORE_BASE64`, когда настроен.

## Следующая работа

1. **P0 — GPT:** `GenerationInstructions.openAi()` + MainActivity request builder. Исправить type `instructions`, сохранив все instruction roles/смысл; strict mock должен отклонять прежний payload. Проверить OA-3: incomplete/failed → следующий Send без потери последней реплики; OA-4: account/missing chain.
2. **UI-поручения:** чат виден после первого Send; per-chat позиция чтения сохраняется при фоне/switch; направление отпускания и 30% slow drag шторки; плавность, общий renderer и белая/серая темы. Подробные критерии, исходный лог и 10 референсов находятся в [прежнем файле аудита](docs/audits/2026-10-12-openai-followup.md), там же дополнять задачи и фотографии.
3. **Оставшиеся риски:** OA-2 — rollback same-request checkpoint (SQL reproduced), OA-5 — pre-ID 120s cleanup живого owner (source scenario), OA-6 — bare key в error (function reproduced). Android interleavings/live не повторялись. CSV/HTML MIME — второстепенный дефект; весь разбор в том же аудите.

Новые UI/продуктовые идеи, controller/Compose rewrite и переработку accounting сейчас не добавлять. Пакет от `4111b83a` уже интегрирован; повторно его не применять. Не называть pending задачу закрытой из-за старого green CI.

## Обязательные ограничения

- Stop и terminal commit — request-aware SQLite CAS; поздний callback не меняет другой request/chat. UI: immutable chatId/generation/assistantItemId и main-thread gate; при возврате Activity реконсиляция.
- Полная история, local originals и signed Claude replay сохраняются. `fingerprint v1` — точные bytes, не mtime/size или объединение fragment digests. Upload cache учитывает bytes/name/MIME/purpose/account; eviction/reset не вызывает автоматический paid resend.
- Ручной effort не понижать молча; Claude verbosity не переопределяет effort. Cache по умолчанию `1h`. Граница `5h` относится к одному ответу/recovery, `5M` — суммарному reported usage OpenRouter complete и всех tool rounds, включая повторный input. Missing usage/cost остаётся unknown, не нулём.
- Не вводить idle timeout, fixed tool rounds, скрытый max_output или тихое усечение. Пустой max_output и legacy 128000 означают отсутствие параметра; выбранное другое положительное значение передаётся. JSON null не текст; сообщения ошибки не добавлять в assistant.text или signed context.
- Оригинал — default; совместимая копия/OCR выбираются явно. Не добавлять отменённые OR cost/size confirmations. File-backed values копировать через JsonProtocol и писать StreamingJsonRequestBody, не materialize Base64/JSONObject.toString. TEXT/UTF-8/NUL в SQLite читать полностью; SQL slices не являются лимитом ответа.
- TLS, Android JPEG validation, pinned SHA и renderer isolation не ослаблять. Model HTML/SVG: untrusted JS/network/file/content/storage off; scientific JS только app-owned с owned document/APK assets и без Android bridge. Preview guards 16k LaTeX/50k Mermaid/400k math HTML/12s preparation — fallback с полным source, не ограничение генерации.
- GitHub tools сохраняют repo/ref allow-list и подтверждение write; range text согласован через ETag/UTF-16 offsets. Byte/parser/cache guards дают явную ошибку, не исчезновение файла/контекста. Не имитировать бинарный Office/PDF обычным текстом.
- Рабочий этап — не более **40 минут**. Не выполнять платные API-вызовы и не управлять телефоном без отдельного поручения. При остановке записать только состояние/remaining в текущем handoff/PR, без новых архивов/отчётов-дублей.

## Проверки и CI

Перед изменением сверить main/open PR и локальный diff. Воспроизвести дефект на его границе: actual wire JSON, DB или Activity action. Сначала targeted проверки; tests «написаны» не означают «выполнены». Local source/JVM, Android, HTTP mock и live evidence указывать раздельно. SDK окружение: JDK17, Gradle8.9, SDK35, API34 emulator; в каждом новом окружении проверять работоспособность.

Runtime/app tests/build workflow changes требуют Successful push Debug + Release на последнем code SHA, включая instrumentation; запуск по branch `[ci]`, main исключён. Docs-only после code commit допустимы при неизменном runtime; final PR head обязан пройти Verify. Failed/Skipped/Cancelled не Success. Не менять workflows/publicity и не merge текущий PR за владельца.

Команды: `python3 tools/verify_handoff.py`; `python3 tools/verify_v4_17_0.py`; `python3 tools/verify_implementation.py`; targeted `gradle :app:testDebugUnitTest --tests '<ChangedTest>'`; compile `gradle :app:assembleDebug :app:assembleDebugAndroidTest`; затем затронутые device cases и итоговый CI. Новый verifier проверять позитивным и минимум тремя содержательными negative mutations; синтаксическая ошибка не проверка правила. Без SDK не объявлять Android compile/run выполненным.

## Renderer: происхождение и SHA

Ожидаемые значения находятся в [config/response-renderer-sha256.properties](config/response-renderer-sha256.properties). Первый baseline — 10 октября: точные JAR получены по HTTPS Maven Central, опубликованный SHA-1 совпал, вычисленный SHA-256 закреплён. Это TLS/registry baseline, не независимая подпись автора. При mismatch расследовать provenance, не переписывать pins и не отключать guard ради green CI.

| Artifact | JAR bytes | SHA-256 |
| --- | ---: | --- |
| KaTeX 0.17.0 | 1 652 296 | `e7dcf511a547a3e353fcc4bd581ac93c9bcfabdde2251920843391a97eafaef9` |
| Mermaid 11.14.0 | 16 257 239 | `fcc17f377c0f657513a66735d41a3095cad37e8059d80e2d80d39f91458cfbed` |

Источники: [KaTeX JAR](https://repo.maven.apache.org/maven2/org/webjars/npm/katex/0.17.0/katex-0.17.0.jar) / [.sha1](https://repo.maven.apache.org/maven2/org/webjars/npm/katex/0.17.0/katex-0.17.0.jar.sha1); [Mermaid JAR](https://repo.maven.apache.org/maven2/org/webjars/npm/mermaid/11.14.0/mermaid-11.14.0.jar) / [.sha1](https://repo.maven.apache.org/maven2/org/webjars/npm/mermaid/11.14.0/mermaid-11.14.0.jar.sha1). Проверять `.asc` только с проверенным publisher key; его отсутствие не разрешает заменить SHA.

Проверка 82 modules/fonts/licenses и packaged engine fixture прошли на code SHA выше. Это не proof всех inline height/toggle/Copy/reopen ветвей телефона; сохранять source fallback и выполнять changed renderer/device tests.

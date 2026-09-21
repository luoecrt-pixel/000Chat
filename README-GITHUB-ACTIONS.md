
# Сборка APK с Android

## Один раз, перед первой сборкой: одинаковая подпись APK
Без этого шага каждая сборка на GitHub подписывается новым случайным ключом,
и Android будет отказываться ставить новую версию поверх старой («конфликтует с другим пакетом»).

1. В репозитории: Settings → Secrets and variables → Actions → New repository secret.
2. Имя секрета: `DEBUG_KEYSTORE_BASE64`.
3. Значение: содержимое файла `astra-debug.keystore.b64` (base64), которое пришлёт Claude/ChatGPT.
4. Save.

Это нужно сделать один раз. Все последующие сборки (4.4, 4.5, ...) будут подписаны
одним и тем же ключом и будут ставиться как обновление, без удаления приложения.
Если секрет не задан, сборка всё равно пройдёт, но каждая новая версия снова
потребует удаления предыдущей.

## Сборка

1. Открой GitHub в Chrome и создай пустой репозиторий.
2. Нажми Add file → Upload files.
3. Загрузи ВСЕ файлы и папки из `OpenAIApiChat`.
4. Commit changes.
5. Открой вкладку Actions.
6. Нажми `Build Android APK`.
7. Нажми `Run workflow`.
8. После окончания job открой её страницу и раздел Artifacts.
9. Скачай `OpenAIApiChat-Astra-debug`.
10. Распакуй ZIP и установи `app-debug.apk`.

API key не нужен для сборки и не должен храниться в репозитории.



### v4.14.1 workflow

The canonical CI workflow is `.github/workflows/build.yml`. It builds with JDK 17, Android SDK 35 and Gradle 8.9, runs `tools/verify_v4_14.py` before compilation, and uploads the debug APK as an artifact. The workflow uses current GitHub Actions major versions (checkout v7, setup-java v6, setup-android v4, setup-gradle v6, upload-artifact v7).


CI packaging note: build.yml now supports source archives that are either flat or wrapped in one top-level project directory, and resolves the Gradle project root before verification/build.

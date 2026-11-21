# FreeAI Android MVP

A dark, tablet-friendly Android client for a running FreeLLMAPI-compatible OpenAI API router.

## v0.6.0 — студия и автономность
- **Студия** (вкладка): фото, видео, дизайн (SVG/HTML), планировка (SVG). Дизайн и планировку можно править словами, сохранять PNG и код. Нужен FreeAI Cloud.
- **Предпросмотр кода**: блоки ```html и ```svg в чате открываются безопасно (без JavaScript и сети).
- **Автономные задачи** на сервере и команды бота `/task`, `/tasks`, `/stopall`. Задачи только читают и уведомляют.
- **Portals и MRKT**: диагностика `/diag`, токен MRKT из файла или по initData, скрипт `tools/mrkt_token.py`. Описание в `server/README.md`.
- **Лимиты сняты**: контекст 60 сообщений, до 8 вызовов инструментов подряд и до 8 шагов плана.
- Tools и специализации переехали в Настройки → «Инструменты и специализации».

## v0.5.0 — объединённая версия
Объединены этот проект и FreeAI-v0_4_1-Fixed (его исходники лежат в `legacy/`). Единый бэкенд теперь `server/` (Node, проверен автотестом), клиент Android собран на моей кодовой базе с идеями из загруженной версии (режим `mode` на сервере, зашифрованное хранение, отключённый бэкап).
- **Серверный мозг**: инструменты, проверка ответа рецензентом, совет двух моделей, агенты (план → шаги → итог), код с ревью.
- **Telegram-бот** на сервере: чат с ИИ, код, агенты, курсы, цены подарков, алерты.
- **Цены подарков**: Fragment (без ключей), Portals (Partner API), MRKT (токен сессии). Вкладка «Крипто» и инструмент `gift_floor`.
- **Фото**: скрепка рядом с файлом; модель описывает снимок и читает текст. Определение личности по лицу и поиск людей по фото не поддерживаются.
- **Шифрование**: история чатов и память хранятся в зашифрованных файлах (ключ в Android Keystore); бэкап Android отключён.
- **GRAM**: с 15 июня 2026 TON называется GRAM; подписи обновлены, id на CoinGecko остаётся `the-open-network`, при смене поправь в списке монет.

## v0.4.0 — полный FreeAI
- **Умный выбор модели.** Модель «Авто» классифицирует запрос (код, сложная задача, быстрый ответ, текст) и подбирает модель по названию; при ошибке или лимите пробует следующую. Использованная модель видна под ответом. Подбор эвристический, по именам моделей.
- **Инструменты (реальные).** Поиск в интернете (DuckDuckGo, запасной — Википедия), чтение страниц, вложенные файлы (скрепка), калькулятор, дата и время, курсы CoinGecko, баланс и NFT TON-кошелька, запись в память. Каждый переключается на вкладке Tools.
- **Агенты.** Режим Agents: план из шагов → последовательное выполнение каждого шага с инструментами → итоговый ответ. Прогресс виден в чате, кнопка «Стоп» прерывает. Работает, пока приложение открыто.
- **Code.** Блоки кода с копированием, быстрые действия (объяснить, баг, рефакторинг, тесты), загрузка файлов. Код не выполняется.
- **Крипто.** Курсы, TON-кошелёк только для чтения (адрес, без ключей), Telegram-бот для уведомлений (поиск chat id, тест), ценовые алерты: в приложении или на сервере 24/7.
- **Облако.** Папка `server/`: прокси к FreeLLMAPI, синхронизация чатов и памяти, серверные алерты. См. `server/README.md`.

## v0.3.0 — память
- История чатов сохраняется на устройстве (иконка «История»), последний чат открывается при запуске
- Долговременная память: заметки + стиль общения (иконка «Память»), подставляются в системный промпт
- Команда «запомни: …» сохраняет заметку сразу; автообучение (можно выключить) делает отдельный запрос после ответа
- Данные лежат в закрытой папке приложения (chats.json, memory.json), без новых зависимостей

## Included
- Chat, Code, Agents, Tools, and Specializations tabs
- Multi-select specializations (programming, research, learning, writing, business, finance, design, analytics, marketing)
- Model selection from `/v1/models?available=true`
- Chat completions through `/v1/chat/completions`
- Encrypted local storage for API key and endpoint
- GitHub Actions workflow to build a debug APK

## Important limitations
This is an Android **client**, not the FreeLLMAPI server and not a provider account. It does not create free model capacity or guarantee unlimited usage. You must run/deploy the FreeLLMAPI router and provide its endpoint and unified key. The documented local default is `http://127.0.0.1:3001/v1` when the router runs on the same Android device.
The Tools tab currently configures assistant instructions/preferences; it does not yet implement real browser search, file-system access, or external integrations. Agents mode plans and works through tasks in a model response; it is not a background autonomous agent with device permissions. File attachments, voice, image input, streaming are not included in this MVP.

## Build APK without Android Studio
1. Create a GitHub repository.
2. Upload the **contents** of this folder to the repository root (so `app/`, `settings.gradle.kts`, and `.github/` are at the top level).
3. Open the repository's **Actions** tab.
4. Run **Build FreeAI APK** (or push to `main`).
5. Open the completed run and download artifact **FreeAI-APK**.
6. Inside the artifact, install `app-debug.apk` on Android.

## Build locally
Open this folder in Android Studio and let Gradle sync, then run `gradle assembleDebug` or use Build > Build APK(s).

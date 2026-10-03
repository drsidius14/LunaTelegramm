# Luna Telegram — Android prototype

Прототип делает:

1. Устанавливается как обычное Android-приложение.
2. Авторизует **пользовательский Telegram-аккаунт** через TDLib.
3. Показывает группы/супергруппы.
4. Позволяет выбрать группу.
5. Поднимает локальный HTTP API на порту `8765`.
6. API защищён случайным Bearer-токеном, сохранённым только на телефоне.

## Важно

Для Telegram API нужен собственный `api_id` и `api_hash`.
Официальная инструкция: https://core.telegram.org/api/obtaining_api_id

Полученные значения положить в `gradle.properties`:

TELEGRAM_API_ID=123456
TELEGRAM_API_HASH=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

После сборки APK можно установить на Android.

## Сборка

Открыть папку в Android Studio и выполнить Build → Build APK(s).

Либо:

`./gradlew assembleDebug`

APK будет:

`app/build/outputs/apk/debug/app-debug.apk`

## Архитектура

Android → TDLib → Telegram account
                  ↓
             selected group
                  ↓
        local HTTP API :8765

В текущем прототипе HTTP API намеренно не публикуется в Интернет.

Чтобы облачная Luna могла получать сообщения напрямую, нужен отдельный безопасный внешний мост/MCP/connector. Просто открыть порт телефона недостаточно: ChatGPT не имеет прямого доступа к локальной сети телефона.

## Безопасность

- Не передавать Luna `api_hash`.
- Не передавать файл TDLib-сессии.
- Не публиковать Bearer-токен.
- Telegram авторизация остаётся на телефоне.

## Что уже работает в прототипе

`/messages` синхронно получает последние сообщения выбранной группы через TDLib и возвращает JSON. Ограничение — локальный API пока рассчитан на дальнейшее подключение отдельного безопасного моста/MCP.

Пример:
`GET /health`
`GET /selected`
`GET /messages?limit=50`

Для всех запросов нужен заголовок `Authorization: Bearer <token>`, который показывается приложением.

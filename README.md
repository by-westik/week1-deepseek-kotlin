# Задания первой недели

Репозиторий с выполнением учебных заданий первой недели.

## День 7. Сохранение контекста

CLI-приложение на Kotlin JVM запускает простого агента для диалога с LLM через DeepSeek API и сохраняет контекст между запусками.

Агент:

- принимает сообщения пользователя в консоли;
- хранит историю диалога в JSON-файле;
- загружает историю при следующем запуске;
- отправляет историю сообщений в LLM через HTTP API;
- получает ответ модели;
- выводит ответ и статистику в CLI.

Важно: агент реализован отдельной сущностью `DeepSeekAgent`. HTTP-вызов инкапсулирован в `DeepSeekClient`, сохранение истории инкапсулировано в `JsonMessageStore`, а агент отвечает за диалоговую логику и обновление истории сообщений.

## Что нужно

- JDK 17 или новее
- API-ключ DeepSeek

## Подготовка

Задайте API-ключ в переменной окружения:

```bash
export DEEPSEEK_API_KEY="ваш_api_ключ"
```

Ключ не хранится в коде и читается только из переменной окружения `DEEPSEEK_API_KEY`.

## Запуск

```bash
./gradlew run
```

После запуска появится интерактивный диалог:

```text
DeepSeek Agent
Введите сообщение. Для выхода: /exit, /quit или пустой EOF.
История: /path/to/project/chat-history.json
Загружено сообщений из истории: 0

Вы:
```

Введите сообщение и нажмите Enter. Агент отправит запрос в DeepSeek API, выведет ответ и сохранит историю диалога в `chat-history.json`. При следующем запуске приложение загрузит этот файл и продолжит диалог с прежним контекстом.

Для выхода используйте:

```text
/exit
/quit
exit
quit
выход
```

## Хранение контекста

По умолчанию история хранится в файле:

```text
chat-history.json
```

Файл содержит массив сообщений в формате Chat Completions:

```json
[
  {
    "role": "user",
    "content": "Запомни кодовое слово: фиалка-72"
  },
  {
    "role": "assistant",
    "content": "Запомнила: фиалка-72."
  }
]
```

Можно указать другой файл истории:

```bash
./gradlew run --args="--history-file my-dialog.json"
```

Практическая проверка:

1. Запустите приложение и попросите агента что-то запомнить.
2. Выйдите командой `/exit`.
3. Запустите приложение снова.
4. Спросите агента о том, что было в прошлом запуске.

Если история загрузилась, агент ответит с учетом предыдущих сообщений.

## Параметры запуска

По умолчанию размер ответа ограничен значением `500` токенов. Его можно изменить:

```bash
./gradlew run --args="--max-tokens 200"
```

Можно передать stop sequence:

```bash
./gradlew run --args="--max-tokens 200 --stop END"
```

Можно управлять температурой:

```bash
./gradlew run --args="--temperature 0"
./gradlew run --args="--temperature 0.7"
./gradlew run --args="--temperature 1.2"
```

Можно выбрать модель и thinking mode:

```bash
./gradlew run --args="--model deepseek-v4-flash --thinking disabled"
./gradlew run --args="--model deepseek-v4-flash --thinking enabled"
./gradlew run --args="--model deepseek-v4-pro --thinking enabled"
```

Параметры можно комбинировать:

```bash
./gradlew run --args="--model deepseek-v4-pro --thinking enabled --max-tokens 700 --temperature 0.7 --history-file my-dialog.json"
```

## Статистика диалога

Во время диалога приложение выводит только ответы агента. После выхода из диалога приложение печатает общую статистику за всю сессию:

```text
=== Статистика диалога ===
Модель: deepseek-v4-flash
Thinking mode: disabled
Ответов агента: 3
Общее время ответов: 7.24 сек
Prompt tokens: 420
Completion tokens: 810
Total tokens: 1230
Prompt cache hit tokens: 0
Prompt cache miss tokens: 420
Примерная стоимость: $0.00028560
```

Стоимость считается примерно по тарифам DeepSeek за 1M токенов:

- `deepseek-v4-flash`: input cache hit `$0.0028`, input cache miss `$0.14`, output `$0.28`
- `deepseek-v4-pro`: input cache hit `$0.003625`, input cache miss `$0.435`, output `$0.87`

Ссылка на цены: https://api-docs.deepseek.com/quick_start/pricing

## Структура проекта

```text
.
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
├── chat-history.json
└── src/main/kotlin/Main.kt
```

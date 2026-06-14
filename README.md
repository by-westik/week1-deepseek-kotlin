# Задания первой недели

Репозиторий с выполнением учебных заданий первой недели.

## День 8. Работа с токенами

CLI-приложение на Kotlin JVM запускает простого агента для диалога с LLM через DeepSeek API, сохраняет контекст между запусками и показывает, как растут токены и стоимость по мере диалога.

Агент:

- принимает сообщения пользователя в консоли;
- хранит историю диалога в JSON-файле;
- загружает историю при следующем запуске;
- считает токены текущего запроса;
- считает токены всей истории диалога;
- показывает фактические токены ответа модели из API usage;
- сравнивает прогноз с лимитом модели;
- отправляет историю сообщений в LLM через HTTP API;
- получает ответ модели;
- выводит ответ и статистику в CLI.

Важно: агент реализован отдельной сущностью `DeepSeekAgent`. HTTP-вызов инкапсулирован в `DeepSeekClient`, сохранение истории инкапсулировано в `JsonMessageStore`, а подсчет токенов вынесен в `SimpleTokenCounter`.

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

Перед каждым запросом агент выводит оценку токенов:

```text
=== Токены перед запросом ===
Текущий запрос: 12
История диалога: 500041
Prompt всего: 500053
Max response tokens: 120
Prompt + максимум ответа: 500173
Лимит модели: 1048576
Использование лимита: 47.70%
Статус: в пределах лимита
```

После ответа API агент показывает фактические токены и стоимость шага:

```text
=== Факт по ответу API ===
Prompt tokens: 74
Completion tokens: 5
Total tokens: 79
Стоимость этого шага: $0.00001176
Накопленная стоимость диалога: $0.00001176
```

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

## Работа с токенами

Подсчет токенов до запроса выполняется локально простым оценочным счетчиком. Фактические `prompt_tokens`, `completion_tokens` и `total_tokens` берутся из ответа API после запроса.

Для безопасной проверки больших историй без отправки запроса в LLM используйте:

```bash
./gradlew run --args="--dry-run-tokens --history-file token-fixtures/long-dialog-500k.json"
```

Если прогноз превышает лимит модели, агент по умолчанию не отправляет запрос:

```text
Статус: превышение лимита
Запрос не отправлен: прогноз превышает лимит модели.
```

Чтобы специально отправить слишком большой запрос и увидеть ошибку API на практике, добавьте:

```bash
./gradlew run --args="--allow-over-limit --history-file token-fixtures/overflow-dialog-1048576.json"
```

## Тестовые истории

В проекте есть готовые JSON-файлы для сравнения:

```text
token-fixtures/short-dialog.json
token-fixtures/long-dialog-500k.json
token-fixtures/overflow-dialog-1048576.json
```

Сценарии:

```bash
./gradlew run --args="--dry-run-tokens --max-tokens 120 --history-file token-fixtures/short-dialog.json"
./gradlew run --args="--dry-run-tokens --max-tokens 120 --history-file token-fixtures/long-dialog-500k.json"
./gradlew run --args="--dry-run-tokens --max-tokens 120 --history-file token-fixtures/overflow-dialog-1048576.json"
```

Короткий диалог занимает доли процента лимита. Длинный диалог примерно на 500k токенов показывает, как растет цена prompt. Переполненная история содержит ранний факт в начале и большой шум дальше; при новом запросе прогноз выходит за лимит `1,048,576`, поэтому агент показывает, что такой запрос ломает бюджет контекста.

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
├── token-fixtures/
│   ├── short-dialog.json
│   ├── long-dialog-500k.json
│   └── overflow-dialog-1048576.json
└── src/main/kotlin/Main.kt
```

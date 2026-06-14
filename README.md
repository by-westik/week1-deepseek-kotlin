# Задания первой недели

Репозиторий с выполнением учебных заданий первой недели.

## День 6. Первый агент

CLI-приложение на Kotlin JVM запускает простого агента для диалога с LLM через DeepSeek API.

Агент:

- принимает сообщения пользователя в консоли;
- хранит историю текущего диалога;
- отправляет историю сообщений в LLM через HTTP API;
- получает ответ модели;
- выводит ответ и статистику в CLI.

Важно: агент реализован отдельной сущностью `DeepSeekAgent`. HTTP-вызов инкапсулирован в `DeepSeekClient`, а агент отвечает за диалоговую логику и историю сообщений.

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

Вы:
```

Введите сообщение и нажмите Enter. Агент отправит запрос в DeepSeek API, выведет ответ и сохранит его в истории диалога. Следующие сообщения будут отправляться вместе с предыдущим контекстом.

Для выхода используйте:

```text
/exit
/quit
exit
quit
выход
```

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
./gradlew run --args="--model deepseek-v4-pro --thinking enabled --max-tokens 700 --temperature 0.7"
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
└── src/main/kotlin/Main.kt
```

# Задания первой недели

Репозиторий с выполнением учебных заданий первой недели.

## День 1. Первый запрос к LLM через API

Минимальное CLI-приложение на Kotlin JVM, которое отправляет запрос в DeepSeek API и выводит ответ модели в консоль.

## Что нужно

- JDK 17 или новее
- API-ключ DeepSeek

## Подготовка

Задайте API-ключ в переменной окружения:

```bash
export DEEPSEEK_API_KEY="ваш_api_ключ"
```

Ключ не хранится в коде и читается только из переменной окружения `DEEPSEEK_API_KEY`.

Задайте запрос одним из двух способов.

Вариант 1: через переменную окружения:

```bash
export DEEPSEEK_PROMPT="Объясни Kotlin suspend fun простыми словами"
```

Вариант 2: через файл `prompt.txt`:

```text
Объясни Kotlin suspend fun простыми словами
```

## Запуск

```bash
./gradlew run
```

Если запускаете проект в первый раз, Gradle Wrapper сам скачает нужную версию Gradle.

Приложение отправит запрос в DeepSeek API и выведет только текст ответа модели.

В коде увеличены таймауты HTTP-запроса, потому что LLM может отвечать дольше обычного HTTP-сервиса.

По умолчанию размер ответа ограничен значением `500` токенов. Его можно изменить при запуске:

```bash
./gradlew run --args="--max-tokens 200"
```

Также можно передать stop sequence:

```bash
./gradlew run --args="--max-tokens 200 --stop END"
```

Для задания про температуру можно передать параметр `temperature`:

```bash
./gradlew run --args="--temperature 0"
./gradlew run --args="--temperature 0.7"
./gradlew run --args="--temperature 1.2"
```

Параметры можно комбинировать:

```bash
./gradlew run --args="--max-tokens 200 --temperature 0.7 --stop END"
```

В этом случае приложение передаст в DeepSeek API параметры `max_tokens`, `temperature` и `stop`.

Для задания про сравнение моделей можно выбрать модель и thinking mode:

```bash
./gradlew run --args="--model deepseek-v4-flash --thinking disabled"
./gradlew run --args="--model deepseek-v4-flash --thinking enabled"
./gradlew run --args="--model deepseek-v4-pro --thinking enabled"
```

После ответа приложение выведет статистику:

```text
=== Статистика ===
Модель: deepseek-v4-flash
Thinking mode: disabled
Время ответа: 2.41 сек
Prompt tokens: 120
Completion tokens: 350
Total tokens: 470
Prompt cache hit tokens: 0
Prompt cache miss tokens: 120
Примерная стоимость: $0.00011520
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

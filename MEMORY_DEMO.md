# Демонстрация памяти и персонализации

Пример показывает финансового CLI-ассистента, у которого профиль пользователя хранится
в `LongTermMemory`, перед каждым запросом загружается в `WorkingMemory` и влияет на стиль ответа.

Запуск для нового пользователя:

```bash
./gradlew run --args="--user alice --memory-dir assistant-memory"
```

Отдельно указывать `--history-file`, `--summary-file`, `--facts-file` и `--branch-dir`
больше не нужно. Если путь не задан явно, приложение создает директорию пользователя:

```text
assistant-memory/alice/
  chat-history.json
  chat-summary.txt
  chat-facts.json
  chat-branches/
  long-term-memory.json
  working-memory.json
```

При старте CLI показывает выбранные пути:

```text
Папка пользователя: /path/to/project/assistant-memory/alice
История: /path/to/project/assistant-memory/alice/chat-history.json
Рабочая память: /path/to/project/assistant-memory/alice/working-memory.json
Долговременная память: /path/to/project/assistant-memory/alice/long-term-memory.json
```

## Первый запуск и онбординг

Профиль отсутствует, поэтому перед первым обычным запросом ассистент задает вопросы:

```text
Профиль пользователя не найден. Перед первым обычным запросом запустится онбординг.

Вы: расскажи о погоде
Давайте настроим персонализацию ответов. Можно нажимать Enter, чтобы оставить значение по умолчанию.

Тон общения: 1 - формальный, 2 - дружелюбный, 3 - нейтральный [по умолчанию: 3]
> 2
Пол ассистента: 1 - мужской, 2 - женский, 3 - без рода [по умолчанию: 3]
> 2
Длина ответа: 1 - кратко, 2 - обычно, 3 - подробно [по умолчанию: 2]
> 1
Язык ответов, например ru или en [по умолчанию: ru]
> ru
Дополнительные пожелания к стилю [можно пропустить]
>

Профиль сохранен.
```

После этого запрос отправляется уже с profile-инструкцией:

```text
Агент: Я не вижу текущую погоду в реальном времени, но могу помочь быстро составить прогнозный чек-лист: город, температура, осадки и ветер. Если пришлешь город, подскажу, что проверить. 🙂
```

Следующий запрос тоже автоматически использует профиль:

```text
Вы: дай совет по продуктивности
Агент: Выбери одну главную задачу на день и поставь таймер на 25 минут. После этого сделай короткий перерыв и реши, продолжать ли. 🙂
```

## Memory Status после онбординга

```text
Вы: /memory-status

WorkingMemory (текущая задача, сохраняется до закрытия задачи)
  context:
    (пусто)
  intermediateResults:
    (пусто)
  flags:
    (пусто)
  loadedUserProfile:
    source = LongTermMemory.profile[user_profile]
    tone = Friendly
    gender = Female
    length = Short
    language = ru
    notes = (пусто)

LongTermMemory (пользователь: alice, JSON)
  user_profile:
    source = LongTermMemory.profile[user_profile]
    tone = Friendly
    gender = Female
    length = Short
    language = ru
    notes = (пусто)
```

## Изменение профиля через CLI

```text
Вы: /profile tone Formal
Профиль обновлен:
tone = Formal
gender = Female
length = Short
language = ru
notes = (пусто)

Вы: /profile length Detailed
Профиль обновлен:
tone = Formal
gender = Female
length = Detailed
language = ru
notes = (пусто)
```

## Повтор запросов после изменения профиля

Теперь ответы должны быть формальными и развернутыми:

```text
Вы: расскажи о погоде
Агент: У меня нет доступа к текущим погодным данным в реальном времени. Чтобы получить полезную оценку, уточните город и дату. В общем случае стоит проверить четыре параметра: температуру, вероятность осадков, скорость ветра и ощущаемую температуру. На основании этих данных можно решить, нужна ли теплая одежда, зонт или изменение маршрута.

Вы: дай совет по продуктивности
Агент: Начните с определения одной приоритетной задачи на день. Затем выделите для нее первый непрерывный рабочий блок на 25-50 минут и заранее уберите отвлекающие факторы. После блока зафиксируйте результат: что сделано, что мешало и какой следующий шаг требуется. Такой подход снижает переключение контекста и делает прогресс измеримым.
```

## Memory Status после изменения профиля

```text
Вы: /memory-status

WorkingMemory (текущая задача, сохраняется до закрытия задачи)
  loadedUserProfile:
    source = LongTermMemory.profile[user_profile]
    tone = Formal
    gender = Female
    length = Detailed
    language = ru
    notes = (пусто)

LongTermMemory (пользователь: alice, JSON)
  user_profile:
    source = LongTermMemory.profile[user_profile]
    tone = Formal
    gender = Female
    length = Detailed
    language = ru
    notes = (пусто)
```

## Сброс профиля

```text
Вы: /profile reset
Профиль пользователя удален. Онбординг запустится перед следующим обычным запросом.
```

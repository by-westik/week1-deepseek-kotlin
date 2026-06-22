# Демонстрация трех слоев памяти

Пример показывает финансового ассистента, который помогает пользователю разобрать
ежемесячный бюджет и накопления.

Запуск для отдельного пользователя:

```bash
./gradlew run --args="--user alice --memory-dir assistant-memory"
```

Начальное состояние можно проверить без API-ключа:

```bash
./gradlew run --args="--user alice --memory-status"
```

Пример вывода до взаимодействия:

```text
=== Memory Status ===

ShortTermMemory (текущая сессия, in-memory)
  (пусто)

WorkingMemory (текущая задача, сохраняется до закрытия задачи)
  context:
    (пусто)
  intermediateResults:
    (пусто)
  flags:
    (пусто)

LongTermMemory (пользователь: alice, JSON)
createdAt: 2026-06-22T10:00:00Z
updatedAt: 2026-06-22T10:00:00Z
  profile:
    (пусто)
  decisions:
    (пусто)
  knowledge:
    (пусто)
```

Фрагмент диалога:

```text
Вы: /work context goal=рассчитать безопасную сумму ежемесячных накоплений
WorkingMemory сохранена: context.goal

Вы: /work result free_cashflow=50000 рублей после расходов
WorkingMemory сохранена: result.free_cashflow

Вы: /work flag emergency_fund_needed=true
Флаг WorkingMemory сохранен: emergency_fund_needed = true

Вы: /profile risk_profile=консервативный
LongTermMemory.profile сохранен: risk_profile

Вы: /knowledge finance_rule=сначала резервный фонд, потом инвестиции
LongTermMemory.knowledge сохранено: finance_rule

Вы: /decision emergency_fund_target=6 месяцев расходов
LongTermMemory.decisions сохранено: emergency_fund_target
```

Вывод `/memory-status` после взаимодействия в той же сессии:

```text
=== Memory Status ===

ShortTermMemory (текущая сессия, in-memory)
  1. user: /work context goal=рассчитать безопасную сумму ежемесячных накоплений
  2. assistant: WorkingMemory сохранена: context.goal
  3. user: /work result free_cashflow=50000 рублей после расходов
  4. assistant: WorkingMemory сохранена: result.free_cashflow
  5. user: /work flag emergency_fund_needed=true
  6. assistant: Флаг WorkingMemory сохранен: emergency_fund_needed = true
  7. user: /profile risk_profile=консервативный
  8. assistant: LongTermMemory.profile сохранен: risk_profile
  9. user: /knowledge finance_rule=сначала резервный фонд, потом инвестиции
  10. assistant: LongTermMemory.knowledge сохранено: finance_rule
  11. user: /decision emergency_fund_target=6 месяцев расходов
  12. assistant: LongTermMemory.decisions сохранено: emergency_fund_target

WorkingMemory (текущая задача, сохраняется до закрытия задачи)
  context:
    goal = рассчитать безопасную сумму ежемесячных накоплений
  intermediateResults:
    free_cashflow = 50000 рублей после расходов
  flags:
    emergency_fund_needed = true

LongTermMemory (пользователь: alice, JSON)
createdAt: 2026-06-22T10:00:00Z
updatedAt: 2026-06-22T10:05:00Z
  profile:
    risk_profile = консервативный
  decisions:
    emergency_fund_target = 6 месяцев расходов
  knowledge:
    finance_rule = сначала резервный фонд, потом инвестиции
```

При выходе ассистент явно спрашивает, завершена ли текущая задача:

```text
Вы: /exit
Диалог завершен.
Текущая задача решена? Очистить WorkingMemory? [y/N]: n
WorkingMemory сохранена для следующей сессии.
```

После перезапуска `ShortTermMemory` снова пустая, но незавершенная `WorkingMemory`
загружается из `assistant-memory/working-alice.json`, а `LongTermMemory` загружается
из `assistant-memory/alice.json`.

Когда задача действительно решена, пользователь отвечает `y`:

```text
Вы: /exit
Диалог завершен.
Текущая задача решена? Очистить WorkingMemory? [y/N]: y
WorkingMemory очищена.
```

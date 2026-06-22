/**
 * A non-negotiable assistant constraint checked before ordinary user requests.
 *
 * New constraints are added by introducing another subclass and registering it
 * in [InvariantRegistry.default].
 */
sealed class Invariant(
    val id: String,
    val description: String,
) {
    /** Returns a violation when [query] breaks this invariant. */
    abstract fun check(query: String): InvariantViolation?

    /**
     * Returns a violation while also considering the current task context.
     *
     * Most invariants are context-independent and use [check]. Domain checks can
     * allow task-specific input such as "ограничения" when a task is active.
     */
    open fun check(
        query: String,
        taskContext: TaskContext?,
    ): InvariantViolation? {
        return check(query)
    }

    /** Restricts financial calculations to Russian rubles and Russia. */
    data object CurrencyCountryInvariant : Invariant(
        id = "currency_country",
        description = "Расчеты поддерживаются только в рублях и для России.",
    ) {
        override fun check(query: String): InvariantViolation? {
            val normalized = query.normalizedForInvariantChecks()
            val foreignCurrency = detectForeignCurrency(normalized)
            val foreignCountry = FOREIGN_COUNTRY_MARKERS.firstOrNull { it in normalized }
            val ambiguousCurrency = foreignCurrency == null && hasAmbiguousNonRubleCurrencyRequest(normalized)

            if (foreignCurrency == null && foreignCountry == null && !ambiguousCurrency) {
                return null
            }

            val reason = when {
                foreignCurrency != null && foreignCountry != null -> "обнаружены другая валюта ($foreignCurrency) и другая страна ($foreignCountry)"
                foreignCurrency != null -> "обнаружена другая валюта ($foreignCurrency)"
                ambiguousCurrency -> "обнаружена неоднозначная просьба пересчитать в неподдерживаемую валюту"
                else -> "обнаружена другая страна ($foreignCountry)"
            }

            return InvariantViolation(
                invariant = this,
                explanation = "Запрос не обработан, потому что я поддерживаю расчеты только в рублях и для России: $reason.",
            )
        }

        private fun detectForeignCurrency(normalizedQuery: String): String? {
            FOREIGN_CURRENCY_MARKERS.firstOrNull { it in normalizedQuery }?.let { marker ->
                return marker
            }

            normalizedQuery.invariantTokens().firstOrNull { token ->
                token.looksLikeDollarToken() || token.startsWith("бакс")
            }?.let { token ->
                return token
            }

            return null
        }

        private fun hasAmbiguousNonRubleCurrencyRequest(normalizedQuery: String): Boolean {
            val mentionsCurrency = AMBIGUOUS_CURRENCY_MARKERS.any { it in normalizedQuery }
            val mentionsRubles = RUBLE_MARKERS.any { it in normalizedQuery }

            return mentionsCurrency && !mentionsRubles
        }

        private val FOREIGN_CURRENCY_MARKERS = setOf(
            "доллар", "долларов", "доллары", "usd", "$",
            "бакс", "баксов",
            "евро", "eur", "€",
            "фунт", "фунтов", "gbp", "£",
            "юань", "юаней", "cny", "¥",
            "тенге", "kzt",
            "гривна", "гривен", "uah",
        )

        private val AMBIGUOUS_CURRENCY_MARKERS = setOf(
            "в этой валюте",
            "в эту валюту",
            "этой валют",
            "эту валют",
            "другой валют",
            "иностранной валют",
            "пересчитай в валют",
            "пересчитать в валют",
        )

        private val RUBLE_MARKERS = setOf(
            "руб", "рубл", "₽",
        )

        private val FOREIGN_COUNTRY_MARKERS = setOf(
            "сша", "америк", "usa", "united states",
            "германи", "germany",
            "франци", "france",
            "итал", "italy",
            "испан", "spain",
            "китай", "china",
            "казахстан", "украин", "беларус", "турци", "турция",
        )
    }

    /** Restricts the assistant to economics and personal finance. */
    data object DomainInvariant : Invariant(
        id = "domain",
        description = "Ассистент отвечает только по экономике и личным финансам.",
    ) {
        override fun check(query: String): InvariantViolation? {
            return check(query, taskContext = null)
        }

        override fun check(
            query: String,
            taskContext: TaskContext?,
        ): InvariantViolation? {
            val normalized = query.normalizedForInvariantChecks()

            if (FINANCE_MARKERS.any { it in normalized } || TASK_FOLLOW_UP_MARKERS.any { it in normalized }) {
                return null
            }

            val blockedMarker = NON_FINANCE_MARKERS.firstOrNull { it in normalized }

            if (blockedMarker == null && taskContext?.isAcceptingTaskInput() == true) {
                val taskInputMarker = TASK_INPUT_MARKERS.firstOrNull { it in normalized }

                if (taskInputMarker != null) {
                    return null
                }
            }

            val markerExplanation = if (blockedMarker == null) {
                "в запросе нет признаков домена экономики или личных финансов"
            } else {
                "обнаружена тема \"$blockedMarker\""
            }

            return InvariantViolation(
                invariant = this,
                explanation = "Я не могу ответить на этот вопрос, так как работаю только с темами по экономике и личным финансам. Нарушен инвариант домена: $markerExplanation.",
            )
        }

        private val FINANCE_MARKERS = setOf(
            "бюджет", "доход", "расход", "накоп", "сбереж", "подушк", "инвест",
            "деньг", "руб", "рубл", "р ", "финанс", "эконом", "кредит", "ипотек",
            "долг", "пенси", "налог", "зарплат", "отпуск", "цель", "портфел",
            "платеж", "трат", "кэшфлоу", "cashflow", "budget", "income", "expense",
            "saving", "investment", "finance", "money",
        )

        private val TASK_FOLLOW_UP_MARKERS = setOf(
            "что дальше", "следующий шаг", "продолж", "проверь", "заверши", "валидац",
            "план", "задач", "шаг",
        )

        private val TASK_INPUT_MARKERS = setOf(
            "цель", "цул", "цел",
            "огранич", "услов", "критер", "вводн", "данн",
            "срок", "сумм", "доход", "расход", "трат", "накоп",
            "подушка", "резерв", "приоритет",
        )

        private val NON_FINANCE_MARKERS = setOf(
            "погод", "температур", "прогноз",
            "рецепт", "готов", "суп", "салат",
            "код", "kotlin", "python", "javascript", "программ", "алгоритм",
            "фильм", "музык", "игр", "спорт", "трениров",
            "путешеств", "маршрут", "отель", "билет",
        )
    }
}

/** A concrete violation found by an [Invariant] check. */
data class InvariantViolation(
    val invariant: Invariant,
    val explanation: String,
)

/** Registry for always-on assistant invariants. */
class InvariantRegistry(
    private val invariants: List<Invariant>,
) {
    /** Returns the first violation, or null when the query is allowed. */
    fun check(query: String): InvariantViolation? {
        return invariants.firstNotNullOfOrNull { invariant -> invariant.check(query) }
    }

    /** Returns the first violation while considering the current task context. */
    fun check(
        query: String,
        taskContext: TaskContext?,
    ): InvariantViolation? {
        return invariants.firstNotNullOfOrNull { invariant -> invariant.check(query, taskContext) }
    }

    /** Returns all active invariants. */
    fun activeInvariants(): List<Invariant> {
        return invariants.toList()
    }

    companion object {
        fun default(): InvariantRegistry {
            return InvariantRegistry(
                invariants = listOf(
                    Invariant.CurrencyCountryInvariant,
                    Invariant.DomainInvariant,
                ),
            )
        }
    }
}

private fun TaskContext.isAcceptingTaskInput(): Boolean {
    return !paused && !isDone
}

private fun String.normalizedForInvariantChecks(): String {
    return lowercase()
        .replace('ё', 'е')
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun String.invariantTokens(): List<String> {
    return split(Regex("""[^а-яa-z0-9]+"""))
        .filter { token -> token.isNotBlank() }
}

private fun String.looksLikeDollarToken(): Boolean {
    return startsWith("дол") && !startsWith("долг")
}

/*
Демонстрационные проверки инвариантов:

Запрос: "Посчитай мой бюджет в долларах"
Ответ: "Запрос не обработан, потому что я поддерживаю расчеты только в рублях и для России..."

Запрос: "Какая сейчас погода в Москве?"
Ответ: "Я не могу ответить на этот вопрос, так как работаю только с темами по экономике и личным финансам..."

Запрос: "Как накопить на отпуск?"
Результат: запрос проходит проверку, потому что относится к личным финансам.

Если активна задача в WorkingMemory.current_task, конфликтный запрос не вызывает /task next
и не меняет TaskContext. Задача остается в рабочей памяти в прежнем stage и step.
*/

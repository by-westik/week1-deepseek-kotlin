interface ContextManager {
    val enabled: Boolean
    val memoryText: String

    fun onUserMessage(userText: String) = Unit

    fun buildPrompt(history: List<ChatMessage>, userMessage: ChatMessage): ContextPrompt

    fun compact(history: List<ChatMessage>, force: Boolean = false): List<ChatMessage> {
        return history
    }
}

class FullContextManager : ContextManager {
    override val enabled: Boolean = false
    override val memoryText: String = ""

    override fun buildPrompt(history: List<ChatMessage>, userMessage: ChatMessage): ContextPrompt {
        return ContextPrompt(
            messages = history + userMessage,
            summaryText = "",
            summarizedMessages = 0,
        )
    }
}

class SlidingWindowContextManager(
    private val recentMessages: Int,
) : ContextManager {
    override val enabled: Boolean = true
    override val memoryText: String = ""

    override fun buildPrompt(history: List<ChatMessage>, userMessage: ChatMessage): ContextPrompt {
        val recent = history.takeLast(recentMessages)

        return ContextPrompt(
            messages = recent + userMessage,
            summaryText = "",
            summarizedMessages = history.size - recent.size,
        )
    }

    override fun compact(history: List<ChatMessage>, force: Boolean): List<ChatMessage> {
        return history.takeLast(recentMessages)
    }
}

class FactsContextManager(
    private val recentMessages: Int,
    private val factsStore: FactsStore,
) : ContextManager {
    private var facts = factsStore.load().toMutableMap()

    override val enabled: Boolean = true

    override val memoryText: String
        get() = factsText()

    override fun onUserMessage(userText: String) {
        extractFacts(userText).forEach { (key, value) ->
            facts[key] = value
        }

        if (facts.isNotEmpty()) {
            factsStore.save(facts)
        }
    }

    override fun buildPrompt(history: List<ChatMessage>, userMessage: ChatMessage): ContextPrompt {
        val recent = history.takeLast(recentMessages)
        val factsMessage = factsText().takeIf { it.isNotBlank() }?.let { facts ->
            ChatMessage(
                role = "system",
                content = """
                    Ниже sticky facts/key-value memory агента. Считай эти факты устойчивой памятью диалога.
                    $facts
                """.trimIndent(),
            )
        }
        val messages = if (factsMessage == null) {
            recent + userMessage
        } else {
            listOf(factsMessage) + recent + userMessage
        }

        return ContextPrompt(
            messages = messages,
            summaryText = factsText(),
            summarizedMessages = history.size - recent.size,
        )
    }

    override fun compact(history: List<ChatMessage>, force: Boolean): List<ChatMessage> {
        return history.takeLast(recentMessages)
    }

    private fun extractFacts(userText: String): Map<String, String> {
        val normalized = userText.replace(Regex("\\s+"), " ").trim()
        val lower = normalized.lowercase()
        val facts = mutableMapOf<String, String>()

        Regex("""(?i)запомни(?:,| что)?\s+(.+)""")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { facts["note_${facts.size + 1}"] = it }

        listOf(
            "цель" to "goal",
            "ограничение" to "constraint",
            "ограничения" to "constraint",
            "предпочтение" to "preference",
            "решение" to "decision",
            "договоренность" to "agreement",
            "договорённость" to "agreement",
            "формат" to "format",
            "тон" to "tone",
        ).forEach { (marker, key) ->
            if (marker in lower) {
                val value = normalized.substringAfter(":", normalized).substringAfter("-", normalized).trim()
                if (value.isNotBlank()) {
                    facts[key] = value
                }
            }
        }

        return facts
    }

    private fun factsText(): String {
        if (facts.isEmpty()) {
            return ""
        }

        return facts.toSortedMap()
            .entries
            .joinToString("\n") { (key, value) -> "$key: $value" }
    }
}

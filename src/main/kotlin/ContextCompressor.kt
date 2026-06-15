class ContextCompressor(
    override val enabled: Boolean,
    private val recentMessages: Int,
    private val summaryChunkSize: Int,
    private val summaryStore: SummaryStore,
    private val summarizer: ConversationSummarizer,
) : ContextManager {
    private var storedSummary = summaryStore.load()

    val summaryText: String
        get() = storedSummary

    override val memoryText: String
        get() = storedSummary

    override fun buildPrompt(history: List<ChatMessage>, userMessage: ChatMessage): ContextPrompt {
        if (!enabled) {
            return ContextPrompt(
                messages = history + userMessage,
                summaryText = "",
                summarizedMessages = 0,
            )
        }

        val summary = storedSummary
        val promptMessages = if (summary.isBlank()) {
            history + userMessage
        } else {
            listOf(ChatMessage(role = "system", content = summaryPrompt(summary))) + history + userMessage
        }

        return ContextPrompt(
            messages = promptMessages,
            summaryText = summary,
            summarizedMessages = storedSummaryMessageCount(),
        )
    }

    override fun compact(history: List<ChatMessage>, force: Boolean): List<ChatMessage> {
        if (!enabled || history.size <= recentMessages) {
            return history
        }

        val oldCount = history.size - recentMessages
        val compactCount = oldCount

        if (compactCount <= 0) {
            return history
        }

        val compactedMessages = history.take(compactCount)
        val summarizedCount = storedSummaryMessageCount() + compactedMessages.size
        val modelSummary = summarizer.summarize(storedSummary, compactedMessages)
        storedSummary = "Сжато сообщений: $summarizedCount.\n$modelSummary"
        summaryStore.save(storedSummary)

        return history.drop(compactCount)
    }

    private fun summaryPrompt(summary: String): String {
        return """
            Ниже краткое содержание более ранней части диалога. Используй его как память о старом контексте.
            $summary
        """.trimIndent()
    }

    private fun storedSummaryMessageCount(): Int {
        return Regex("""Сжато сообщений:\s*(\d+)""")
            .findAll(storedSummary)
            .sumOf { result -> result.groupValues[1].toIntOrNull() ?: 0 }
    }
}

class DeepSeekAgent(
    private val client: DeepSeekClient,
    private val settings: ModelSettings,
    private val messageStore: MessageStore,
    private val tokenCounter: TokenCounter,
    private val contextCompressor: ContextCompressor,
) {
    private var messages = messageStore.load().toMutableList()

    val messageCount: Int
        get() = messages.size

    val summaryText: String
        get() = contextCompressor.summaryText

    fun compactHistoryNow(): Int {
        messages = contextCompressor.compact(messages, force = true).toMutableList()
        messageStore.save(messages)

        return messages.size
    }

    fun compactHistoryIfNeeded(): Int {
        messages = contextCompressor.compact(messages).toMutableList()
        messageStore.save(messages)

        return messages.size
    }

    fun previewTokens(userText: String): TokenReport {
        val userMessage = ChatMessage(role = "user", content = userText)
        val contextPrompt = contextCompressor.buildPrompt(messages, userMessage)
        val currentRequestTokens = tokenCounter.countMessage(userMessage)
        val fullHistoryTokens = tokenCounter.countMessages(messages)
        val promptTokens = tokenCounter.countMessages(contextPrompt.messages)
        val effectiveHistoryTokens = promptTokens - currentRequestTokens
        val contextLimit = MODEL_LIMITS.getValue(settings.model).contextTokens

        return TokenReport(
            currentRequestTokens = currentRequestTokens,
            fullHistoryTokens = fullHistoryTokens,
            effectiveHistoryTokens = effectiveHistoryTokens,
            savedHistoryMessages = messages.size,
            requestMessages = contextPrompt.messages.size,
            summaryTokens = tokenCounter.countText(contextPrompt.summaryText),
            summarizedMessages = contextPrompt.summarizedMessages,
            promptTokens = promptTokens,
            maxResponseTokens = settings.maxTokens,
            projectedTotalTokens = promptTokens + settings.maxTokens,
            contextLimit = contextLimit,
            compressionEnabled = contextCompressor.enabled,
        )
    }

    fun ask(userText: String): ModelResponse {
        compactHistoryIfNeeded()

        val userMessage = ChatMessage(role = "user", content = userText)
        val contextPrompt = contextCompressor.buildPrompt(messages, userMessage)
        val response = client.complete(contextPrompt.messages, settings)

        messages += userMessage
        messages += ChatMessage(role = "assistant", content = response.answer)
        compactHistoryIfNeeded()
        messageStore.save(messages)

        return response
    }
}

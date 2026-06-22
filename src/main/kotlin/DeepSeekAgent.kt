class DeepSeekAgent(
    private val client: DeepSeekClient,
    private val settings: ModelSettings,
    private val messageStore: MessageStore,
    private val tokenCounter: TokenCounter,
    private val contextManager: ContextManager,
    private val memory: AssistantMemory,
) {
    private var messages = messageStore.load().toMutableList()

    val messageCount: Int
        get() = messages.size

    val summaryText: String
        get() = contextManager.memoryText

    fun currentMessages(): List<ChatMessage> {
        return messages.toList()
    }

    fun reloadMessages() {
        messages = messageStore.load().toMutableList()
    }

    fun compactHistoryNow(): Int {
        messages = contextManager.compact(messages, force = true).toMutableList()
        messageStore.save(messages)

        return messages.size
    }

    fun compactHistoryIfNeeded(): Int {
        messages = contextManager.compact(messages).toMutableList()
        messageStore.save(messages)

        return messages.size
    }

    fun previewTokens(userText: String): TokenReport {
        val userMessage = ChatMessage(role = "user", content = userText)
        val contextPrompt = contextManager.buildPrompt(messages, userMessage)
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
            compressionEnabled = contextManager.enabled,
        )
    }

    fun ask(userText: String): ModelResponse {
        contextManager.onUserMessage(userText)
        compactHistoryIfNeeded()

        val userMessage = ChatMessage(role = "user", content = userText)
        memory.shortTerm.add(userMessage)
        val contextPrompt = contextManager.buildPrompt(messages, userMessage)
        val response = client.complete(contextPrompt.messages, settings)
        val assistantMessage = ChatMessage(role = "assistant", content = response.answer)

        messages += userMessage
        messages += assistantMessage
        memory.shortTerm.add(assistantMessage)
        compactHistoryIfNeeded()
        messageStore.save(messages)

        return response
    }
}

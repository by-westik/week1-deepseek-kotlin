interface TokenCounter {
    fun countText(text: String): Int
    fun countMessage(message: ChatMessage): Int
    fun countMessages(messages: List<ChatMessage>): Int
}

class SimpleTokenCounter : TokenCounter {
    private val tokenRegex = Regex("""[\p{L}_]+|\p{N}+|[^\s\p{L}\p{N}_]""")

    override fun countText(text: String): Int {
        return tokenRegex.findAll(text).count()
    }

    override fun countMessage(message: ChatMessage): Int {
        return MESSAGE_OVERHEAD_TOKENS + countText(message.role) + countText(message.content)
    }

    override fun countMessages(messages: List<ChatMessage>): Int {
        return messages.sumOf(::countMessage)
    }

    private companion object {
        const val MESSAGE_OVERHEAD_TOKENS = 4
    }
}

data class ModelPricing(
    val inputCacheHitPerMillion: Double,
    val inputCacheMissPerMillion: Double,
    val outputPerMillion: Double,
)

data class ModelLimit(
    val contextTokens: Int,
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val promptCacheHitTokens: Int?,
    val promptCacheMissTokens: Int?,
)

data class ModelResponse(
    val answer: String,
    val usage: TokenUsage,
    val elapsedMillis: Long,
    val estimatedCost: Double,
)

data class DialogStats(
    val responseCount: Int = 0,
    val elapsedMillis: Long = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val promptCacheHitTokens: Int = 0,
    val promptCacheMissTokens: Int = 0,
    val hasCacheStats: Boolean = false,
    val estimatedCost: Double = 0.0,
) {
    fun plus(response: ModelResponse): DialogStats {
        val usage = response.usage

        return copy(
            responseCount = responseCount + 1,
            elapsedMillis = elapsedMillis + response.elapsedMillis,
            promptTokens = promptTokens + usage.promptTokens,
            completionTokens = completionTokens + usage.completionTokens,
            totalTokens = totalTokens + usage.totalTokens,
            promptCacheHitTokens = promptCacheHitTokens + (usage.promptCacheHitTokens ?: 0),
            promptCacheMissTokens = promptCacheMissTokens + (usage.promptCacheMissTokens ?: 0),
            hasCacheStats = hasCacheStats || usage.promptCacheHitTokens != null || usage.promptCacheMissTokens != null,
            estimatedCost = estimatedCost + response.estimatedCost,
        )
    }
}

data class TokenReport(
    val currentRequestTokens: Int,
    val fullHistoryTokens: Int,
    val effectiveHistoryTokens: Int,
    val savedHistoryMessages: Int,
    val requestMessages: Int,
    val summaryTokens: Int,
    val summarizedMessages: Int,
    val promptTokens: Int,
    val maxResponseTokens: Int,
    val projectedTotalTokens: Int,
    val contextLimit: Int,
    val compressionEnabled: Boolean,
) {
    val isOverLimit: Boolean
        get() = projectedTotalTokens > contextLimit

    val usagePercent: Double
        get() = projectedTotalTokens.toDouble() / contextLimit * 100.0
}

data class ContextPrompt(
    val messages: List<ChatMessage>,
    val summaryText: String,
    val summarizedMessages: Int,
)

data class ChatMessage(
    val role: String,
    val content: String,
)

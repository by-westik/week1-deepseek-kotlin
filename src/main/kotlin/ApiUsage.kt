import org.json.JSONObject

fun parseUsage(responseJson: JSONObject): TokenUsage {
    val usageJson = responseJson.optJSONObject("usage")
        ?: return TokenUsage(
            promptTokens = 0,
            completionTokens = 0,
            totalTokens = 0,
            promptCacheHitTokens = null,
            promptCacheMissTokens = null,
        )

    return TokenUsage(
        promptTokens = usageJson.optInt("prompt_tokens", 0),
        completionTokens = usageJson.optInt("completion_tokens", 0),
        totalTokens = usageJson.optInt("total_tokens", 0),
        promptCacheHitTokens = usageJson.optionalInt("prompt_cache_hit_tokens"),
        promptCacheMissTokens = usageJson.optionalInt("prompt_cache_miss_tokens"),
    )
}

fun JSONObject.optionalInt(name: String): Int? {
    return if (has(name) && !isNull(name)) {
        optInt(name)
    } else {
        null
    }
}

fun calculateCost(model: String, usage: TokenUsage): Double {
    val pricing = MODEL_PRICING[model] ?: return 0.0

    val inputCost = if (usage.promptCacheHitTokens != null || usage.promptCacheMissTokens != null) {
        ((usage.promptCacheHitTokens ?: 0) / TOKENS_PER_MILLION * pricing.inputCacheHitPerMillion) +
            ((usage.promptCacheMissTokens ?: 0) / TOKENS_PER_MILLION * pricing.inputCacheMissPerMillion)
    } else {
        usage.promptTokens / TOKENS_PER_MILLION * pricing.inputCacheMissPerMillion
    }

    val outputCost = usage.completionTokens / TOKENS_PER_MILLION * pricing.outputPerMillion

    return inputCost + outputCost
}

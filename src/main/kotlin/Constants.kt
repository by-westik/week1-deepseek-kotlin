const val API_URL = "https://api.deepseek.com/chat/completions"
const val DEFAULT_MODEL = "deepseek-v4-flash"
const val DEFAULT_THINKING = "disabled"
const val DEFAULT_MAX_TOKENS = 500
const val DEFAULT_HISTORY_FILE = "chat-history.json"
const val DEFAULT_SUMMARY_FILE = "chat-summary.txt"
const val DEFAULT_FACTS_FILE = "chat-facts.json"
const val DEFAULT_BRANCH_DIR = "chat-branches"
const val DEFAULT_BRANCH_NAME = "main"
const val DEFAULT_RECENT_MESSAGES = 8
const val DEFAULT_SUMMARY_CHUNK_SIZE = 1
const val TOKENS_PER_MILLION = 1_000_000.0

val EXIT_COMMANDS = setOf("/exit", "/quit", "exit", "quit", "выход")

val SUPPORTED_MODELS = setOf("deepseek-v4-flash", "deepseek-v4-pro")

val MODEL_LIMITS = mapOf(
    "deepseek-v4-flash" to ModelLimit(contextTokens = 1_048_576),
    "deepseek-v4-pro" to ModelLimit(contextTokens = 1_048_576),
)

val MODEL_PRICING = mapOf(
    "deepseek-v4-flash" to ModelPricing(
        inputCacheHitPerMillion = 0.0028,
        inputCacheMissPerMillion = 0.14,
        outputPerMillion = 0.28,
    ),
    "deepseek-v4-pro" to ModelPricing(
        inputCacheHitPerMillion = 0.003625,
        inputCacheMissPerMillion = 0.435,
        outputPerMillion = 0.87,
    ),
)

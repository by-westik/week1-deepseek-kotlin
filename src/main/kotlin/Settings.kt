import java.nio.file.Path

data class AppSettings(
    val modelSettings: ModelSettings = ModelSettings(),
    val historyPath: Path = Path.of(DEFAULT_HISTORY_FILE),
    val summaryPath: Path = Path.of(DEFAULT_SUMMARY_FILE),
    val allowOverLimit: Boolean = false,
    val dryRunTokens: Boolean = false,
    val compressionEnabled: Boolean = false,
    val recentMessages: Int = DEFAULT_RECENT_MESSAGES,
    val summaryChunkSize: Int = DEFAULT_SUMMARY_CHUNK_SIZE,
    val compactNow: Boolean = false,
)

data class ModelSettings(
    val model: String = DEFAULT_MODEL,
    val thinking: String = DEFAULT_THINKING,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val stop: String? = null,
    val temperature: Double? = null,
)

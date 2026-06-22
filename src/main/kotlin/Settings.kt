import java.nio.file.Path

data class AppSettings(
    val modelSettings: ModelSettings = ModelSettings(),
    val historyPath: Path = Path.of(DEFAULT_HISTORY_FILE),
    val summaryPath: Path = Path.of(DEFAULT_SUMMARY_FILE),
    val factsPath: Path = Path.of(DEFAULT_FACTS_FILE),
    val branchDir: Path = Path.of(DEFAULT_BRANCH_DIR),
    val branchName: String = DEFAULT_BRANCH_NAME,
    val memoryDir: Path = Path.of(DEFAULT_MEMORY_DIR),
    val memoryUser: String = DEFAULT_MEMORY_USER,
    val memoryStatus: Boolean = false,
    val historyPathExplicit: Boolean = false,
    val summaryPathExplicit: Boolean = false,
    val factsPathExplicit: Boolean = false,
    val branchDirExplicit: Boolean = false,
    val contextStrategy: ContextStrategyType = ContextStrategyType.FULL,
    val allowOverLimit: Boolean = false,
    val dryRunTokens: Boolean = false,
    val compressionEnabled: Boolean = false,
    val recentMessages: Int = DEFAULT_RECENT_MESSAGES,
    val summaryChunkSize: Int = DEFAULT_SUMMARY_CHUNK_SIZE,
    val compactNow: Boolean = false,
)

enum class ContextStrategyType {
    FULL,
    SUMMARY,
    SLIDING,
    FACTS,
    BRANCHING,
}

data class ModelSettings(
    val model: String = DEFAULT_MODEL,
    val thinking: String = DEFAULT_THINKING,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val stop: String? = null,
    val temperature: Double? = null,
)

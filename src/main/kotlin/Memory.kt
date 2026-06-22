import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * In-memory transcript for the current process.
 *
 * The short-term layer intentionally has no storage backend: it is created on
 * startup and disappears when the CLI process exits.
 */
class ShortTermMemory {
    private val messages = mutableListOf<ChatMessage>()

    /** Adds one user or assistant message to the current session transcript. */
    fun add(message: ChatMessage) {
        messages += message
    }

    /** Returns the messages captured during this process only. */
    fun all(): List<ChatMessage> {
        return messages.toList()
    }

    /** Clears the current in-memory transcript. */
    fun clear() {
        messages.clear()
    }
}

/**
 * Scratchpad for the task currently being solved.
 *
 * This layer is explicit and mutable by design: commands can place intermediate
 * results, context snippets, or state flags here. It can be backed by a
 * temporary JSON file so unfinished tasks survive process restarts until the
 * user confirms that the task is solved.
 */
class WorkingMemory(
    private val userId: String? = null,
    private val store: WorkingMemoryStore? = null,
) {
    private var snapshot = if (userId != null && store != null) {
        store.load(userId).also { store.save(userId, it) }
    } else {
        WorkingMemorySnapshot(userId = userId ?: DEFAULT_MEMORY_USER)
    }

    /** Stores a context value for the current task. */
    fun putContext(key: String, value: String) {
        snapshot = snapshot.copy(
            context = snapshot.context + (key.trim() to value.trim()),
            updatedAt = Instant.now().toString(),
        )
        persist()
    }

    /** Stores an intermediate result for the current task. */
    fun putResult(key: String, value: String) {
        snapshot = snapshot.copy(
            intermediateResults = snapshot.intermediateResults + (key.trim() to value.trim()),
            updatedAt = Instant.now().toString(),
        )
        persist()
    }

    /** Stores a boolean state flag for the current task. */
    fun setFlag(key: String, value: Boolean) {
        snapshot = snapshot.copy(
            flags = snapshot.flags + (key.trim() to value),
            updatedAt = Instant.now().toString(),
        )
        persist()
    }

    /** Returns all current task context values. */
    fun context(): Map<String, String> {
        return snapshot.context
    }

    /** Returns all current intermediate results. */
    fun results(): Map<String, String> {
        return snapshot.intermediateResults
    }

    /** Returns all current state flags. */
    fun flags(): Map<String, Boolean> {
        return snapshot.flags
    }

    /** Returns true when the current task has no stored context, results, or flags. */
    fun isEmpty(): Boolean {
        return snapshot.context.isEmpty() && snapshot.intermediateResults.isEmpty() && snapshot.flags.isEmpty()
    }

    /** Clears the current task scratchpad after the task is explicitly closed. */
    fun clear() {
        snapshot = WorkingMemorySnapshot(
            userId = snapshot.userId,
            createdAt = snapshot.createdAt,
            updatedAt = Instant.now().toString(),
        )
        persist()
    }

    private fun persist() {
        if (userId != null && store != null) {
            store.save(userId, snapshot)
        }
    }
}

/** Durable temporary contents of one user's unfinished current task. */
data class WorkingMemorySnapshot(
    val userId: String,
    val context: Map<String, String> = emptyMap(),
    val intermediateResults: Map<String, String> = emptyMap(),
    val flags: Map<String, Boolean> = emptyMap(),
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = createdAt,
)

/** JSON-backed store for per-user working memory files. */
class WorkingMemoryStore(private val memoryDir: Path) {
    /** Loads unfinished task memory for a user, or returns an empty task snapshot. */
    fun load(userId: String): WorkingMemorySnapshot {
        val safeUserId = sanitizeUserId(userId)
        val path = userPath(safeUserId)

        if (!Files.exists(path)) {
            return WorkingMemorySnapshot(userId = safeUserId)
        }

        val json = JSONObject(Files.readString(path))

        return WorkingMemorySnapshot(
            userId = json.optString("userId", safeUserId),
            context = json.optJSONObject("context").toStringMap(),
            intermediateResults = json.optJSONObject("intermediateResults").toStringMap(),
            flags = json.optJSONObject("flags").toBooleanMap(),
            createdAt = json.optString("createdAt", Instant.now().toString()),
            updatedAt = json.optString("updatedAt", Instant.now().toString()),
        )
    }

    /** Persists unfinished task memory for one user as pretty-printed JSON. */
    fun save(userId: String, snapshot: WorkingMemorySnapshot) {
        val safeUserId = sanitizeUserId(userId)
        Files.createDirectories(memoryDir)
        Files.writeString(userPath(safeUserId), snapshot.toJson().toString(2))
    }

    /** Resolves the JSON path for a sanitized user id. */
    fun userPath(userId: String): Path {
        return memoryDir.resolve("working-${sanitizeUserId(userId)}.json")
    }

    /** Converts arbitrary CLI input into a stable, filesystem-safe user id. */
    fun sanitizeUserId(userId: String): String {
        val sanitized = userId.trim().replace(Regex("""[^A-Za-z0-9_.-]"""), "_")

        if (sanitized.isBlank()) {
            throw IllegalArgumentException("user id не должен быть пустым")
        }

        return sanitized
    }
}

/**
 * Persistent memory for one user profile.
 *
 * The long-term layer stores profile fields, durable decisions, and knowledge
 * extracted by explicit commands. Each user gets a separate JSON file.
 */
class LongTermMemory(
    private val userId: String,
    private val store: LongTermMemoryStore,
) {
    private var snapshot = store.load(userId).also { store.save(userId, it) }

    /** User identifier whose long-term memory file is currently active. */
    val activeUser: String
        get() = userId

    /** Last loaded or saved durable memory snapshot. */
    fun snapshot(): LongTermMemorySnapshot {
        return snapshot
    }

    /** Stores a durable profile field and persists it immediately. */
    fun rememberProfile(key: String, value: String) {
        snapshot = snapshot.copy(
            profile = snapshot.profile + (key.trim() to value.trim()),
            updatedAt = Instant.now().toString(),
        )
        store.save(userId, snapshot)
    }

    /** Stores a durable decision and persists it immediately. */
    fun rememberDecision(key: String, value: String) {
        snapshot = snapshot.copy(
            decisions = snapshot.decisions + (key.trim() to value.trim()),
            updatedAt = Instant.now().toString(),
        )
        store.save(userId, snapshot)
    }

    /** Stores durable knowledge and persists it immediately. */
    fun rememberKnowledge(key: String, value: String) {
        snapshot = snapshot.copy(
            knowledge = snapshot.knowledge + (key.trim() to value.trim()),
            updatedAt = Instant.now().toString(),
        )
        store.save(userId, snapshot)
    }
}

/** Durable contents of one user's long-term memory file. */
data class LongTermMemorySnapshot(
    val userId: String,
    val profile: Map<String, String> = emptyMap(),
    val decisions: Map<String, String> = emptyMap(),
    val knowledge: Map<String, String> = emptyMap(),
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = createdAt,
)

/** JSON-backed store for per-user long-term memory files. */
class LongTermMemoryStore(private val memoryDir: Path) {
    /** Loads memory for a user, creating an empty snapshot if no file exists yet. */
    fun load(userId: String): LongTermMemorySnapshot {
        val safeUserId = sanitizeUserId(userId)
        val path = userPath(safeUserId)

        if (!Files.exists(path)) {
            return LongTermMemorySnapshot(userId = safeUserId)
        }

        val json = JSONObject(Files.readString(path))

        return LongTermMemorySnapshot(
            userId = json.optString("userId", safeUserId),
            profile = json.optJSONObject("profile").toStringMap(),
            decisions = json.optJSONObject("decisions").toStringMap(),
            knowledge = json.optJSONObject("knowledge").toStringMap(),
            createdAt = json.optString("createdAt", Instant.now().toString()),
            updatedAt = json.optString("updatedAt", Instant.now().toString()),
        )
    }

    /** Persists memory for one user as pretty-printed JSON. */
    fun save(userId: String, snapshot: LongTermMemorySnapshot) {
        val safeUserId = sanitizeUserId(userId)
        Files.createDirectories(memoryDir)
        Files.writeString(userPath(safeUserId), snapshot.toJson().toString(2))
    }

    /** Returns all known user ids based on files already present in memoryDir. */
    fun listUsers(): List<String> {
        if (!Files.exists(memoryDir)) {
            return emptyList()
        }

        return Files.list(memoryDir).use { paths ->
            paths
                .filter { path -> path.fileName.toString().endsWith(".json") }
                .map { path -> path.fileName.toString().removeSuffix(".json") }
                .sorted()
                .toList()
        }
    }

    /** Resolves the JSON path for a sanitized user id. */
    fun userPath(userId: String): Path {
        return memoryDir.resolve("${sanitizeUserId(userId)}.json")
    }

    /** Converts arbitrary CLI input into a stable, filesystem-safe user id. */
    fun sanitizeUserId(userId: String): String {
        val sanitized = userId.trim().replace(Regex("""[^A-Za-z0-9_.-]"""), "_")

        if (sanitized.isBlank()) {
            throw IllegalArgumentException("user id не должен быть пустым")
        }

        return sanitized
    }
}

/** Groups the three memory layers so call sites can choose a layer explicitly. */
data class AssistantMemory(
    val shortTerm: ShortTermMemory,
    val working: WorkingMemory,
    val longTerm: LongTermMemory,
)

/** Builds a human-readable dump of all memory layers for the CLI. */
class MemoryStatusFormatter {
    fun format(memory: AssistantMemory): String {
        val longTerm = memory.longTerm.snapshot()

        return buildString {
            appendLine("=== Memory Status ===")
            appendLine()
            appendLine("ShortTermMemory (текущая сессия, in-memory)")
            appendMessages(memory.shortTerm.all())
            appendLine()
            appendLine("WorkingMemory (текущая задача, сохраняется до закрытия задачи)")
            appendMap("context", memory.working.context())
            appendMap("intermediateResults", memory.working.results())
            appendMap("flags", memory.working.flags().mapValues { it.value.toString() })
            appendLine()
            appendLine("LongTermMemory (пользователь: ${longTerm.userId}, JSON)")
            appendLine("createdAt: ${longTerm.createdAt}")
            appendLine("updatedAt: ${longTerm.updatedAt}")
            appendMap("profile", longTerm.profile)
            appendMap("decisions", longTerm.decisions)
            appendMap("knowledge", longTerm.knowledge)
        }.trimEnd()
    }

    private fun StringBuilder.appendMessages(messages: List<ChatMessage>) {
        if (messages.isEmpty()) {
            appendLine("  (пусто)")
            return
        }

        messages.forEachIndexed { index, message ->
            appendLine("  ${index + 1}. ${message.role}: ${message.content}")
        }
    }

    private fun StringBuilder.appendMap(label: String, values: Map<String, String>) {
        appendLine("  $label:")

        if (values.isEmpty()) {
            appendLine("    (пусто)")
            return
        }

        values.toSortedMap().forEach { (key, value) ->
            appendLine("    $key = $value")
        }
    }
}

private fun LongTermMemorySnapshot.toJson(): JSONObject {
    return JSONObject()
        .put("userId", userId)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("profile", profile.toJsonObject())
        .put("decisions", decisions.toJsonObject())
        .put("knowledge", knowledge.toJsonObject())
}

private fun WorkingMemorySnapshot.toJson(): JSONObject {
    return JSONObject()
        .put("userId", userId)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("context", context.toJsonObject())
        .put("intermediateResults", intermediateResults.toJsonObject())
        .put("flags", flags.toBooleanJsonObject())
}

private fun Map<String, String>.toJsonObject(): JSONObject {
    val json = JSONObject()

    toSortedMap().forEach { (key, value) ->
        json.put(key, value)
    }

    return json
}

private fun Map<String, Boolean>.toBooleanJsonObject(): JSONObject {
    val json = JSONObject()

    toSortedMap().forEach { (key, value) ->
        json.put(key, value)
    }

    return json
}

private fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) {
        return emptyMap()
    }

    return keys().asSequence()
        .associateWith { key -> optString(key) }
        .filterKeys { key -> key.isNotBlank() }
        .filterValues { value -> value.isNotBlank() }
}

private fun JSONObject?.toBooleanMap(): Map<String, Boolean> {
    if (this == null) {
        return emptyMap()
    }

    return keys().asSequence()
        .filter { key -> key.isNotBlank() }
        .associateWith { key -> optBoolean(key) }
}

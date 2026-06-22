import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

const val USER_PROFILE_MEMORY_KEY = "user_profile"
const val USER_PROFILE_MEMORY_SOURCE = "LongTermMemory.profile[user_profile]"

/** Preferred communication tone for assistant answers. */
enum class Tone {
    Formal,
    Friendly,
    Neutral,
    ;

    companion object {
        fun fromInput(value: String): Tone {
            return when (value.trim().lowercase()) {
                "1", "formal", "formally", "формальный", "формально" -> Formal
                "2", "friendly", "friend", "дружелюбный", "дружелюбно" -> Friendly
                "3", "neutral", "нейтральный", "нейтрально" -> Neutral
                else -> throw IllegalArgumentException("tone должен быть Formal, Friendly или Neutral")
            }
        }
    }
}

/** Grammatical gender the assistant should use for self-references. */
enum class AssistantGender {
    Male,
    Female,
    None,
    ;

    companion object {
        fun fromInput(value: String): AssistantGender {
            return when (value.trim().lowercase()) {
                "1", "male", "мужской", "муж", "он" -> Male
                "2", "female", "женский", "жен", "она" -> Female
                "3", "none", "neutral", "без", "нет", "нейтральный" -> None
                else -> throw IllegalArgumentException("gender должен быть Male, Female или None")
            }
        }
    }
}

/** Preferred answer length. */
enum class AnswerLength {
    Short,
    Normal,
    Detailed,
    ;

    companion object {
        fun fromInput(value: String): AnswerLength {
            return when (value.trim().lowercase()) {
                "1", "short", "brief", "кратко", "коротко", "краткий" -> Short
                "2", "normal", "обычно", "нормально", "средне" -> Normal
                "3", "detailed", "detail", "подробно", "детально", "развернуто", "развёрнуто" -> Detailed
                else -> throw IllegalArgumentException("length должен быть Short, Normal или Detailed")
            }
        }
    }
}

/**
 * Persistent personalization settings for one user.
 *
 * The profile is stored in LongTermMemory under [USER_PROFILE_MEMORY_KEY] and
 * loaded into WorkingMemory before ordinary assistant requests.
 */
data class UserProfile(
    val tone: Tone = Tone.Neutral,
    val gender: AssistantGender = AssistantGender.None,
    val length: AnswerLength = AnswerLength.Normal,
    val language: String = "ru",
    val notes: String = "",
) {
    /** Returns a copy with one field changed by a CLI value. */
    fun withField(field: String, value: String): UserProfile {
        return when (field.trim().lowercase()) {
            "tone" -> copy(tone = Tone.fromInput(value))
            "gender" -> copy(gender = AssistantGender.fromInput(value))
            "length" -> copy(length = AnswerLength.fromInput(value))
            "language", "lang" -> copy(language = normalizeLanguage(value))
            "notes", "note" -> copy(notes = value.trim())
            else -> throw IllegalArgumentException("поле профиля должно быть tone, gender, length, language или notes")
        }
    }

    /** Builds the system instruction used for personalized model answers. */
    fun toSystemInstruction(): String {
        val toneInstruction = when (tone) {
            Tone.Formal -> "Пиши формально, спокойно и без эмодзи."
            Tone.Friendly -> "Пиши дружелюбно и тепло; можно использовать уместные эмодзи."
            Tone.Neutral -> "Пиши нейтрально и по делу."
        }
        val genderInstruction = when (gender) {
            AssistantGender.Male -> "Если говоришь о себе, используй мужской род."
            AssistantGender.Female -> "Если говоришь о себе, используй женский род."
            AssistantGender.None -> "Избегай гендерно окрашенных формулировок о себе."
        }
        val lengthInstruction = when (length) {
            AnswerLength.Short -> "Отвечай кратко: 1-3 коротких абзаца или пункта."
            AnswerLength.Normal -> "Отвечай обычной длиной: достаточно деталей без лишнего разрастания."
            AnswerLength.Detailed -> "Отвечай подробно: раскрывай причины, шаги и важные нюансы."
        }
        val notesInstruction = notes.takeIf { it.isNotBlank() }
            ?.let { "Дополнительные пожелания пользователя: $it" }
            ?: "Дополнительных пожеланий нет."

        return """
            Персонализация ответа для текущего пользователя:
            - Язык ответа: $language.
            - Тон: $tone. $toneInstruction
            - Род ассистента: $gender. $genderInstruction
            - Длина ответа: $length. $lengthInstruction
            - $notesInstruction
            Следуй этим настройкам во всех ответах, если запрос пользователя явно не просит другое.
        """.trimIndent()
    }

    /** Formats the profile for CLI output. */
    fun toDisplayText(): String {
        return """
            tone = $tone
            gender = $gender
            length = $length
            language = $language
            notes = ${notes.ifBlank { "(пусто)" }}
        """.trimIndent()
    }

    /** Serializes the profile for storage in LongTermMemory. */
    fun toJsonString(): String {
        return toJsonObject().toString()
    }

    companion object {
        fun fromJsonString(value: String): UserProfile? {
            return runCatching { fromJsonObject(JSONObject(value)) }.getOrNull()
        }

        fun fromJsonObject(json: JSONObject): UserProfile {
            return UserProfile(
                tone = Tone.fromInput(json.optString("tone", Tone.Neutral.name)),
                gender = AssistantGender.fromInput(json.optString("gender", AssistantGender.None.name)),
                length = AnswerLength.fromInput(json.optString("length", AnswerLength.Normal.name)),
                language = normalizeLanguage(json.optString("language", "ru")),
                notes = json.optString("notes", ""),
            )
        }

        private fun normalizeLanguage(value: String): String {
            return value.trim().lowercase().ifBlank { "ru" }
        }
    }
}

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

    /** Stores the profile currently loaded from long-term memory for request handling. */
    fun putUserProfile(profile: UserProfile, source: String) {
        snapshot = snapshot.copy(
            loadedUserProfile = profile,
            userProfileSource = source,
            updatedAt = Instant.now().toString(),
        )
        persist()
    }

    /** Removes only the loaded profile view from working memory. */
    fun clearUserProfile() {
        snapshot = snapshot.copy(
            loadedUserProfile = null,
            userProfileSource = null,
            updatedAt = Instant.now().toString(),
        )
        persist()
    }

    /** Returns the profile loaded into working memory for the current request. */
    fun userProfile(): UserProfile? {
        return snapshot.loadedUserProfile
    }

    /** Returns where the loaded profile came from. */
    fun userProfileSource(): String? {
        return snapshot.userProfileSource
    }

    /** Stores the current formal task state machine context. */
    fun putCurrentTask(task: TaskContext) {
        snapshot = snapshot.copy(
            currentTask = task,
            updatedAt = Instant.now().toString(),
        )
        persist()
    }

    /** Returns the current formal task state machine context, if any. */
    fun currentTask(): TaskContext? {
        return snapshot.currentTask
    }

    /** Removes only the formal task state machine context. */
    fun clearCurrentTask() {
        snapshot = snapshot.copy(
            currentTask = null,
            updatedAt = Instant.now().toString(),
        )
        persist()
    }

    /** Returns true when the current task has no stored context, results, or flags. */
    fun isEmpty(): Boolean {
        return snapshot.context.isEmpty() &&
            snapshot.intermediateResults.isEmpty() &&
            snapshot.flags.isEmpty() &&
            snapshot.currentTask == null
    }

    /** Clears the current task scratchpad after the task is explicitly closed. */
    fun clear() {
        snapshot = WorkingMemorySnapshot(
            userId = snapshot.userId,
            loadedUserProfile = snapshot.loadedUserProfile,
            userProfileSource = snapshot.userProfileSource,
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
    val loadedUserProfile: UserProfile? = null,
    val userProfileSource: String? = null,
    val currentTask: TaskContext? = null,
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = createdAt,
)

/** JSON-backed store for per-user working memory files. */
class WorkingMemoryStore(private val memoryDir: Path) {
    /** Loads unfinished task memory for a user, or returns an empty task snapshot. */
    fun load(userId: String): WorkingMemorySnapshot {
        val safeUserId = sanitizeUserId(userId)
        val path = when {
            Files.exists(userPath(safeUserId)) -> userPath(safeUserId)
            Files.exists(legacyUserPath(safeUserId)) -> legacyUserPath(safeUserId)
            else -> null
        }

        if (path == null) {
            return WorkingMemorySnapshot(userId = safeUserId)
        }

        val json = JSONObject(Files.readString(path))

        return WorkingMemorySnapshot(
            userId = json.optString("userId", safeUserId),
            context = json.optJSONObject("context").toStringMap(),
            intermediateResults = json.optJSONObject("intermediateResults").toStringMap(),
            flags = json.optJSONObject("flags").toBooleanMap(),
            loadedUserProfile = json.optJSONObject("loadedUserProfile")?.let { UserProfile.fromJsonObject(it) },
            userProfileSource = json.optString("userProfileSource").ifBlank { null },
            currentTask = (json.optJSONObject("current_task") ?: json.optJSONObject("currentTask"))
                ?.let { TaskContext.fromJsonObject(it) },
            createdAt = json.optString("createdAt", Instant.now().toString()),
            updatedAt = json.optString("updatedAt", Instant.now().toString()),
        )
    }

    /** Persists unfinished task memory for one user as pretty-printed JSON. */
    fun save(userId: String, snapshot: WorkingMemorySnapshot) {
        val safeUserId = sanitizeUserId(userId)
        val path = userPath(safeUserId)
        Files.createDirectories(path.parent)
        Files.writeString(path, snapshot.toJson().toString(2))
    }

    /** Resolves the JSON path for a sanitized user id. */
    fun userPath(userId: String): Path {
        return userDir(userId).resolve(DEFAULT_WORKING_MEMORY_FILE)
    }

    /** Resolves the per-user workspace directory. */
    fun userDir(userId: String): Path {
        return memoryDir.resolve(sanitizeUserId(userId))
    }

    /** Converts arbitrary CLI input into a stable, filesystem-safe user id. */
    fun sanitizeUserId(userId: String): String {
        val sanitized = userId.trim().replace(Regex("""[^A-Za-z0-9_.-]"""), "_")

        if (sanitized.isBlank()) {
            throw IllegalArgumentException("user id не должен быть пустым")
        }

        return sanitized
    }

    private fun legacyUserPath(userId: String): Path {
        return memoryDir.resolve("working-${sanitizeUserId(userId)}.json")
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

    /** Loads the structured personalization profile from long-term memory. */
    fun loadUserProfile(): UserProfile? {
        return snapshot.profile[USER_PROFILE_MEMORY_KEY]?.let(UserProfile::fromJsonString)
    }

    /** Saves the structured personalization profile in long-term memory. */
    fun saveUserProfile(profile: UserProfile) {
        rememberProfile(USER_PROFILE_MEMORY_KEY, profile.toJsonString())
    }

    /** Deletes only the structured personalization profile from long-term memory. */
    fun deleteUserProfile() {
        snapshot = snapshot.copy(
            profile = snapshot.profile - USER_PROFILE_MEMORY_KEY,
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
        val path = when {
            Files.exists(userPath(safeUserId)) -> userPath(safeUserId)
            Files.exists(legacyUserPath(safeUserId)) -> legacyUserPath(safeUserId)
            else -> null
        }

        if (path == null) {
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
        val path = userPath(safeUserId)
        Files.createDirectories(path.parent)
        Files.writeString(path, snapshot.toJson().toString(2))
    }

    /** Returns all known user ids based on files already present in memoryDir. */
    fun listUsers(): List<String> {
        if (!Files.exists(memoryDir)) {
            return emptyList()
        }

        val userDirs = Files.list(memoryDir).use { paths ->
            paths
                .filter { path -> Files.isDirectory(path) }
                .map { path -> path.fileName.toString() }
                .toList()
        }
        val legacyFiles = Files.list(memoryDir).use { paths ->
            paths
                .filter { path -> path.fileName.toString().endsWith(".json") }
                .filter { path -> !path.fileName.toString().startsWith("working-") }
                .map { path -> path.fileName.toString().removeSuffix(".json") }
                .toList()
        }

        return (userDirs + legacyFiles).distinct().sorted()
    }

    /** Resolves the JSON path for a sanitized user id. */
    fun userPath(userId: String): Path {
        return userDir(userId).resolve(DEFAULT_LONG_TERM_MEMORY_FILE)
    }

    /** Resolves the per-user workspace directory. */
    fun userDir(userId: String): Path {
        return memoryDir.resolve(sanitizeUserId(userId))
    }

    /** Converts arbitrary CLI input into a stable, filesystem-safe user id. */
    fun sanitizeUserId(userId: String): String {
        val sanitized = userId.trim().replace(Regex("""[^A-Za-z0-9_.-]"""), "_")

        if (sanitized.isBlank()) {
            throw IllegalArgumentException("user id не должен быть пустым")
        }

        return sanitized
    }

    private fun legacyUserPath(userId: String): Path {
        return memoryDir.resolve("${sanitizeUserId(userId)}.json")
    }
}

/** Groups the three memory layers so call sites can choose a layer explicitly. */
data class AssistantMemory(
    val shortTerm: ShortTermMemory,
    val working: WorkingMemory,
    val longTerm: LongTermMemory,
) {
    /** Loads the persistent user profile into the working layer for request handling. */
    fun loadUserProfileIntoWorking(): UserProfile? {
        val profile = longTerm.loadUserProfile()

        if (profile == null) {
            working.clearUserProfile()
        } else {
            working.putUserProfile(profile, USER_PROFILE_MEMORY_SOURCE)
        }

        return profile
    }
}

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
            appendUserProfile(
                label = "loadedUserProfile",
                profile = memory.working.userProfile(),
                source = memory.working.userProfileSource(),
            )
            appendCurrentTask(memory.working.currentTask())
            appendLine()
            appendLine("LongTermMemory (пользователь: ${longTerm.userId}, JSON)")
            appendLine("createdAt: ${longTerm.createdAt}")
            appendLine("updatedAt: ${longTerm.updatedAt}")
            appendUserProfile(
                label = USER_PROFILE_MEMORY_KEY,
                profile = memory.longTerm.loadUserProfile(),
                source = USER_PROFILE_MEMORY_SOURCE,
            )
            appendMap("profile", longTerm.profile - USER_PROFILE_MEMORY_KEY)
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

    private fun StringBuilder.appendUserProfile(
        label: String,
        profile: UserProfile?,
        source: String?,
    ) {
        appendLine("  $label:")

        if (profile == null) {
            appendLine("    (не загружен)")
            return
        }

        appendLine("    source = ${source ?: "(неизвестно)"}")
        profile.toDisplayText().lines().forEach { line ->
            appendLine("    $line")
        }
    }

    private fun StringBuilder.appendCurrentTask(task: TaskContext?) {
        appendLine("  current_task:")

        if (task == null) {
            appendLine("    (пусто)")
            return
        }

        task.toDisplayText().lines().forEach { line ->
            appendLine("    $line")
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
    val json = JSONObject()
        .put("userId", userId)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("context", context.toJsonObject())
        .put("intermediateResults", intermediateResults.toJsonObject())
        .put("flags", flags.toBooleanJsonObject())

    loadedUserProfile?.let { profile ->
        json.put("loadedUserProfile", profile.toJsonObject())
    }
    userProfileSource?.let { source ->
        json.put("userProfileSource", source)
    }
    currentTask?.let { task ->
        json.put("current_task", task.toJsonObject())
    }

    return json
}

private fun UserProfile.toJsonObject(): JSONObject {
    return JSONObject()
        .put("tone", tone.name)
        .put("gender", gender.name)
        .put("length", length.name)
        .put("language", language)
        .put("notes", notes)
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

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

interface MessageStore {
    fun load(): List<ChatMessage>
    fun save(messages: List<ChatMessage>)
}

class JsonMessageStore(private val path: Path) : MessageStore {
    override fun load(): List<ChatMessage> {
        if (!Files.exists(path)) {
            return emptyList()
        }

        val historyJson = JSONArray(Files.readString(path))
        val messages = mutableListOf<ChatMessage>()

        for (index in 0 until historyJson.length()) {
            val messageJson = historyJson.getJSONObject(index)
            val role = messageJson.optString("role").trim()
            val content = messageJson.optString("content").trim()

            if (role.isNotBlank() && content.isNotBlank()) {
                messages += ChatMessage(role = role, content = content)
            }
        }

        return messages
    }

    override fun save(messages: List<ChatMessage>) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, messages.toJson().toString(2))
    }
}

interface SummaryStore {
    fun load(): String
    fun save(summary: String)
}

class TextSummaryStore(private val path: Path) : SummaryStore {
    override fun load(): String {
        if (!Files.exists(path)) {
            return ""
        }

        return Files.readString(path).trim()
    }

    override fun save(summary: String) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, summary.trim())
    }
}

interface FactsStore {
    fun load(): Map<String, String>
    fun save(facts: Map<String, String>)
}

class JsonFactsStore(private val path: Path) : FactsStore {
    override fun load(): Map<String, String> {
        if (!Files.exists(path)) {
            return emptyMap()
        }

        val factsJson = JSONObject(Files.readString(path))

        return factsJson.keys().asSequence()
            .associateWith { key -> factsJson.optString(key) }
            .filterValues { value -> value.isNotBlank() }
    }

    override fun save(facts: Map<String, String>) {
        path.parent?.let { Files.createDirectories(it) }
        val factsJson = JSONObject()

        facts.toSortedMap().forEach { (key, value) ->
            factsJson.put(key, value)
        }

        Files.writeString(path, factsJson.toString(2))
    }
}

class BranchingMessageStore(
    private val branchDir: Path,
    initialBranch: String,
    private val fallbackHistoryPath: Path,
) : MessageStore {
    var activeBranch: String = sanitizeName(initialBranch)
        private set

    override fun load(): List<ChatMessage> {
        val branchPath = branchPath(activeBranch)

        if (Files.exists(branchPath)) {
            return JsonMessageStore(branchPath).load()
        }

        if (activeBranch == DEFAULT_BRANCH_NAME && Files.exists(fallbackHistoryPath)) {
            val messages = JsonMessageStore(fallbackHistoryPath).load()
            save(messages)
            return messages
        }

        return emptyList()
    }

    override fun save(messages: List<ChatMessage>) {
        JsonMessageStore(branchPath(activeBranch)).save(messages)
    }

    fun checkpoint(name: String, messages: List<ChatMessage>) {
        JsonMessageStore(checkpointPath(name)).save(messages)
    }

    fun createBranch(branchName: String, checkpointName: String) {
        val checkpointPath = checkpointPath(checkpointName)

        if (!Files.exists(checkpointPath)) {
            throw IllegalArgumentException("checkpoint не найден: $checkpointName")
        }

        val branch = sanitizeName(branchName)
        Files.createDirectories(branchDir)
        Files.copy(checkpointPath, branchPath(branch), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        activeBranch = branch
    }

    fun switchBranch(branchName: String) {
        val branch = sanitizeName(branchName)

        if (!Files.exists(branchPath(branch))) {
            throw IllegalArgumentException("ветка не найдена: $branch")
        }

        activeBranch = branch
    }

    fun listBranches(): List<String> {
        if (!Files.exists(branchDir)) {
            return emptyList()
        }

        return Files.list(branchDir).use { paths ->
            paths
                .filter { path -> path.fileName.toString().startsWith("branch-") }
                .map { path -> path.fileName.toString().removePrefix("branch-").removeSuffix(".json") }
                .sorted()
                .toList()
        }
    }

    private fun branchPath(branchName: String): Path {
        return branchDir.resolve("branch-${sanitizeName(branchName)}.json")
    }

    private fun checkpointPath(name: String): Path {
        return branchDir.resolve("checkpoint-${sanitizeName(name)}.json")
    }

    private fun sanitizeName(name: String): String {
        val sanitized = name.trim().replace(Regex("""[^A-Za-z0-9_.-]"""), "_")

        if (sanitized.isBlank()) {
            throw IllegalArgumentException("имя ветки/checkpoint не должно быть пустым")
        }

        return sanitized
    }
}

import org.json.JSONArray
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

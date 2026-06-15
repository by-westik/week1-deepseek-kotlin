import org.json.JSONArray
import org.json.JSONObject

fun List<ChatMessage>.toJson(): JSONArray {
    val messagesJson = JSONArray()

    forEach { message ->
        messagesJson.put(
            JSONObject()
                .put("role", message.role)
                .put("content", message.content),
        )
    }

    return messagesJson
}

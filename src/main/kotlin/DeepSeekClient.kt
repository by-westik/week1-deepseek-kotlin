import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class DeepSeekClient(private val apiKey: String) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun complete(messages: List<ChatMessage>, settings: ModelSettings): ModelResponse {
        val requestJson = JSONObject()
            .put("model", settings.model)
            .put("messages", messages.toJson())
            .put("thinking", JSONObject().put("type", settings.thinking))
            .put("max_tokens", settings.maxTokens)
            .put("stream", false)

        if (settings.stop != null) {
            requestJson.put("stop", JSONArray().put(settings.stop))
        }

        if (settings.temperature != null) {
            requestJson.put("temperature", settings.temperature)
        }

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody(jsonType))
            .build()

        val startedAt = System.nanoTime()

        httpClient.newCall(request).execute().use { response ->
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            val responseText = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: $responseText")
            }

            val responseJson = JSONObject(responseText)
            val answer = responseJson
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()

            if (answer.isBlank()) {
                throw IOException("модель вернула пустой ответ")
            }

            val usage = parseUsage(responseJson)
            val estimatedCost = calculateCost(settings.model, usage)

            return ModelResponse(
                answer = answer,
                usage = usage,
                elapsedMillis = elapsedMillis,
                estimatedCost = estimatedCost,
            )
        }
    }
}

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private const val API_URL = "https://api.deepseek.com/chat/completions"
private const val MODEL = "deepseek-v4-flash"
private const val PROMPT_ENV = "DEEPSEEK_PROMPT"
private const val PROMPT_FILE = "prompt.txt"
private const val DEFAULT_MAX_TOKENS = 500

data class ModelSettings(
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val stop: String? = null,
)

fun main(args: Array<String>) {
    val apiKey = System.getenv("DEEPSEEK_API_KEY")

    if (apiKey.isNullOrBlank()) {
        System.err.println("Ошибка: переменная окружения DEEPSEEK_API_KEY не задана")
        exitProcess(1)
    }

    val settings = try {
        parseArgs(args)
    } catch (error: IllegalArgumentException) {
        System.err.println("Ошибка: ${error.message}")
        exitProcess(1)
    }

    val userText = readPrompt()

    if (userText.isBlank()) {
        System.err.println("Ошибка: задайте запрос в переменной $PROMPT_ENV или в файле $PROMPT_FILE")
        exitProcess(1)
    }

    try {
        val answer = askDeepSeek(apiKey, userText, settings)
        println(answer)
    } catch (error: Exception) {
        System.err.println("Ошибка: ${error.message}")
        exitProcess(1)
    }
}

private fun parseArgs(args: Array<String>): ModelSettings {
    var maxTokens = DEFAULT_MAX_TOKENS
    var stop: String? = null
    var index = 0

    while (index < args.size) {
        val arg = args[index]

        when {
            arg == "--max-tokens" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после --max-tokens нужно указать число")
                maxTokens = parseMaxTokens(value)
                index += 2
            }

            arg.startsWith("--max-tokens=") -> {
                maxTokens = parseMaxTokens(arg.substringAfter("="))
                index++
            }

            arg == "--stop" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после --stop нужно указать строку")
                stop = value.ifBlank { null }
                index += 2
            }

            arg.startsWith("--stop=") -> {
                stop = arg.substringAfter("=").ifBlank { null }
                index++
            }

            else -> throw IllegalArgumentException("неизвестный аргумент: $arg")
        }
    }

    return ModelSettings(maxTokens = maxTokens, stop = stop)
}

private fun parseMaxTokens(value: String): Int {
    val maxTokens = value.toIntOrNull()
        ?: throw IllegalArgumentException("--max-tokens должен быть числом")

    if (maxTokens <= 0) {
        throw IllegalArgumentException("--max-tokens должен быть больше 0")
    }

    return maxTokens
}

private fun readPrompt(): String {
    val promptFromEnv = System.getenv(PROMPT_ENV)
    if (!promptFromEnv.isNullOrBlank()) {
        return promptFromEnv.trim()
    }

    val promptPath = Path.of(PROMPT_FILE)
    if (Files.exists(promptPath)) {
        return Files.readString(promptPath).trim()
    }

    return ""
}

private fun askDeepSeek(apiKey: String, userText: String, settings: ModelSettings): String {
    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()
    val jsonType = "application/json; charset=utf-8".toMediaType()

    val requestJson = JSONObject()
        .put("model", MODEL)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userText)))
        .put("thinking", JSONObject().put("type", "disabled"))
        .put("max_tokens", settings.maxTokens)
        .put("stream", false)

    if (settings.stop != null) {
        requestJson.put("stop", JSONArray().put(settings.stop))
    }

    val request = Request.Builder()
        .url(API_URL)
        .addHeader("Authorization", "Bearer $apiKey")
        .addHeader("Content-Type", "application/json")
        .post(requestJson.toString().toRequestBody(jsonType))
        .build()

    client.newCall(request).execute().use { response ->
        val responseText = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}: $responseText")
        }

        val answer = JSONObject(responseText)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .optString("content")
            .trim()

        if (answer.isBlank()) {
            throw IOException("модель вернула пустой ответ")
        }

        return answer
    }
}

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private const val API_URL = "https://api.deepseek.com/chat/completions"
private const val DEFAULT_MODEL = "deepseek-v4-flash"
private const val DEFAULT_THINKING = "disabled"
private const val DEFAULT_MAX_TOKENS = 500
private const val TOKENS_PER_MILLION = 1_000_000.0

private val EXIT_COMMANDS = setOf("/exit", "/quit", "exit", "quit", "выход")

data class ModelSettings(
    val model: String = DEFAULT_MODEL,
    val thinking: String = DEFAULT_THINKING,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val stop: String? = null,
    val temperature: Double? = null,
)

data class ModelPricing(
    val inputCacheHitPerMillion: Double,
    val inputCacheMissPerMillion: Double,
    val outputPerMillion: Double,
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val promptCacheHitTokens: Int?,
    val promptCacheMissTokens: Int?,
)

data class ModelResponse(
    val answer: String,
    val usage: TokenUsage,
    val elapsedMillis: Long,
    val estimatedCost: Double,
)

data class DialogStats(
    val responseCount: Int = 0,
    val elapsedMillis: Long = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val promptCacheHitTokens: Int = 0,
    val promptCacheMissTokens: Int = 0,
    val hasCacheStats: Boolean = false,
    val estimatedCost: Double = 0.0,
) {
    fun plus(response: ModelResponse): DialogStats {
        val usage = response.usage

        return copy(
            responseCount = responseCount + 1,
            elapsedMillis = elapsedMillis + response.elapsedMillis,
            promptTokens = promptTokens + usage.promptTokens,
            completionTokens = completionTokens + usage.completionTokens,
            totalTokens = totalTokens + usage.totalTokens,
            promptCacheHitTokens = promptCacheHitTokens + (usage.promptCacheHitTokens ?: 0),
            promptCacheMissTokens = promptCacheMissTokens + (usage.promptCacheMissTokens ?: 0),
            hasCacheStats = hasCacheStats || usage.promptCacheHitTokens != null || usage.promptCacheMissTokens != null,
            estimatedCost = estimatedCost + response.estimatedCost,
        )
    }
}

data class ChatMessage(
    val role: String,
    val content: String,
)

class DeepSeekAgent(
    private val client: DeepSeekClient,
    private val settings: ModelSettings,
) {
    private val messages = mutableListOf<ChatMessage>()

    fun ask(userText: String): ModelResponse {
        val userMessage = ChatMessage(role = "user", content = userText)
        val response = client.complete(messages + userMessage, settings)

        messages += userMessage
        messages += ChatMessage(role = "assistant", content = response.answer)

        return response
    }
}

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

private val SUPPORTED_MODELS = setOf("deepseek-v4-flash", "deepseek-v4-pro")

private val MODEL_PRICING = mapOf(
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

    val agent = DeepSeekAgent(
        client = DeepSeekClient(apiKey),
        settings = settings,
    )
    var dialogStats = DialogStats()

    println("DeepSeek Agent")
    println("Введите сообщение. Для выхода: /exit, /quit или пустой EOF.")
    println()

    while (true) {
        print("Вы: ")
        val input = readLine() ?: break
        val userText = input.trim()

        if (userText.isBlank()) {
            continue
        }

        if (userText in EXIT_COMMANDS) {
            println("Диалог завершен.")
            break
        }

        try {
            val response = agent.ask(userText)
            dialogStats = dialogStats.plus(response)
            println()
            println("Агент: ${response.answer}")
            println()
        } catch (error: Exception) {
            System.err.println("Ошибка: ${error.message}")
            println()
        }
    }

    println()
    printDialogStats(settings, dialogStats)
}

private fun parseArgs(args: Array<String>): ModelSettings {
    var model = DEFAULT_MODEL
    var thinking = DEFAULT_THINKING
    var maxTokens = DEFAULT_MAX_TOKENS
    var stop: String? = null
    var temperature: Double? = null
    var index = 0

    while (index < args.size) {
        val arg = args[index]

        when {
            arg == "--model" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после --model нужно указать название модели")
                model = parseModel(value)
                index += 2
            }

            arg.startsWith("--model=") -> {
                model = parseModel(arg.substringAfter("="))
                index++
            }

            arg == "--thinking" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после --thinking нужно указать enabled или disabled")
                thinking = parseThinking(value)
                index += 2
            }

            arg.startsWith("--thinking=") -> {
                thinking = parseThinking(arg.substringAfter("="))
                index++
            }

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

            arg == "--temperature" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после --temperature нужно указать число")
                temperature = parseTemperature(value)
                index += 2
            }

            arg.startsWith("--temperature=") -> {
                temperature = parseTemperature(arg.substringAfter("="))
                index++
            }

            else -> throw IllegalArgumentException("неизвестный аргумент: $arg")
        }
    }

    return ModelSettings(
        model = model,
        thinking = thinking,
        maxTokens = maxTokens,
        stop = stop,
        temperature = temperature,
    )
}

private fun parseModel(value: String): String {
    val model = value.trim()

    if (model !in SUPPORTED_MODELS) {
        throw IllegalArgumentException("--model должен быть deepseek-v4-flash или deepseek-v4-pro")
    }

    return model
}

private fun parseThinking(value: String): String {
    return when (value.trim().lowercase()) {
        "enabled", "enable", "true", "on", "yes", "thinking", "думать", "думающий" -> "enabled"
        "disabled", "disable", "false", "off", "no", "non-thinking", "нет", "недумающий" -> "disabled"
        else -> throw IllegalArgumentException("--thinking должен быть enabled или disabled")
    }
}

private fun parseMaxTokens(value: String): Int {
    val maxTokens = value.toIntOrNull()
        ?: throw IllegalArgumentException("--max-tokens должен быть числом")

    if (maxTokens <= 0) {
        throw IllegalArgumentException("--max-tokens должен быть больше 0")
    }

    return maxTokens
}

private fun parseTemperature(value: String): Double {
    val temperature = value.toDoubleOrNull()
        ?: throw IllegalArgumentException("--temperature должен быть числом")

    if (temperature < 0.0 || temperature > 2.0) {
        throw IllegalArgumentException("--temperature должен быть от 0 до 2")
    }

    return temperature
}

private fun List<ChatMessage>.toJson(): JSONArray {
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

private fun parseUsage(responseJson: JSONObject): TokenUsage {
    val usageJson = responseJson.optJSONObject("usage")
        ?: return TokenUsage(
            promptTokens = 0,
            completionTokens = 0,
            totalTokens = 0,
            promptCacheHitTokens = null,
            promptCacheMissTokens = null,
        )

    return TokenUsage(
        promptTokens = usageJson.optInt("prompt_tokens", 0),
        completionTokens = usageJson.optInt("completion_tokens", 0),
        totalTokens = usageJson.optInt("total_tokens", 0),
        promptCacheHitTokens = usageJson.optionalInt("prompt_cache_hit_tokens"),
        promptCacheMissTokens = usageJson.optionalInt("prompt_cache_miss_tokens"),
    )
}

private fun JSONObject.optionalInt(name: String): Int? {
    return if (has(name) && !isNull(name)) {
        optInt(name)
    } else {
        null
    }
}

private fun calculateCost(model: String, usage: TokenUsage): Double {
    val pricing = MODEL_PRICING[model] ?: return 0.0

    val inputCost = if (usage.promptCacheHitTokens != null || usage.promptCacheMissTokens != null) {
        ((usage.promptCacheHitTokens ?: 0) / TOKENS_PER_MILLION * pricing.inputCacheHitPerMillion) +
            ((usage.promptCacheMissTokens ?: 0) / TOKENS_PER_MILLION * pricing.inputCacheMissPerMillion)
    } else {
        usage.promptTokens / TOKENS_PER_MILLION * pricing.inputCacheMissPerMillion
    }

    val outputCost = usage.completionTokens / TOKENS_PER_MILLION * pricing.outputPerMillion

    return inputCost + outputCost
}

private fun printDialogStats(settings: ModelSettings, stats: DialogStats) {
    println("=== Статистика диалога ===")
    println("Модель: ${settings.model}")
    println("Thinking mode: ${settings.thinking}")
    println("Ответов агента: ${stats.responseCount}")
    println("Общее время ответов: ${formatSeconds(stats.elapsedMillis)} сек")
    println("Prompt tokens: ${stats.promptTokens}")
    println("Completion tokens: ${stats.completionTokens}")
    println("Total tokens: ${stats.totalTokens}")

    if (stats.hasCacheStats) {
        println("Prompt cache hit tokens: ${stats.promptCacheHitTokens}")
        println("Prompt cache miss tokens: ${stats.promptCacheMissTokens}")
    }

    println("Примерная стоимость: ${formatUsd(stats.estimatedCost)}")
}

private fun formatSeconds(milliseconds: Long): String {
    return String.format(Locale.US, "%.2f", milliseconds / 1000.0)
}

private fun formatUsd(value: Double): String {
    return "$" + String.format(Locale.US, "%.8f", value)
}

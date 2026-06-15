import java.nio.file.Path
import java.util.Locale
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val apiKey = System.getenv("DEEPSEEK_API_KEY")

    if (apiKey.isNullOrBlank()) {
        System.err.println("Ошибка: переменная окружения DEEPSEEK_API_KEY не задана")
        exitProcess(1)
    }

    val appSettings = try {
        parseArgs(args)
    } catch (error: IllegalArgumentException) {
        System.err.println("Ошибка: ${error.message}")
        exitProcess(1)
    }
    val settings = appSettings.modelSettings
    val tokenCounter = SimpleTokenCounter()
    val client = DeepSeekClient(apiKey)
    val summarizer = if (appSettings.dryRunTokens) {
        NoOpConversationSummarizer()
    } else {
        DeepSeekConversationSummarizer(client = client, settings = settings)
    }

    val agent = DeepSeekAgent(
        client = client,
        settings = settings,
        messageStore = JsonMessageStore(appSettings.historyPath),
        tokenCounter = tokenCounter,
        contextCompressor = ContextCompressor(
            enabled = appSettings.compressionEnabled,
            recentMessages = appSettings.recentMessages,
            summaryChunkSize = appSettings.summaryChunkSize,
            summaryStore = TextSummaryStore(appSettings.summaryPath),
            summarizer = summarizer,
        ),
    )
    var dialogStats = DialogStats()

    println("DeepSeek Agent")
    println("Введите сообщение. Для выхода: /exit, /quit или пустой EOF.")
    println("История: ${appSettings.historyPath.toAbsolutePath()}")
    println("Загружено сообщений из истории: ${agent.messageCount}")
    if (appSettings.compressionEnabled) {
        println("Компрессия контекста: включена")
        println("Последние сообщения без сжатия: ${appSettings.recentMessages}")
        println("Summary: ${appSettings.summaryPath.toAbsolutePath()}")
        println("Размер summary: ${tokenCounter.countText(agent.summaryText)} токенов")
    } else {
        println("Компрессия контекста: выключена")
    }
    println()

        if (appSettings.compactNow) {
            val remainingMessages = agent.compactHistoryNow()
            println("История сжата. Сообщений осталось в JSON: $remainingMessages")
            println("Summary обновлен: ${appSettings.summaryPath.toAbsolutePath()}")
            return
        }

        val messagesBeforeStartupCompact = agent.messageCount
        val remainingMessages = try {
            agent.compactHistoryIfNeeded()
        } catch (error: Exception) {
            System.err.println("Ошибка сжатия истории: ${error.message}")
            null
        }

        if (remainingMessages != null && remainingMessages != messagesBeforeStartupCompact) {
            println("История сжата при старте. Сообщений осталось в JSON: $remainingMessages")
            println("Summary обновлен: ${appSettings.summaryPath.toAbsolutePath()}")
        }

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
            val tokenReport = agent.previewTokens(userText)
            printTokenReport(tokenReport)

            if (tokenReport.isOverLimit && !appSettings.allowOverLimit) {
                println("Запрос не отправлен: прогноз превышает лимит модели.")
                println("Добавьте --allow-over-limit, если хотите отправить запрос и увидеть ошибку API на практике.")
                println()
                continue
            }

            if (appSettings.dryRunTokens) {
                println("Dry run: запрос не отправлен в API.")
                println()
                continue
            }

            val response = agent.ask(userText)
            dialogStats = dialogStats.plus(response)
            println()
            println("Агент: ${response.answer}")
            println()
            printResponseTokenStats(response, dialogStats)
            println()
        } catch (error: Exception) {
            System.err.println("Ошибка: ${error.message}")
            println()
        }
    }

    println()
    printDialogStats(settings, dialogStats)
}

private fun parseArgs(args: Array<String>): AppSettings {
    var model = DEFAULT_MODEL
    var thinking = DEFAULT_THINKING
    var maxTokens = DEFAULT_MAX_TOKENS
    var stop: String? = null
    var temperature: Double? = null
    var historyPath = Path.of(DEFAULT_HISTORY_FILE)
    var summaryPath = Path.of(DEFAULT_SUMMARY_FILE)
    var allowOverLimit = false
    var dryRunTokens = false
    var compressionEnabled = false
    var recentMessages = DEFAULT_RECENT_MESSAGES
    var summaryChunkSize = DEFAULT_SUMMARY_CHUNK_SIZE
    var compactNow = false
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

            arg == "--history-file" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после --history-file нужно указать путь к JSON-файлу")
                historyPath = parseHistoryPath(value)
                index += 2
            }

            arg.startsWith("--history-file=") -> {
                historyPath = parseHistoryPath(arg.substringAfter("="))
                index++
            }

            arg == "--allow-over-limit" -> {
                allowOverLimit = true
                index++
            }

            arg == "--dry-run-tokens" -> {
                dryRunTokens = true
                index++
            }

            arg == "--compress-context" -> {
                compressionEnabled = true
                index++
            }

            arg == "--recent-messages" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после --recent-messages нужно указать число")
                recentMessages = parsePositiveInt(value, "--recent-messages")
                index += 2
            }

            arg.startsWith("--recent-messages=") -> {
                recentMessages = parsePositiveInt(arg.substringAfter("="), "--recent-messages")
                index++
            }

            arg == "--summary-file" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после --summary-file нужно указать путь к txt-файлу")
                summaryPath = parseSummaryPath(value)
                index += 2
            }

            arg.startsWith("--summary-file=") -> {
                summaryPath = parseSummaryPath(arg.substringAfter("="))
                index++
            }

            arg == "--summary-chunk-size" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после --summary-chunk-size нужно указать число")
                summaryChunkSize = parsePositiveInt(value, "--summary-chunk-size")
                index += 2
            }

            arg.startsWith("--summary-chunk-size=") -> {
                summaryChunkSize = parsePositiveInt(arg.substringAfter("="), "--summary-chunk-size")
                index++
            }

            arg == "--compact-now" -> {
                compactNow = true
                compressionEnabled = true
                index++
            }

            else -> throw IllegalArgumentException("неизвестный аргумент: $arg")
        }
    }

    return AppSettings(
        modelSettings = ModelSettings(
            model = model,
            thinking = thinking,
            maxTokens = maxTokens,
            stop = stop,
            temperature = temperature,
        ),
        historyPath = historyPath,
        summaryPath = summaryPath,
        allowOverLimit = allowOverLimit,
        dryRunTokens = dryRunTokens,
        compressionEnabled = compressionEnabled,
        recentMessages = recentMessages,
        summaryChunkSize = summaryChunkSize,
        compactNow = compactNow,
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

private fun parseHistoryPath(value: String): Path {
    val path = value.trim()

    if (path.isBlank()) {
        throw IllegalArgumentException("--history-file не должен быть пустым")
    }

    return Path.of(path)
}

private fun parseSummaryPath(value: String): Path {
    val path = value.trim()

    if (path.isBlank()) {
        throw IllegalArgumentException("--summary-file не должен быть пустым")
    }

    return Path.of(path)
}

private fun parsePositiveInt(value: String, optionName: String): Int {
    val number = value.toIntOrNull()
        ?: throw IllegalArgumentException("$optionName должен быть числом")

    if (number <= 0) {
        throw IllegalArgumentException("$optionName должен быть больше 0")
    }

    return number
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

private fun printTokenReport(report: TokenReport) {
    println()
    println("=== Токены перед запросом ===")
    println("Компрессия: ${if (report.compressionEnabled) "включена" else "выключена"}")
    println("Сообщений в сохраненной истории: ${report.savedHistoryMessages}")
    println("Сообщений в API-запросе: ${report.requestMessages}")
    println("Сжато сообщений в summary: ${report.summarizedMessages}")
    println("Summary tokens: ${report.summaryTokens}")
    println("Текущий запрос: ${report.currentRequestTokens}")
    println("Полная история без сжатия: ${report.fullHistoryTokens}")
    println("История в запросе после сжатия: ${report.effectiveHistoryTokens}")
    println("Prompt всего: ${report.promptTokens}")
    println("Max response tokens: ${report.maxResponseTokens}")
    println("Prompt + максимум ответа: ${report.projectedTotalTokens}")
    println("Лимит модели: ${report.contextLimit}")
    println("Использование лимита: ${formatPercent(report.usagePercent)}")

    if (report.isOverLimit) {
        println("Статус: превышение лимита")
    } else {
        println("Статус: в пределах лимита")
    }
}

private fun printResponseTokenStats(response: ModelResponse, dialogStats: DialogStats) {
    val usage = response.usage

    println("=== Факт по ответу API ===")
    println("Prompt tokens: ${usage.promptTokens}")
    println("Completion tokens: ${usage.completionTokens}")
    println("Total tokens: ${usage.totalTokens}")
    println("Стоимость этого шага: ${formatUsd(response.estimatedCost)}")
    println("Накопленная стоимость диалога: ${formatUsd(dialogStats.estimatedCost)}")
}

private fun formatSeconds(milliseconds: Long): String {
    return String.format(Locale.US, "%.2f", milliseconds / 1000.0)
}

private fun formatPercent(value: Double): String {
    return String.format(Locale.US, "%.2f%%", value)
}

private fun formatUsd(value: Double): String {
    return "$" + String.format(Locale.US, "%.8f", value)
}

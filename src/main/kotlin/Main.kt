import java.nio.file.Path
import java.util.Locale
import kotlin.system.exitProcess

private val PROFILE_FIELDS = setOf("tone", "gender", "length", "language", "lang", "notes", "note")

fun main(args: Array<String>) {
    val parsedSettings = try {
        parseArgs(args)
    } catch (error: IllegalArgumentException) {
        System.err.println("Ошибка: ${error.message}")
        exitProcess(1)
    }
    val baseLongTermStore = LongTermMemoryStore(parsedSettings.memoryDir)
    val memoryUser = baseLongTermStore.sanitizeUserId(parsedSettings.memoryUser)
    val appSettings = parsedSettings.withUserScopedDefaults(memoryUser)
    val longTermStore = LongTermMemoryStore(appSettings.memoryDir)
    val workingStore = WorkingMemoryStore(appSettings.memoryDir)
    val memory = AssistantMemory(
        shortTerm = ShortTermMemory(),
        working = WorkingMemory(userId = memoryUser, store = workingStore),
        longTerm = LongTermMemory(userId = memoryUser, store = longTermStore),
    )
    memory.loadUserProfileIntoWorking()
    val memoryFormatter = MemoryStatusFormatter()

    if (appSettings.memoryStatus) {
        println(memoryFormatter.format(memory))
        println()
        println("User workspace: ${longTermStore.userDir(memoryUser).toAbsolutePath()}")
        println("History file: ${appSettings.historyPath.toAbsolutePath()}")
        println("Working memory file: ${workingStore.userPath(memoryUser).toAbsolutePath()}")
        println("Long-term memory file: ${longTermStore.userPath(memoryUser).toAbsolutePath()}")
        return
    }

    val apiKey = System.getenv("DEEPSEEK_API_KEY")

    if (apiKey.isNullOrBlank()) {
        System.err.println("Ошибка: переменная окружения DEEPSEEK_API_KEY не задана")
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
    val branchStore = if (appSettings.contextStrategy == ContextStrategyType.BRANCHING) {
        BranchingMessageStore(
            branchDir = appSettings.branchDir,
            initialBranch = appSettings.branchName,
            fallbackHistoryPath = appSettings.historyPath,
        )
    } else {
        null
    }
    val messageStore = branchStore ?: JsonMessageStore(appSettings.historyPath)
    val contextManager = buildContextManager(
        appSettings = appSettings,
        summarizer = summarizer,
    )

    val agent = DeepSeekAgent(
        client = client,
        settings = settings,
        messageStore = messageStore,
        tokenCounter = tokenCounter,
        contextManager = contextManager,
        memory = memory,
    )
    var dialogStats = DialogStats()

    println("DeepSeek Agent")
    println("Введите сообщение. Для выхода: /exit, /quit или пустой EOF.")
    println("Папка пользователя: ${longTermStore.userDir(memoryUser).toAbsolutePath()}")
    println("История: ${appSettings.historyPath.toAbsolutePath()}")
    println("Пользователь памяти: $memoryUser")
    println("Рабочая память: ${workingStore.userPath(memoryUser).toAbsolutePath()}")
    println("Долговременная память: ${longTermStore.userPath(memoryUser).toAbsolutePath()}")
    println("Загружено сообщений из истории: ${agent.messageCount}")
    println("Стратегия контекста: ${appSettings.contextStrategy.name.lowercase()}")
    if (branchStore != null) {
        println("Активная ветка: ${branchStore.activeBranch}")
        println("Папка веток: ${appSettings.branchDir.toAbsolutePath()}")
    }
    if (appSettings.contextStrategy == ContextStrategyType.SUMMARY) {
        println("Компрессия контекста: включена")
        println("Последние сообщения без сжатия: ${appSettings.recentMessages}")
        println("Summary: ${appSettings.summaryPath.toAbsolutePath()}")
        println("Размер summary: ${tokenCounter.countText(agent.summaryText)} токенов")
    } else if (appSettings.contextStrategy == ContextStrategyType.FACTS) {
        println("Facts: ${appSettings.factsPath.toAbsolutePath()}")
        println("Facts tokens: ${tokenCounter.countText(agent.summaryText)}")
    } else {
        println("Компрессия контекста: выключена")
    }
    if (memory.working.userProfile() == null) {
        println("Профиль пользователя не найден. Перед первым обычным запросом запустится онбординг.")
    }
    println()

    if (appSettings.compactNow) {
        val remainingMessages = agent.compactHistoryNow()
        println("История сжата. Сообщений осталось в JSON: $remainingMessages")
        if (appSettings.contextStrategy == ContextStrategyType.SUMMARY) {
            println("Summary обновлен: ${appSettings.summaryPath.toAbsolutePath()}")
        }
        return
    }

    if (appSettings.contextStrategy == ContextStrategyType.SUMMARY) {
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
            askWhetherTaskIsSolved(memory)
            break
        }

        if (branchStore != null && handleBranchCommand(userText, branchStore, agent)) {
            continue
        }

        try {
            if (handleMemoryCommand(userText, memory, memoryFormatter)) {
                continue
            }
        } catch (error: IllegalArgumentException) {
            System.err.println("Ошибка: ${error.message}")
            println()
            continue
        }

        ensureUserProfileForRequest(memory)
        captureImplicitMemory(userText, memory)

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

private fun askWhetherTaskIsSolved(memory: AssistantMemory) {
    if (memory.working.isEmpty()) {
        return
    }

    print("Текущая задача решена? Очистить WorkingMemory? [y/N]: ")
    val answer = readLine()?.trim()?.lowercase().orEmpty()

    if (answer in setOf("y", "yes", "д", "да")) {
        memory.working.clear()
        println("WorkingMemory очищена.")
    } else {
        println("WorkingMemory сохранена для следующей сессии.")
    }
}

private fun ensureUserProfileForRequest(memory: AssistantMemory): UserProfile {
    memory.loadUserProfileIntoWorking()?.let { profile ->
        return profile
    }

    val profile = runUserProfileOnboarding(memory)
    memory.longTerm.saveUserProfile(profile)
    memory.loadUserProfileIntoWorking()
    println("Профиль сохранен.")
    println()

    return profile
}

private fun runUserProfileOnboarding(memory: AssistantMemory): UserProfile {
    println("Давайте настроим персонализацию ответов. Можно нажимать Enter, чтобы оставить значение по умолчанию.")
    println()

    val tone = askOnboardingQuestion(
        memory = memory,
        question = "Тон общения: 1 - формальный, 2 - дружелюбный, 3 - нейтральный [по умолчанию: 3]",
        defaultValue = Tone.Neutral,
        parser = { Tone.fromInput(it) },
    )
    val gender = askOnboardingQuestion(
        memory = memory,
        question = "Пол ассистента: 1 - мужской, 2 - женский, 3 - без рода [по умолчанию: 3]",
        defaultValue = AssistantGender.None,
        parser = { AssistantGender.fromInput(it) },
    )
    val length = askOnboardingQuestion(
        memory = memory,
        question = "Длина ответа: 1 - кратко, 2 - обычно, 3 - подробно [по умолчанию: 2]",
        defaultValue = AnswerLength.Normal,
        parser = { AnswerLength.fromInput(it) },
    )
    val languageAnswer = askOnboardingText(
        memory = memory,
        question = "Язык ответов, например ru или en [по умолчанию: ru]",
    )
    val notes = askOnboardingText(
        memory = memory,
        question = "Дополнительные пожелания к стилю [можно пропустить]",
    )

    return UserProfile(
        tone = tone,
        gender = gender,
        length = length,
        language = languageAnswer.ifBlank { "ru" },
        notes = notes,
    )
}

private fun <T> askOnboardingQuestion(
    memory: AssistantMemory,
    question: String,
    defaultValue: T,
    parser: (String) -> T,
): T {
    val answer = askOnboardingText(memory, question)

    if (answer.isBlank()) {
        return defaultValue
    }

    return try {
        parser(answer)
    } catch (error: IllegalArgumentException) {
        println("Не распознала ответ, оставляю значение по умолчанию: $defaultValue")
        defaultValue
    }
}

private fun askOnboardingText(
    memory: AssistantMemory,
    question: String,
): String {
    println(question)
    print("> ")
    memory.shortTerm.add(ChatMessage(role = "assistant", content = question))
    val answer = readLine()?.trim().orEmpty()
    memory.shortTerm.add(ChatMessage(role = "user", content = answer.ifBlank { "(пропущено)" }))

    return answer
}

private fun buildContextManager(
    appSettings: AppSettings,
    summarizer: ConversationSummarizer,
): ContextManager {
    return when (appSettings.contextStrategy) {
        ContextStrategyType.FULL -> FullContextManager()
        ContextStrategyType.SLIDING -> SlidingWindowContextManager(appSettings.recentMessages)
        ContextStrategyType.FACTS -> FactsContextManager(
            recentMessages = appSettings.recentMessages,
            factsStore = JsonFactsStore(appSettings.factsPath),
        )
        ContextStrategyType.BRANCHING -> FullContextManager()
        ContextStrategyType.SUMMARY -> ContextCompressor(
            enabled = true,
            recentMessages = appSettings.recentMessages,
            summaryChunkSize = appSettings.summaryChunkSize,
            summaryStore = TextSummaryStore(appSettings.summaryPath),
            summarizer = summarizer,
        )
    }
}

private fun handleBranchCommand(
    input: String,
    branchStore: BranchingMessageStore,
    agent: DeepSeekAgent,
): Boolean {
    val parts = input.split(Regex("\\s+")).filter { it.isNotBlank() }

    if (parts.isEmpty()) {
        return false
    }

    return when {
        parts[0] == "/checkpoint" && parts.size == 2 -> {
            branchStore.checkpoint(parts[1], agent.currentMessages())
            println("Checkpoint сохранен: ${parts[1]}")
            true
        }
        parts[0] == "/branch" && parts.getOrNull(1) == "create" && parts.size == 4 -> {
            branchStore.createBranch(branchName = parts[2], checkpointName = parts[3])
            agent.reloadMessages()
            println("Создана и выбрана ветка: ${parts[2]}")
            true
        }
        parts[0] == "/branch" && parts.getOrNull(1) == "switch" && parts.size == 3 -> {
            branchStore.switchBranch(parts[2])
            agent.reloadMessages()
            println("Активная ветка: ${parts[2]}")
            true
        }
        parts[0] == "/branch" && parts.getOrNull(1) == "list" -> {
            val branches = branchStore.listBranches()
            println("Ветки: ${branches.ifEmpty { listOf("(пока нет)") }.joinToString(", ")}")
            println("Активная ветка: ${branchStore.activeBranch}")
            true
        }
        else -> false
    }
}

private fun handleMemoryCommand(
    input: String,
    memory: AssistantMemory,
    formatter: MemoryStatusFormatter,
): Boolean {
    val trimmed = input.trim()
    val parts = trimmed.split(Regex("\\s+"), limit = 3).filter { it.isNotBlank() }

    if (parts.isEmpty()) {
        return false
    }

    fun recordCommandResult(answer: String) {
        memory.shortTerm.add(ChatMessage(role = "user", content = trimmed))
        memory.shortTerm.add(ChatMessage(role = "assistant", content = answer))
        println(answer)
        println()
    }

    return when {
        parts[0] == "/memory-status" || parts[0] == "memory-status" -> {
            println(formatter.format(memory))
            println()
            true
        }

        parts[0] == "/work" && parts.getOrNull(1) == "clear" -> {
            memory.working.clear()
            recordCommandResult("WorkingMemory очищена.")
            true
        }

        parts[0] == "/work" && parts.getOrNull(1) == "flag" && parts.size == 3 -> {
            val (key, value) = splitKeyValue(parts[2], "/work flag")
            memory.working.setFlag(key, parseBooleanFlag(value))
            recordCommandResult("Флаг WorkingMemory сохранен: $key = ${parseBooleanFlag(value)}")
            true
        }

        parts[0] == "/work" && parts.size == 3 -> {
            val layer = parts[1]
            val (key, value) = splitKeyValue(parts[2], "/work $layer")

            when (layer) {
                "context" -> memory.working.putContext(key, value)
                "result" -> memory.working.putResult(key, value)
                else -> return false
            }

            recordCommandResult("WorkingMemory сохранена: $layer.$key")
            true
        }

        parts[0] == "/profile" -> {
            val answer = handleProfileCommand(trimmed, parts, memory)
            recordCommandResult(answer)
            true
        }

        parts[0] == "/decision" && parts.size >= 2 -> {
            val (key, value) = splitKeyValue(trimmed.removePrefix("/decision").trim(), "/decision")
            memory.longTerm.rememberDecision(key, value)
            recordCommandResult("LongTermMemory.decisions сохранено: $key")
            true
        }

        parts[0] == "/knowledge" && parts.size >= 2 -> {
            val (key, value) = splitKeyValue(trimmed.removePrefix("/knowledge").trim(), "/knowledge")
            memory.longTerm.rememberKnowledge(key, value)
            recordCommandResult("LongTermMemory.knowledge сохранено: $key")
            true
        }

        else -> false
    }
}

private fun handleProfileCommand(
    input: String,
    parts: List<String>,
    memory: AssistantMemory,
): String {
    if (parts.size == 1) {
        val profile = memory.loadUserProfileIntoWorking()

        return if (profile == null) {
            "Профиль пользователя отсутствует. Онбординг запустится перед следующим обычным запросом."
        } else {
            "Текущий профиль:\n${profile.toDisplayText()}"
        }
    }

    val command = parts[1].lowercase()

    if (command == "reset") {
        memory.longTerm.deleteUserProfile()
        memory.working.clearUserProfile()
        return "Профиль пользователя удален. Онбординг запустится перед следующим обычным запросом."
    }

    if (parts.size >= 3 && command in PROFILE_FIELDS) {
        val currentProfile = memory.longTerm.loadUserProfile() ?: UserProfile()
        val updatedProfile = currentProfile.withField(command, parts[2])
        memory.longTerm.saveUserProfile(updatedProfile)
        memory.loadUserProfileIntoWorking()

        return "Профиль обновлен:\n${updatedProfile.toDisplayText()}"
    }

    splitKeyValueOrNull(input.removePrefix("/profile").trim())?.let { (key, value) ->
        if (key.lowercase() in PROFILE_FIELDS) {
            val currentProfile = memory.longTerm.loadUserProfile() ?: UserProfile()
            val updatedProfile = currentProfile.withField(key, value)
            memory.longTerm.saveUserProfile(updatedProfile)
            memory.loadUserProfileIntoWorking()

            return "Профиль обновлен:\n${updatedProfile.toDisplayText()}"
        }

        memory.longTerm.rememberProfile(key, value)
        return "LongTermMemory.profile сохранен: $key"
    }

    throw IllegalArgumentException("/profile ожидает поле tone/gender/length/language/notes, reset или формат key=value")
}

private fun captureImplicitMemory(input: String, memory: AssistantMemory) {
    val lower = input.lowercase()

    when {
        lower.startsWith("запомни результат поиска") -> {
            val value = input.substringAfter(":", missingDelimiterValue = "")
                .ifBlank { input.substringAfter("запомни результат поиска", missingDelimiterValue = "").trim() }

            if (value.isNotBlank()) {
                memory.working.putResult("searchResult", value)
            }
        }

        lower.startsWith("запомни в профиль") -> {
            splitKeyValueOrNull(input.substringAfter("запомни в профиль"))?.let { (key, value) ->
                memory.longTerm.rememberProfile(key, value)
            }
        }

        lower.startsWith("запомни знание") -> {
            splitKeyValueOrNull(input.substringAfter("запомни знание"))?.let { (key, value) ->
                memory.longTerm.rememberKnowledge(key, value)
            }
        }

        lower.startsWith("запомни решение") -> {
            splitKeyValueOrNull(input.substringAfter("запомни решение"))?.let { (key, value) ->
                memory.longTerm.rememberDecision(key, value)
            }
        }
    }
}

private fun splitKeyValue(input: String, commandName: String): Pair<String, String> {
    return splitKeyValueOrNull(input)
        ?: throw IllegalArgumentException("$commandName ожидает формат key=value или key: value")
}

private fun splitKeyValueOrNull(input: String): Pair<String, String>? {
    val delimiterIndex = listOf(
        input.indexOf('='),
        input.indexOf(':'),
    ).filter { it >= 0 }.minOrNull() ?: return null
    val key = input.substring(0, delimiterIndex).trim()
    val value = input.substring(delimiterIndex + 1).trim()

    if (key.isBlank() || value.isBlank()) {
        return null
    }

    return key to value
}

private fun parseBooleanFlag(value: String): Boolean {
    return when (value.trim().lowercase()) {
        "true", "yes", "on", "1", "да", "истина" -> true
        "false", "no", "off", "0", "нет", "ложь" -> false
        else -> throw IllegalArgumentException("флаг должен быть true/false")
    }
}

private fun AppSettings.withUserScopedDefaults(memoryUser: String): AppSettings {
    val userDir = memoryDir.resolve(memoryUser)

    return copy(
        historyPath = if (historyPathExplicit) historyPath else userDir.resolve(DEFAULT_HISTORY_FILE),
        summaryPath = if (summaryPathExplicit) summaryPath else userDir.resolve(DEFAULT_SUMMARY_FILE),
        factsPath = if (factsPathExplicit) factsPath else userDir.resolve(DEFAULT_FACTS_FILE),
        branchDir = if (branchDirExplicit) branchDir else userDir.resolve(DEFAULT_BRANCH_DIR),
    )
}

private fun parseArgs(args: Array<String>): AppSettings {
    var model = DEFAULT_MODEL
    var thinking = DEFAULT_THINKING
    var maxTokens = DEFAULT_MAX_TOKENS
    var stop: String? = null
    var temperature: Double? = null
    var historyPath = Path.of(DEFAULT_HISTORY_FILE)
    var summaryPath = Path.of(DEFAULT_SUMMARY_FILE)
    var factsPath = Path.of(DEFAULT_FACTS_FILE)
    var branchDir = Path.of(DEFAULT_BRANCH_DIR)
    var branchName = DEFAULT_BRANCH_NAME
    var memoryDir = Path.of(DEFAULT_MEMORY_DIR)
    var memoryUser = DEFAULT_MEMORY_USER
    var memoryStatus = false
    var historyPathExplicit = false
    var summaryPathExplicit = false
    var factsPathExplicit = false
    var branchDirExplicit = false
    var contextStrategy = ContextStrategyType.FULL
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
                historyPathExplicit = true
                index += 2
            }

            arg.startsWith("--history-file=") -> {
                historyPath = parseHistoryPath(arg.substringAfter("="))
                historyPathExplicit = true
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
                contextStrategy = ContextStrategyType.SUMMARY
                index++
            }

            arg == "--context-strategy" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после --context-strategy нужно указать full, summary, sliding, facts или branching")
                contextStrategy = parseContextStrategy(value)
                compressionEnabled = contextStrategy == ContextStrategyType.SUMMARY
                index += 2
            }

            arg.startsWith("--context-strategy=") -> {
                contextStrategy = parseContextStrategy(arg.substringAfter("="))
                compressionEnabled = contextStrategy == ContextStrategyType.SUMMARY
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
                summaryPathExplicit = true
                index += 2
            }

            arg.startsWith("--summary-file=") -> {
                summaryPath = parseSummaryPath(arg.substringAfter("="))
                summaryPathExplicit = true
                index++
            }

            arg == "--facts-file" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после --facts-file нужно указать путь к JSON-файлу")
                factsPath = parsePath(value, "--facts-file")
                factsPathExplicit = true
                index += 2
            }

            arg.startsWith("--facts-file=") -> {
                factsPath = parsePath(arg.substringAfter("="), "--facts-file")
                factsPathExplicit = true
                index++
            }

            arg == "--branch-dir" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после --branch-dir нужно указать путь к папке")
                branchDir = parsePath(value, "--branch-dir")
                branchDirExplicit = true
                index += 2
            }

            arg.startsWith("--branch-dir=") -> {
                branchDir = parsePath(arg.substringAfter("="), "--branch-dir")
                branchDirExplicit = true
                index++
            }

            arg == "--branch" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после --branch нужно указать имя ветки")
                branchName = parseName(value, "--branch")
                index += 2
            }

            arg.startsWith("--branch=") -> {
                branchName = parseName(arg.substringAfter("="), "--branch")
                index++
            }

            arg == "--memory-dir" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после --memory-dir нужно указать путь к папке")
                memoryDir = parsePath(value, "--memory-dir")
                index += 2
            }

            arg.startsWith("--memory-dir=") -> {
                memoryDir = parsePath(arg.substringAfter("="), "--memory-dir")
                index++
            }

            arg == "--user" || arg == "--memory-user" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("после $arg нужно указать пользователя")
                memoryUser = parseName(value, arg)
                index += 2
            }

            arg.startsWith("--user=") -> {
                memoryUser = parseName(arg.substringAfter("="), "--user")
                index++
            }

            arg.startsWith("--memory-user=") -> {
                memoryUser = parseName(arg.substringAfter("="), "--memory-user")
                index++
            }

            arg == "--memory-status" || arg == "memory-status" -> {
                memoryStatus = true
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
        factsPath = factsPath,
        branchDir = branchDir,
        branchName = branchName,
        memoryDir = memoryDir,
        memoryUser = memoryUser,
        memoryStatus = memoryStatus,
        historyPathExplicit = historyPathExplicit,
        summaryPathExplicit = summaryPathExplicit,
        factsPathExplicit = factsPathExplicit,
        branchDirExplicit = branchDirExplicit,
        contextStrategy = contextStrategy,
        allowOverLimit = allowOverLimit,
        dryRunTokens = dryRunTokens,
        compressionEnabled = compressionEnabled,
        recentMessages = recentMessages,
        summaryChunkSize = summaryChunkSize,
        compactNow = compactNow,
    )
}

private fun parseContextStrategy(value: String): ContextStrategyType {
    return when (value.trim().lowercase()) {
        "full", "none" -> ContextStrategyType.FULL
        "summary", "compress", "compression" -> ContextStrategyType.SUMMARY
        "sliding", "sliding-window", "window" -> ContextStrategyType.SLIDING
        "facts", "sticky-facts", "memory" -> ContextStrategyType.FACTS
        "branching", "branches", "branch" -> ContextStrategyType.BRANCHING
        else -> throw IllegalArgumentException("--context-strategy должен быть full, summary, sliding, facts или branching")
    }
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
    return parsePath(value, "--summary-file")
}

private fun parsePath(value: String, optionName: String): Path {
    val path = value.trim()

    if (path.isBlank()) {
        throw IllegalArgumentException("$optionName не должен быть пустым")
    }

    return Path.of(path)
}

private fun parseName(value: String, optionName: String): String {
    val name = value.trim()

    if (name.isBlank()) {
        throw IllegalArgumentException("$optionName не должен быть пустым")
    }

    return name
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
    println("Управление контекстом: ${if (report.compressionEnabled) "включено" else "выключено"}")
    println("Сообщений в сохраненной истории: ${report.savedHistoryMessages}")
    println("Сообщений в API-запросе: ${report.requestMessages}")
    println("Сообщений вне текущего окна: ${report.summarizedMessages}")
    println("Memory tokens: ${report.summaryTokens}")
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

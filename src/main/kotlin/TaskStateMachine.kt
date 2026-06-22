import org.json.JSONObject
import java.time.Instant

/**
 * Formal stages for a task handled by the assistant.
 *
 * The paused state is intentionally modeled as [TaskContext.paused], not as a
 * separate stage, so resume can return to the same stage and step directly.
 */
sealed interface TaskState {
    val name: String

    data object Planning : TaskState {
        override val name: String = "Planning"
    }

    data object Execution : TaskState {
        override val name: String = "Execution"
    }

    data object Validation : TaskState {
        override val name: String = "Validation"
    }

    data object Done : TaskState {
        override val name: String = "Done"
    }

    companion object {
        fun fromName(value: String): TaskState {
            return when (value.trim().lowercase()) {
                "planning" -> Planning
                "execution" -> Execution
                "validation" -> Validation
                "done" -> Done
                else -> throw IllegalArgumentException("неизвестный stage задачи: $value")
            }
        }
    }
}

/**
 * Recoverable context for the current task stored in WorkingMemory.current_task.
 */
data class TaskContext(
    val description: String,
    val state: TaskState,
    val currentStepNumber: Int,
    val currentStepDescription: String,
    val expectedAction: String,
    val recoveryData: Map<String, String> = emptyMap(),
    val paused: Boolean = false,
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = createdAt,
) {
    val isDone: Boolean
        get() = state == TaskState.Done

    fun toJsonObject(): JSONObject {
        return JSONObject()
            .put("description", description)
            .put("state", state.name)
            .put("currentStepNumber", currentStepNumber)
            .put("currentStepDescription", currentStepDescription)
            .put("expectedAction", expectedAction)
            .put("recoveryData", recoveryData.toJsonObject())
            .put("paused", paused)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
    }

    /** Builds the system instruction used while the task is active and not paused. */
    fun toSystemInstruction(): String {
        return """
            В WorkingMemory.current_task есть активная задача.
            Описание: $description
            Stage: ${state.name}
            Текущий шаг: $currentStepNumber. $currentStepDescription
            Ожидаемое действие: $expectedAction
            Жизненный цикл задачи строгий: Planning -> Execution -> Validation -> Done.
            Не предлагай и не выполняй пропуск этапов; переходы разрешены только через /task approve, /task next, /task pause, /task resume и /task done.
            Не пересказывай весь план заново. Продолжай с текущего stage/шага и учитывай recoveryData.
            recoveryData:
            ${recoveryData.toSortedMap().entries.joinToString("\n") { (key, value) -> "- $key = $value" }}
        """.trimIndent()
    }

    fun toDisplayText(): String {
        return buildString {
            appendLine("description = $description")
            appendLine("stage = ${state.name}")
            appendLine("paused = $paused")
            appendLine("currentStepNumber = $currentStepNumber")
            appendLine("currentStepDescription = $currentStepDescription")
            appendLine("expectedAction = $expectedAction")
            appendLine("recoveryData:")

            if (recoveryData.isEmpty()) {
                appendLine("  (пусто)")
            } else {
                recoveryData.toSortedMap().forEach { (key, value) ->
                    appendLine("  $key = $value")
                }
            }
        }.trimEnd()
    }

    companion object {
        fun fromJsonObject(json: JSONObject): TaskContext {
            return TaskContext(
                description = json.optString("description"),
                state = TaskState.fromName(json.optString("state", TaskState.Planning.name)),
                currentStepNumber = json.optInt("currentStepNumber", 1),
                currentStepDescription = json.optString("currentStepDescription"),
                expectedAction = json.optString("expectedAction"),
                recoveryData = json.optJSONObject("recoveryData").toStringMap(),
                paused = json.optBoolean("paused", false),
                createdAt = json.optString("createdAt", Instant.now().toString()),
                updatedAt = json.optString("updatedAt", Instant.now().toString()),
            )
        }
    }
}

/** Result of one state-machine transition. */
data class TaskTransition(
    val context: TaskContext,
    val message: String,
) {
    val autoExecutionPrompt: String?
        get() = context.toAutoExecutionPrompt()
}

/** Lifecycle events that can request a controlled task transition. */
sealed interface TaskEvent {
    data object NextStep : TaskEvent
    data object Pause : TaskEvent
    data object Resume : TaskEvent
    data object ApprovePlan : TaskEvent
    data object CompleteTask : TaskEvent
    data class RequestSkipStage(val input: String) : TaskEvent
}

/** Result of a strict task lifecycle transition attempt. */
sealed interface TransitionResult {
    data class Allowed(
        val context: TaskContext,
        val message: String,
    ) : TransitionResult

    data class Forbidden(
        val reason: String,
    ) : TransitionResult
}

/**
 * Strict lifecycle guard for task state changes.
 *
 * Allowed stage transitions are Planning -> Execution -> Validation -> Done.
 * Pause can wrap any non-Done stage and resume returns to the same stage.
 */
object TaskLifecycle {
    /** Attempts one lifecycle transition without mutating memory directly. */
    fun tryTransition(
        context: TaskContext,
        event: TaskEvent,
    ): TransitionResult {
        return when (event) {
            TaskEvent.ApprovePlan -> approvePlan(context)
            TaskEvent.CompleteTask -> completeTask(context)
            TaskEvent.NextStep -> next(context)
            TaskEvent.Pause -> pause(context)
            TaskEvent.Resume -> resume(context)
            is TaskEvent.RequestSkipStage -> rejectSkipStageRequest(context, event.input)
        }
    }

    /** Returns true when free-form text appears to approve the current plan. */
    fun isPlanApprovalRequest(input: String): Boolean {
        val normalized = input.normalizedTaskText()

        if (PLAN_REJECTION_MARKERS.any { marker -> marker in normalized }) {
            return false
        }

        return PLAN_APPROVAL_MARKERS.any { marker -> marker in normalized }
    }

    /** Returns true when free-form text appears to ask for skipping a stage. */
    fun isSkipStageRequest(input: String): Boolean {
        val normalized = input.normalizedTaskText()

        return SKIP_STAGE_MARKERS.any { marker -> marker in normalized }
    }

    private fun approvePlan(context: TaskContext): TransitionResult {
        if (context.paused) {
            return TransitionResult.Forbidden("Задача на паузе. Используйте /task resume, а затем подтвердите план.")
        }
        if (context.state != TaskState.Planning) {
            return TransitionResult.Forbidden("План можно утверждать только на stage Planning. Сейчас stage: ${context.state.name}.")
        }
        if (!context.isPlanReady()) {
            return TransitionResult.Forbidden("План еще не сформирован. Сначала начните задачу через /task start <описание>.")
        }
        if (context.isPlanApproved()) {
            return TransitionResult.Allowed(
                context = context,
                message = "План уже утвержден. Вызовите /task next, чтобы перейти к Execution.",
            )
        }

        val approvedContext = context.copy(
            expectedAction = expectedActionFor(
                state = TaskState.Planning,
                recoveryData = context.recoveryData + ("plan_approved" to "true"),
            ),
            recoveryData = context.recoveryData + ("plan_approved" to "true"),
            updatedAt = Instant.now().toString(),
        )

        return TransitionResult.Allowed(
            context = approvedContext,
            message = "План утвержден. Теперь доступен строгий переход Planning -> Execution через /task next.",
        )
    }

    private fun next(context: TaskContext): TransitionResult {
        if (context.paused) {
            return TransitionResult.Forbidden("Задача на паузе. Используйте /task resume, чтобы продолжить с текущего места.")
        }

        return when (context.state) {
            TaskState.Planning -> {
                if (!context.isPlanReady()) {
                    TransitionResult.Forbidden("Переход Planning -> Execution невозможен: план еще не сформирован.")
                } else if (!context.isPlanApproved()) {
                    TransitionResult.Forbidden(
                        "Я не могу перейти к Execution без утвержденного плана. Сначала проверьте план и вызовите /task approve.",
                    )
                } else {
                    moveToExecution(context)
                }
            }
            TaskState.Execution -> moveWithinOrAfterExecution(context)
            TaskState.Validation -> moveToDone(context)
            TaskState.Done -> TransitionResult.Forbidden("Задача уже завершена.")
        }
    }

    private fun pause(context: TaskContext): TransitionResult {
        if (context.state == TaskState.Done) {
            return TransitionResult.Forbidden("Завершенную задачу нельзя поставить на паузу.")
        }
        if (context.paused) {
            return TransitionResult.Allowed(
                context = context,
                message = "Задача уже на паузе: ${context.state.name}, шаг ${context.currentStepNumber}.",
            )
        }

        val pausedContext = context.copy(
            paused = true,
            expectedAction = "Задача приостановлена. Используйте /task resume для продолжения.",
            updatedAt = Instant.now().toString(),
        )

        return TransitionResult.Allowed(
            context = pausedContext,
            message = "Задача приостановлена на stage ${context.state.name}, шаг ${context.currentStepNumber}.",
        )
    }

    private fun resume(context: TaskContext): TransitionResult {
        if (!context.paused) {
            return TransitionResult.Allowed(
                context = context,
                message = "Задача уже активна: ${context.state.name}, шаг ${context.currentStepNumber}.",
            )
        }

        val resumedContext = context.copy(
            paused = false,
            expectedAction = expectedActionFor(context.state, context.recoveryData),
            updatedAt = Instant.now().toString(),
        )

        return TransitionResult.Allowed(
            context = resumedContext,
            message = "Возобновляю задачу: ${resumedContext.state.name}, шаг ${resumedContext.currentStepNumber}. Продолжаем с того же места.",
        )
    }

    private fun completeTask(context: TaskContext): TransitionResult {
        if (context.paused) {
            return TransitionResult.Forbidden("Задача на паузе. Сначала используйте /task resume.")
        }

        return when (context.state) {
            TaskState.Validation, TaskState.Done -> moveToDone(context.copy(paused = false))
            TaskState.Planning -> TransitionResult.Forbidden(
                "/task done запрещена в Planning. Сначала утвердите план, выполните задачу и пройдите Validation.",
            )
            TaskState.Execution -> TransitionResult.Forbidden(
                "/task done запрещена в Execution. Сначала выполните все шаги и перейдите в Validation.",
            )
        }
    }

    private fun rejectSkipStageRequest(
        context: TaskContext,
        input: String,
    ): TransitionResult {
        if (!isSkipStageRequest(input)) {
            return TransitionResult.Allowed(context = context, message = "")
        }

        val reason = when (context.state) {
            TaskState.Planning -> {
                "Я не могу перейти к реализации без утвержденного плана. Сначала завершите планирование и подтвердите план через /task approve."
            }
            TaskState.Execution -> {
                "Пропускать этап валидации запрещено. Сначала выполните все шаги, затем проверьте результат."
            }
            TaskState.Validation -> {
                "Нельзя завершить задачу в обход проверки. Завершение доступно только после Validation через /task next или /task done."
            }
            TaskState.Done -> {
                "Задача уже завершена, поэтому пропускать этапы больше нельзя."
            }
        }

        return TransitionResult.Forbidden(
            "$reason Переходы между этапами строго контролируются: Planning -> Execution -> Validation -> Done.",
        )
    }
}

/** Finite state machine for assistant task handling. */
object TaskStateMachine {
    fun start(description: String): TaskTransition {
        val normalizedDescription = description.trim()

        if (normalizedDescription.isBlank()) {
            throw IllegalArgumentException("/task start требует описание задачи")
        }

        val plan = buildDefaultPlan(normalizedDescription)
        val context = TaskContext(
            description = normalizedDescription,
            state = TaskState.Planning,
            currentStepNumber = 1,
            currentStepDescription = "Согласовать план из ${plan.size} шагов",
            expectedAction = expectedActionFor(
                state = TaskState.Planning,
                recoveryData = mapOf("plan_ready" to "true", "plan_approved" to "false"),
            ),
            recoveryData = mapOf(
                "plan_ready" to "true",
                "plan_approved" to "false",
                "plan_step_1" to plan[0],
                "plan_step_2" to plan[1],
                "plan_step_3" to plan[2],
                "execution_step_1" to plan[0],
                "execution_step_2" to plan[1],
                "execution_steps_total" to "2",
            ),
        )

        return TaskTransition(
            context = context,
            message = """
                TaskState: Planning
                Задача: $normalizedDescription
                План:
                1. ${plan[0]}
                2. ${plan[1]}
                3. ${plan[2]}
                Ожидаемое действие: ${context.expectedAction}
            """.trimIndent(),
        )
    }

    fun next(context: TaskContext): TaskTransition {
        return TaskLifecycle.tryTransition(context, TaskEvent.NextStep).toTaskTransition(context)
    }

    fun approvePlan(context: TaskContext): TaskTransition {
        return TaskLifecycle.tryTransition(context, TaskEvent.ApprovePlan).toTaskTransition(context)
    }

    fun pause(context: TaskContext): TaskTransition {
        return TaskLifecycle.tryTransition(context, TaskEvent.Pause).toTaskTransition(context)
    }

    fun resume(context: TaskContext): TaskTransition {
        return TaskLifecycle.tryTransition(context, TaskEvent.Resume).toTaskTransition(context)
    }

    fun done(context: TaskContext): TaskTransition {
        return TaskLifecycle.tryTransition(context, TaskEvent.CompleteTask).toTaskTransition(context)
    }

    fun status(context: TaskContext): String {
        return "Текущая задача:\n${context.toDisplayText()}"
    }

    fun skipStageViolation(
        input: String,
        context: TaskContext?,
    ): String? {
        if (context == null || context.isDone) {
            return null
        }

        val result = TaskLifecycle.tryTransition(context, TaskEvent.RequestSkipStage(input))

        return (result as? TransitionResult.Forbidden)?.reason
    }

    fun approvePlanFromText(
        input: String,
        context: TaskContext,
    ): TaskTransition? {
        if (context.state != TaskState.Planning || context.paused || context.isDone) {
            return null
        }
        if (!TaskLifecycle.isPlanApprovalRequest(input)) {
            return null
        }

        return approvePlan(context)
    }

    private fun buildDefaultPlan(description: String): List<String> {
        val lower = description.lowercase()

        return if ("стать" in lower || "article" in lower) {
            listOf(
                "Сформулировать структуру статьи и ключевые тезисы.",
                "Подготовить черновик основного текста.",
                "Проверить логику, полноту и финальную подачу.",
            )
        } else {
            listOf(
                "Определить цель, ограничения и критерии успеха.",
                "Выполнить основной рабочий шаг по задаче.",
                "Проверить результат и зафиксировать следующий шаг.",
            )
        }
    }
}

private fun TransitionResult.toTaskTransition(fallbackContext: TaskContext): TaskTransition {
    return when (this) {
        is TransitionResult.Allowed -> TaskTransition(
            context = context,
            message = message,
        )
        is TransitionResult.Forbidden -> TaskTransition(
            context = fallbackContext,
            message = reason,
        )
    }
}

private fun moveToExecution(context: TaskContext): TransitionResult.Allowed {
    val nextContext = context.copy(
        state = TaskState.Execution,
        currentStepNumber = 1,
        currentStepDescription = context.recoveryData["execution_step_1"]
            ?: context.recoveryData["plan_step_1"]
            ?: "Выполнить первый шаг задачи",
        expectedAction = expectedActionFor(TaskState.Execution, context.recoveryData),
        updatedAt = Instant.now().toString(),
    )

    return TransitionResult.Allowed(
        context = nextContext,
        message = "Переход Planning -> Execution. Приступаю к шагу 1: ${nextContext.currentStepDescription}",
    )
}

private fun moveWithinOrAfterExecution(context: TaskContext): TransitionResult.Allowed {
    val totalSteps = inferredExecutionStepsTotal(context)

    if (context.currentStepNumber < totalSteps) {
        val nextStep = context.currentStepNumber + 1
        val remainingAfterMove = totalSteps - nextStep
        val nextContext = context.copy(
            currentStepNumber = nextStep,
            currentStepDescription = context.recoveryData["execution_step_$nextStep"]
                ?: "Выполнить шаг $nextStep",
            expectedAction = expectedActionFor(TaskState.Execution, context.recoveryData),
            updatedAt = Instant.now().toString(),
        )
        val remainingText = if (remainingAfterMove > 0) {
            "До Validation осталось шагов: $remainingAfterMove."
        } else {
            "Это последний execution-шаг перед Validation."
        }

        return TransitionResult.Allowed(
            context = nextContext,
            message = "Execution: шаг ${context.currentStepNumber} завершен, остаемся в Execution и переходим к шагу $nextStep: ${nextContext.currentStepDescription} $remainingText",
        )
    }

    val validationContext = context.copy(
        state = TaskState.Validation,
        currentStepNumber = 1,
        currentStepDescription = context.recoveryData["plan_step_3"] ?: "Проверить результат",
        expectedAction = expectedActionFor(TaskState.Validation, context.recoveryData),
        recoveryData = context.recoveryData + ("execution_completed" to "true"),
        updatedAt = Instant.now().toString(),
    )

    return TransitionResult.Allowed(
        context = validationContext,
        message = "Все execution-шаги завершены. Переход Execution -> Validation: ${validationContext.currentStepDescription}",
    )
}

private fun moveToDone(context: TaskContext): TransitionResult.Allowed {
    val doneContext = context.copy(
        state = TaskState.Done,
        paused = false,
        currentStepNumber = 1,
        currentStepDescription = "Задача завершена",
        expectedAction = "Действий не требуется.",
        recoveryData = context.recoveryData + ("validation_passed" to "true"),
        updatedAt = Instant.now().toString(),
    )

    return TransitionResult.Allowed(
        context = doneContext,
        message = "Validation завершена успешно. Переход Validation -> Done. Задача завершена.",
    )
}

private fun expectedActionFor(
    state: TaskState,
    recoveryData: Map<String, String> = emptyMap(),
): String {
    return when (state) {
        TaskState.Planning -> {
            if (recoveryData.isPlanApproved()) {
                "План утвержден. Вызовите /task next для перехода в Execution."
            } else {
                "Проверьте план и вызовите /task approve. /task next без утверждения будет отклонен."
            }
        }
        TaskState.Execution -> "Ассистент выполняет текущий шаг. Вызовите /task next для перехода к следующему шагу или проверке."
        TaskState.Validation -> "Проверьте результат. Вызовите /task next или /task done для завершения."
        TaskState.Done -> "Действий не требуется."
    }
}

private fun TaskContext.isPlanReady(): Boolean {
    return recoveryData["plan_ready"]?.toBooleanStrictOrNull() ?: recoveryData.containsKey("plan_step_1")
}

private fun TaskContext.isPlanApproved(): Boolean {
    return recoveryData.isPlanApproved()
}

private fun Map<String, String>.isPlanApproved(): Boolean {
    return this["plan_approved"]?.toBooleanStrictOrNull() ?: false
}

private fun inferredExecutionStepsTotal(context: TaskContext): Int {
    val configuredTotal = context.recoveryData["execution_steps_total"]?.toIntOrNull() ?: 0
    val explicitExecutionSteps = context.recoveryData.keys
        .mapNotNull { key -> Regex("""execution_step_(\d+)""").matchEntire(key)?.groupValues?.get(1)?.toIntOrNull() }
        .maxOrNull() ?: 0
    val planBasedExecutionSteps = if (context.recoveryData.containsKey("plan_step_2")) {
        2
    } else {
        1
    }

    return maxOf(configuredTotal, explicitExecutionSteps, planBasedExecutionSteps, 1)
}

private fun String.normalizedTaskText(): String {
    return lowercase()
        .replace('ё', 'е')
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private val PLAN_APPROVAL_MARKERS = setOf(
    "утверждаю",
    "утвердить план",
    "план утвержден",
    "одобряю",
    "одобрить план",
    "согласен с планом",
    "согласна с планом",
    "подтверждаю план",
    "approve plan",
    "plan approved",
)

private val PLAN_REJECTION_MARKERS = setOf(
    "не утверждаю",
    "не одобряю",
    "не согласен",
    "не согласна",
    "не подтверждаю",
    "отклоняю",
)

private val SKIP_STAGE_MARKERS = setOf(
    "пропусти",
    "пропустить",
    "сразу к",
    "сразу в",
    "сразу на",
    "без плана",
    "без планирования",
    "без валидации",
    "без проверки",
    "к финалу",
    "сразу финал",
    "сразу заверш",
    "завершай без",
    "закрывай без",
    "перейди к реализации",
    "переходи к реализации",
    "приступай сразу",
    "давай сразу",
    "не надо план",
    "не нужен план",
    "не нужна проверка",
    "не проверяй",
    "skip stage",
    "skip planning",
    "skip validation",
    "without plan",
    "without planning",
    "without validation",
)

private fun TaskContext.toAutoExecutionPrompt(): String? {
    if (paused || isDone) {
        return null
    }

    return when (state) {
        TaskState.Planning -> null
        TaskState.Execution -> """
            Выполни текущий шаг задачи.
            Задача: $description
            Stage: ${state.name}
            Шаг $currentStepNumber: $currentStepDescription
            Ожидаемое действие: $expectedAction
            Используй recoveryData как вводные и ограничения. Не пересказывай весь план заново.
            Если данных достаточно, дай конкретный результат текущего шага. Если данных не хватает, задай только самые нужные уточняющие вопросы.
        """.trimIndent()
        TaskState.Validation -> """
            Проверь результат текущей задачи.
            Задача: $description
            Stage: ${state.name}
            Шаг $currentStepNumber: $currentStepDescription
            Ожидаемое действие: $expectedAction
            Используй историю диалога и recoveryData. Дай краткую проверку, что готово, что требует исправления, и можно ли завершать задачу.
        """.trimIndent()
        TaskState.Done -> null
    }
}

private fun Map<String, String>.toJsonObject(): JSONObject {
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

/*
Демонстрационный сценарий Task State Machine:

Пользователь: /task start Разработать бюджет
Ассистент (Planning): предлагает план из трех шагов и ждет /task approve.
Пользователь: приступай сразу к реализации
Ассистент: отказывает, потому что Planning -> Execution возможен только после утверждения плана.
Пользователь: /task next
Ассистент: отказывает, потому что plan_approved=false.
Пользователь: /task approve
Ассистент: сохраняет plan_approved=true в WorkingMemory.current_task.
Пользователь: /task next
Ассистент: переходит в Execution и приступает к первому шагу.
Пользователь: давай без валидации, завершай
Ассистент: отказывает, потому что пропуск Validation запрещен.
Пользователь: /task pause
Ассистент: сохраняет paused=true, stage=Execution и текущий шаг.
Пользователь: /task resume
Ассистент: снимает paused и продолжает с Execution без повторного объяснения плана.
Пользователь: /task next
Ассистент: остается в Execution, переходит к следующему execution-шагу и сообщает остаток.
Пользователь: /task next
Ассистент: после всех execution-шагов переходит в Validation.
Пользователь: /task next
Ассистент: переводит задачу в Done.
Пользователь: /memory-status
Ассистент: показывает WorkingMemory.current_task со stage=Done.
*/

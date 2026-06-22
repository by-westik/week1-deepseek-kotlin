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
)

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
            expectedAction = "Проверьте план и вызовите /task next, чтобы перейти к выполнению.",
            recoveryData = mapOf(
                "plan_step_1" to plan[0],
                "plan_step_2" to plan[1],
                "plan_step_3" to plan[2],
                "execution_steps_total" to "1",
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
        if (context.paused) {
            return TaskTransition(
                context = context,
                message = "Задача на паузе. Используйте /task resume, чтобы продолжить с текущего места.",
            )
        }

        return when (context.state) {
            TaskState.Planning -> moveToExecution(context)
            TaskState.Execution -> moveWithinOrAfterExecution(context)
            TaskState.Validation -> moveToDone(context)
            TaskState.Done -> TaskTransition(
                context = context,
                message = "Задача уже завершена.",
            )
        }
    }

    fun pause(context: TaskContext): TaskTransition {
        if (context.state == TaskState.Done) {
            throw IllegalArgumentException("завершенную задачу нельзя поставить на паузу")
        }

        val pausedContext = context.copy(
            paused = true,
            expectedAction = "Задача приостановлена. Используйте /task resume для продолжения.",
            updatedAt = Instant.now().toString(),
        )

        return TaskTransition(
            context = pausedContext,
            message = "Задача приостановлена на stage ${context.state.name}, шаг ${context.currentStepNumber}.",
        )
    }

    fun resume(context: TaskContext): TaskTransition {
        if (!context.paused) {
            return TaskTransition(
                context = context,
                message = "Задача уже активна: ${context.state.name}, шаг ${context.currentStepNumber}.",
            )
        }

        val resumedContext = context.copy(
            paused = false,
            expectedAction = expectedActionFor(context.state),
            updatedAt = Instant.now().toString(),
        )

        return TaskTransition(
            context = resumedContext,
            message = "Возобновляю задачу: ${resumedContext.state.name}, шаг ${resumedContext.currentStepNumber}. Продолжаем с того же места.",
        )
    }

    fun done(context: TaskContext): TaskTransition {
        return when (context.state) {
            TaskState.Validation, TaskState.Done -> moveToDone(context.copy(paused = false))
            TaskState.Planning, TaskState.Execution -> throw IllegalArgumentException(
                "/task done допустима после Validation. Сейчас stage: ${context.state.name}",
            )
        }
    }

    fun status(context: TaskContext): String {
        return """
            Текущая задача:
            ${context.toDisplayText()}
        """.trimIndent()
    }

    private fun moveToExecution(context: TaskContext): TaskTransition {
        val nextContext = context.copy(
            state = TaskState.Execution,
            currentStepNumber = 1,
            currentStepDescription = context.recoveryData["plan_step_1"] ?: "Выполнить первый шаг задачи",
            expectedAction = expectedActionFor(TaskState.Execution),
            updatedAt = Instant.now().toString(),
        )

        return TaskTransition(
            context = nextContext,
            message = "Переход Planning -> Execution. Приступаю к шагу 1: ${nextContext.currentStepDescription}",
        )
    }

    private fun moveWithinOrAfterExecution(context: TaskContext): TaskTransition {
        val totalSteps = context.recoveryData["execution_steps_total"]?.toIntOrNull() ?: 1

        if (context.currentStepNumber < totalSteps) {
            val nextStep = context.currentStepNumber + 1
            val nextContext = context.copy(
                currentStepNumber = nextStep,
                currentStepDescription = context.recoveryData["execution_step_$nextStep"]
                    ?: "Выполнить шаг $nextStep",
                expectedAction = expectedActionFor(TaskState.Execution),
                updatedAt = Instant.now().toString(),
            )

            return TaskTransition(
                context = nextContext,
                message = "Execution: перехожу к шагу $nextStep: ${nextContext.currentStepDescription}",
            )
        }

        val validationContext = context.copy(
            state = TaskState.Validation,
            currentStepNumber = 1,
            currentStepDescription = context.recoveryData["plan_step_3"] ?: "Проверить результат",
            expectedAction = expectedActionFor(TaskState.Validation),
            updatedAt = Instant.now().toString(),
        )

        return TaskTransition(
            context = validationContext,
            message = "Execution завершен. Переход Execution -> Validation: ${validationContext.currentStepDescription}",
        )
    }

    private fun moveToDone(context: TaskContext): TaskTransition {
        val doneContext = context.copy(
            state = TaskState.Done,
            paused = false,
            currentStepNumber = 1,
            currentStepDescription = "Задача завершена",
            expectedAction = "Действий не требуется.",
            updatedAt = Instant.now().toString(),
        )

        return TaskTransition(
            context = doneContext,
            message = "Validation завершена. Переход Validation -> Done. Задача завершена.",
        )
    }

    private fun expectedActionFor(state: TaskState): String {
        return when (state) {
            TaskState.Planning -> "Проверьте план и вызовите /task next."
            TaskState.Execution -> "Ассистент выполняет текущий шаг. Вызовите /task next для перехода к проверке."
            TaskState.Validation -> "Проверьте результат. Вызовите /task next или /task done для завершения."
            TaskState.Done -> "Действий не требуется."
        }
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

Пользователь: /task start Написать статью об автоматизации
Ассистент (Planning): предлагает план из трех шагов.
Пользователь: /task next
Ассистент: переходит в Execution и приступает к первому шагу.
Пользователь: /task pause
Ассистент: сохраняет paused=true, stage=Execution и текущий шаг.
Пользователь: Какая сейчас погода?
Ассистент: отвечает на обычный вопрос, не трогая paused-задачу.
Пользователь: /task resume
Ассистент: снимает paused и продолжает с Execution без повторного объяснения плана.
Пользователь: /task next
Ассистент: завершает шаг и переходит в Validation.
Пользователь: /task next
Ассистент: переводит задачу в Done.
Пользователь: /memory-status
Ассистент: показывает WorkingMemory.current_task со stage=Done.
*/

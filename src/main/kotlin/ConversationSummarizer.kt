interface ConversationSummarizer {
    fun summarize(existingSummary: String, messages: List<ChatMessage>): String
}

class NoOpConversationSummarizer : ConversationSummarizer {
    override fun summarize(existingSummary: String, messages: List<ChatMessage>): String {
        return existingSummary
    }
}

class DeepSeekConversationSummarizer(
    private val client: DeepSeekClient,
    private val settings: ModelSettings,
) : ConversationSummarizer {
    override fun summarize(existingSummary: String, messages: List<ChatMessage>): String {
        if (messages.isEmpty()) {
            return existingSummary
        }

        val summaryRequest = buildList {
            add(
                ChatMessage(
                    role = "system",
                    content = """
                        Ты сжимаешь историю диалога для памяти агента.
                        Твоя задача: написать короткое, но полезное summary, которое заменит старые сообщения.
                        Summary будет скрыто от пользователя и будет подставляться агенту как память.

                        Правила:
                        - сохрани важные факты, предпочтения, цели, решения, имена, числа, кодовые слова;
                        - сохрани незавершенные задачи и обещания агента;
                        - убери повторы, шум, приветствия и лишние формулировки;
                        - не добавляй фактов, которых нет в истории;
                        - пиши по-русски;
                        - будь краткой: 10-20 пунктов максимум.
                    """.trimIndent(),
                ),
            )

            if (existingSummary.isNotBlank()) {
                add(
                    ChatMessage(
                        role = "user",
                        content = """
                            Уже сохраненное summary:
                            $existingSummary
                        """.trimIndent(),
                    ),
                )
            }

            add(
                ChatMessage(
                    role = "user",
                    content = """
                        Сожми следующие сообщения в обновленное summary.
                        Верни только итоговое summary без Markdown-заголовков и без пояснений.

                        ${messages.toTranscript()}
                    """.trimIndent(),
                ),
            )
        }

        return client.complete(summaryRequest, summarySettings()).answer.trim()
    }

    private fun summarySettings(): ModelSettings {
        return settings.copy(
            maxTokens = SUMMARY_MAX_TOKENS,
            temperature = SUMMARY_TEMPERATURE,
            stop = null,
            thinking = "disabled",
        )
    }

    private fun List<ChatMessage>.toTranscript(): String {
        return joinToString("\n") { message ->
            "${message.role}: ${message.content}"
        }
    }

    private companion object {
        const val SUMMARY_MAX_TOKENS = 700
        const val SUMMARY_TEMPERATURE = 0.2
    }
}

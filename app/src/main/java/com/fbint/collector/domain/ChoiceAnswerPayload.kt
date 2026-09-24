package com.fbint.collector.domain

import com.fbint.collector.data.remote.dto.SurveyDto

/**
 * Display labels are trimmed/HTML-stripped and may come from a previously selected language.
 * The server validates exact labels in the submitted language. Resolve only unambiguous
 * configured choices; never rewrite free text or guess which option an unknown answer meant.
 * This transforms the outgoing payload only, keeping the original queued answers intact.
 */
internal fun choiceAnswerPayload(
    data: Map<String, Any?>,
    survey: SurveyDto?,
    language: String?,
): Map<String, Any?> {
    if (survey == null) return data
    val defaultCode = survey.languages.firstOrNull { it.default }?.language?.code
    val languageKey = language ?: defaultCode ?: "default"
    val questions = survey.questions.associateBy { it.id }
    return data.mapValues { (id, value) ->
        val question = questions[id]
        if (question?.type != QType.CHOICE_SINGLE && question?.type != QType.CHOICE_MULTI) return@mapValues value
        val choices = question?.choices.orEmpty().filter { it.id != "other" }
        fun resolve(answer: Any?): Any? {
            if (answer !is String || answer.isBlank()) return answer
            val matches = choices.filter { choice ->
                answer == choice.id || choice.label.orEmpty().any { (key, raw) ->
                    answer == raw || answer == choice.label.localized(key)
                }
            }
            val choice = matches.singleOrNull() ?: return answer
            return choice.label?.get(languageKey)?.takeIf { it.isNotBlank() }
                ?: choice.label?.get("default")?.takeIf { it.isNotBlank() }
                ?: answer
        }
        when (value) {
            is String -> resolve(value)
            is List<*> -> value.map { resolve(it) }
            else -> value
        }
    }
}

package com.fbint.collector.domain

import com.fbint.collector.data.remote.dto.*
import org.junit.Assert.*
import org.junit.Test

class ChoiceRequiredTest {
    private val question = QuestionDto("fac03r_e", QType.CHOICE_MULTI, required = true,
        choices = listOf(ChoiceDto("toilets", mapOf("default" to "Toilets", "ar-SA" to "المراحيض")),
            ChoiceDto("other", mapOf("default" to "Other"))))
    @Test fun selectedOtherRequiresTextEvenWithAnotherSelectedChoice() {
        for (value in listOf(listOf(""), listOf("Toilets", ""), listOf("", "Toilets"), listOf("Toilets", "", ""), listOf("Toilets", " "))) {
            assertFalse(value.toString(), question.isAnswerValid(value))
            assertFalse(value.toString(), question.copy(required = false).isAnswerValid(value))
        }
    }
    @Test fun actualChoiceAndSpecifiedOtherAreValid() {
        assertTrue(question.isAnswerValid(listOf("المراحيض")))
        assertTrue(question.isAnswerValid(listOf("Toilets", "", "Broken door")))
        assertTrue(question.isAnswerValid(listOf("Broken door")))
    }
    @Test fun optionalUnansweredQuestionCanStillBeSkipped() {
        assertTrue(question.copy(required = false).isAnswerValid(emptyList<String>()))
        assertTrue(question.copy(required = false).isAnswerValid(null))
        assertFalse(question.isAnswerValid(emptyList<String>()))
    }
    @Test fun blankSingleChoiceOtherCannotBeSubmitted() {
        assertFalse(question.copy(type = QType.CHOICE_SINGLE, required = false).isAnswerValid(" "))
        assertTrue(question.copy(type = QType.CHOICE_SINGLE, required = false).isAnswerValid(null))
    }
}

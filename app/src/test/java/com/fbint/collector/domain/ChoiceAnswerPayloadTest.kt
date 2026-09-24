package com.fbint.collector.domain

import com.fbint.collector.data.remote.dto.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ChoiceAnswerPayloadTest {
    private val choices = listOf(
        ChoiceDto("public", mapOf("default" to "Public transport", "ar-SA" to "النقل العام")),
        ChoiceDto("private", mapOf("default" to "Private car", "ar-SA" to "سيارة خاصة")),
    )
    private fun survey(options: List<ChoiceDto> = choices, type: String = QType.CHOICE_SINGLE) = SurveyDto(
        id = "survey", name = "Survey", environmentId = "env",
        languages = listOf(LanguageDto(LanguageCodeDto(code = "en-US"), default = true)),
        questions = listOf(QuestionDto(id = "adm04_e", type = type, choices = options)),
    )
    private fun payload(answer: Any?, language: String = "ar-SA", definition: SurveyDto = survey()) =
        choiceAnswerPayload(mapOf("adm04_e" to answer), definition, language)["adm04_e"]

    @Test fun languageSwitchResolvesSavedEnglishChoiceToArabic() {
        assertEquals("النقل العام", payload("Public transport"))
    }
    @Test fun defaultLanguageCodeUsesDefaultLabel() {
        assertEquals("Private car", payload("سيارة خاصة", "en-US"))
    }
    @Test fun displayFormattingIsNotUsedAsWireValue() {
        val options = listOf(ChoiceDto("public", mapOf("default" to "<p>Bus &amp; train</p>")))
        assertEquals("<p>Bus &amp; train</p>", payload("Bus & train", "en-US", survey(options)))
    }
    @Test fun unknownOrAmbiguousAnswersAreNeverGuessed() {
        assertEquals("Old deleted option", payload("Old deleted option"))
        val options = choices + ChoiceDto("another", mapOf("default" to "Public transport", "ar-SA" to "different"))
        assertEquals("Public transport", payload("Public transport", definition = survey(options)))
    }
    @Test fun multiChoicePreservesOtherSentinelAndFreeText() {
        val options = choices + ChoiceDto("other", mapOf("default" to "Other"))
        assertEquals(listOf("سيارة خاصة", "", "Walking"),
            payload(listOf("Private car", "", "Walking"), definition = survey(options, QType.CHOICE_MULTI)))
    }
    @Test fun originalMapAndNonChoiceAnswersAreUnchanged() {
        val data = mapOf("adm04_e" to "Private car", "comment" to "Private car")
        val result = choiceAnswerPayload(data, survey(), "ar-SA")
        assertEquals("Private car", data["adm04_e"])
        assertEquals("Private car", result["comment"])
        assertEquals("سيارة خاصة", result["adm04_e"])
        assertEquals(data, choiceAnswerPayload(data, null, "ar-SA"))
    }
}

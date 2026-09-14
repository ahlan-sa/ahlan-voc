package com.fbint.collector.domain

import com.fbint.collector.data.remote.dto.*
import org.junit.Assert.*
import org.junit.Test

class LanguageOptionsTest {
    @Test fun emptyFormbricksAliasesProduceReadableNamesAndPreserveTranslationKeys() {
        val survey = SurveyDto("survey", "Example", environmentId = "env", languages = listOf(
            LanguageDto(LanguageCodeDto(code = "ar-SA", alias = ""), enabled = true),
            LanguageDto(LanguageCodeDto(code = "en-GB", alias = "  "), default = true)))
        val options = survey.languageOptions()
        assertEquals(listOf("default", "ar-SA"), options.map { it.lookupKey })
        assertTrue(options[0].displayName.contains("English"))
        assertTrue(options[1].displayName.contains("العربية"))
        assertTrue(options[1].isRtl)
        assertFalse(options[0].isRtl)
    }
    @Test fun customAliasesRemainVisibleAndDisabledLanguagesStayHidden() {
        val survey = SurveyDto("survey", "Example", environmentId = "env", languages = listOf(
            LanguageDto(LanguageCodeDto(code = "en-GB", alias = " Interview English "), default = true),
            LanguageDto(LanguageCodeDto(code = "fr"), enabled = false)))
        assertEquals(listOf("Interview English"), survey.languageOptions().map { it.displayName })
    }
}

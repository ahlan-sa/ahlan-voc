package com.fbint.collector.domain

import com.fbint.collector.data.remote.dto.SurveyDto
import com.fbint.collector.data.remote.dto.VariableDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyVisibilityTest {
    private fun survey(value: Any?, type: String = "text", status: String = "inProgress") =
        SurveyDto("survey", "Example", status = status, environmentId = "env",
            variables = listOf(VariableDto("setting", SHOW_IN_APP_VARIABLE, type, value)))

    @Test
    fun onlyAnExplicitYesEnablesAnActiveSurvey() {
        for (value in listOf("YES", "yes", " Yes ")) assertTrue(survey(value).isVisibleInApp())
        for (value in listOf("NO", "no", "", "true", "maybe", null, 1, true)) {
            assertFalse(survey(value).isVisibleInApp())
        }
        assertFalse(survey("YES", type = "number").isVisibleInApp())
    }

    @Test
    fun draftsPausedAndCompletedSurveysRemainHiddenEvenWithYes() {
        for (status in listOf("draft", "paused", "completed")) {
            assertFalse(survey("YES", status = status).isVisibleInApp())
        }
    }

    @Test
    fun missingOrAmbiguousSettingDoesNotOptIn() {
        val yes = survey("YES")
        assertFalse(yes.copy(variables = emptyList()).isVisibleInApp())
        assertFalse(yes.copy(variables = yes.variables + yes.variables).isVisibleInApp())
    }
}

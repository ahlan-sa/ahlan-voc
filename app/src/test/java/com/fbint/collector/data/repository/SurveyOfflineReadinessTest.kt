package com.fbint.collector.data.repository

import android.app.Application
import com.fbint.collector.data.local.SurveyDao
import com.fbint.collector.data.local.entity.SurveyEntity
import com.fbint.collector.data.remote.FormbricksApiFactory
import com.fbint.collector.data.remote.dto.*
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SurveyOfflineReadinessTest {
    private val moshi = Moshi.Builder().build()
    private val repository = SurveyRepository(mock(FormbricksApiFactory::class.java), mock(SurveyDao::class.java),
        mock(ConfigRepository::class.java), RuntimeEnvironment.getApplication(), moshi)
    private fun survey() = SurveyDto("survey", "Example", environmentId = "env",
        questions = listOf(QuestionDto("question", "openText")),
        hiddenFields = HiddenFieldsDto(true, AUTO_STAMPED_HIDDEN_FIELD_IDS.toList()))
    private fun entity(survey: SurveyDto) = SurveyEntity(survey.id, survey.name, "inProgress", "link", "env", null, 1,
        moshi.adapter(SurveyDto::class.java).toJson(survey))

    @Test fun completeTextSurveyIsReadyOffline() {
        assertTrue(repository.isOfflineReady(entity(survey())))
    }
    @Test fun incompleteDefinitionIsNotReadyOffline() {
        assertFalse(repository.isOfflineReady(entity(survey().copy(questions = emptyList()))))
        assertFalse(repository.isOfflineReady(entity(survey().copy(hiddenFields = null))))
        assertFalse(repository.isOfflineReady(entity(survey()).copy(json = "broken")))
    }
    @Test fun missingImageOrStreamingVideoRequiresInternet() {
        assertFalse(repository.isOfflineReady(entity(survey().copy(questions = listOf(
            QuestionDto("question", "pictureSelection", imageUrl = "https://example.com/uncached-readiness-image.png"))))))
        assertFalse(repository.isOfflineReady(entity(survey().copy(questions = listOf(
            QuestionDto("question", "openText", videoUrl = "https://example.com/video.mp4"))))))
    }
}

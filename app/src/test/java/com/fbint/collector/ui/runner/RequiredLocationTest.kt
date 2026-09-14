package com.fbint.collector.ui.runner

import android.app.Application
import android.os.Looper
import com.fbint.collector.data.LocationProvider
import com.fbint.collector.data.NetworkMonitor
import com.fbint.collector.data.local.ResponseQueueDao
import com.fbint.collector.data.remote.dto.*
import com.fbint.collector.data.repository.*
import com.fbint.collector.sync.SyncScheduler
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RequiredLocationTest {
    @Test fun noFixKeepsAnswersAndDoesNotQueueResponse() = runBlocking {
        val surveys = mock(SurveyRepository::class.java)
        val responses = mock(ResponseRepository::class.java)
        val location = mock(LocationProvider::class.java)
        val network = mock(NetworkMonitor::class.java)
        val config = mock(ConfigRepository::class.java)
        `when`(network.observeOnline()).thenReturn(flowOf(true))
        `when`(surveys.loadFromCache("survey")).thenReturn(SurveyDto(
            "survey", "Example", environmentId = "env", status = "inProgress",
            variables = listOf(VariableDto("visible", "show_in_app", "text", "YES")),
            questions = listOf(QuestionDto("question", "openText", required = false))))
        `when`(location.current(15_000)).thenReturn(null)
        val vm = SurveyRunnerViewModel("survey", surveys, responses,
            mock(FileQueueRepository::class.java), config, mock(SyncScheduler::class.java),
            location, network, mock(ResponseQueueDao::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RunnerStage.Question("question"), vm.state.value.stage)
        vm.setAnswer("question", "Keep this answer")
        vm.next()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RunnerStage.Question("question"), vm.state.value.stage)
        assertTrue(vm.state.value.validationError.orEmpty().contains("location"))
        assertEquals("Keep this answer", vm.state.value.answers["question"])
        verifyNoInteractions(responses)
    }
}

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
class RunnerRecoveryTest {
    @Test fun retryRollsBackCalculationBeforeSuccessfulSubmission() = runBlocking {
        val surveys = mock(SurveyRepository::class.java)
        val responses = mock(ResponseRepository::class.java)
        val location = mock(LocationProvider::class.java)
        val network = mock(NetworkMonitor::class.java)
        val config = mock(ConfigRepository::class.java)
        `when`(network.observeOnline()).thenReturn(flowOf(true))
        `when`(surveys.loadFromCache("survey")).thenReturn(SurveyDto(
            "survey", "Example", environmentId = "env", status = "inProgress",
            variables = listOf(VariableDto("visible", "show_in_app", "text", "YES"), VariableDto("counter", "counter", "number", 0)),
            hiddenFields = HiddenFieldsDto(true, AUTO_STAMPED_HIDDEN_FIELD_IDS.toList()),
            questions = listOf(QuestionDto("question", "openText", required = false, logic = listOf(LogicRuleDto("rule", ConditionGroupDto(), listOf(LogicActionDto(objective = "calculate", variableId = "counter", operator = "add", value = OperandDto("static", 1)))))))))
        `when`(location.current(15_000)).thenReturn(null)
        `when`(config.deviceInstallId()).thenReturn("device")
        `when`(config.surveyorId()).thenReturn("surveyor")
        val dao = mock(ResponseQueueDao::class.java)
        `when`(dao.observeById(anyString())).thenReturn(flowOf(null))
        val vm = SurveyRunnerViewModel("survey", surveys, responses,
            mock(FileQueueRepository::class.java), config, mock(SyncScheduler::class.java),
            location, network, dao)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RunnerStage.Question("question"), vm.state.value.stage)
        vm.setAnswer("question", "Keep this answer")
        vm.next()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RunnerStage.Question("question"), vm.state.value.stage)
        assertTrue(vm.state.value.validationError.orEmpty().contains("location"))
        assertEquals("Keep this answer", vm.state.value.answers["question"])
        vm.next()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0.0, (vm.state.value.variables["counter"] as Number).toDouble(), 0.0)
        verifyNoInteractions(responses)
        val fix = android.location.Location("gps").apply { latitude = 24.7; longitude = 46.6; accuracy = 5f }
        `when`(location.current(15_000)).thenReturn(fix)
        vm.next()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RunnerStage.Done, vm.state.value.stage)
        assertEquals(1.0, vm.state.value.variables["counter"])

    }
    @Test fun failedDownloadMustReturnNull() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404))
            val checker = com.fbint.collector.data.UpdateChecker(
                org.robolectric.RuntimeEnvironment.getApplication(), okhttp3.OkHttpClient(),
                com.squareup.moshi.Moshi.Builder().build())
            assertNull(checker.download(server.url("/missing.apk").toString()) {})
        } finally { server.shutdown() }
    }
    @Test fun recreatingRunnerRestoresDraftAndOriginalCollector() = runBlocking {
        val surveys = mock(SurveyRepository::class.java)
        val config = mock(ConfigRepository::class.java)
        val network = mock(NetworkMonitor::class.java)
        `when`(network.observeOnline()).thenReturn(flowOf(false))
        `when`(config.surveyorId()).thenReturn("original-collector")
        `when`(surveys.loadFromCache("survey")).thenReturn(SurveyDto("survey", "Example", environmentId = "env",
            hiddenFields = HiddenFieldsDto(true, AUTO_STAMPED_HIDDEN_FIELD_IDS.toList()),
            status = "inProgress", variables = listOf(VariableDto("visible", "show_in_app", "text", "YES")),
            questions = listOf(QuestionDto("question", "openText"))))
        var saved: String? = null
        `when`(config.loadDraft("survey")).thenAnswer { saved }
        doAnswer { invocation -> saved = invocation.getArgument(1); null }.`when`(config).saveDraft(anyString(), anyString())
        fun create() = SurveyRunnerViewModel("survey", surveys, mock(ResponseRepository::class.java),
            mock(FileQueueRepository::class.java), config, mock(SyncScheduler::class.java),
            mock(LocationProvider::class.java), network, mock(ResponseQueueDao::class.java))
        val first = create()
        shadowOf(Looper.getMainLooper()).idle()
        first.setAnswer("question", "Saved interview")
        first.setLanguage("ar")
        val before = saved!!
        `when`(config.surveyorId()).thenReturn("new-collector")
        val restored = create()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(restored.state.value.restoredDraft)
        assertEquals("Saved interview", restored.state.value.answers["question"])
        assertEquals("ar", restored.state.value.language)
        restored.setAnswer("question", "Updated interview")
        assertTrue(saved!!.contains("original-collector"))
        val adapter = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build().adapter(RunnerDraft::class.java)
        assertEquals(adapter.fromJson(before)!!.submissionId, adapter.fromJson(saved!!)!!.submissionId)
    }
}

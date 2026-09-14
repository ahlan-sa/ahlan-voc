package com.fbint.collector.ui.runner

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fbint.collector.data.remote.dto.EndingDto
import com.fbint.collector.data.remote.dto.QuestionDto
import com.fbint.collector.data.remote.dto.SurveyDto
import com.fbint.collector.BuildConfig
import com.fbint.collector.data.LocationProvider
import com.fbint.collector.data.NetworkMonitor
import com.fbint.collector.data.local.ResponseQueueDao
import com.fbint.collector.data.repository.ConfigRepository
import com.fbint.collector.data.repository.FileQueueRepository
import com.fbint.collector.data.repository.Instrumentation
import com.fbint.collector.data.repository.ResponseRepository
import com.fbint.collector.data.repository.SurveyRepository
import com.fbint.collector.data.repository.toCandidateStamps
import kotlinx.coroutines.flow.first
import java.time.Instant
import com.fbint.collector.domain.LogicContext
import com.fbint.collector.domain.LanguageOption
import com.fbint.collector.domain.LogicEngine
import com.fbint.collector.domain.NextStep
import com.fbint.collector.domain.defaultLanguageCode
import com.fbint.collector.domain.initialAnswer
import com.fbint.collector.domain.languageOptions
import com.fbint.collector.domain.isAnswerValid
import com.fbint.collector.domain.isVisibleInApp
import com.fbint.collector.sync.SyncScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface RunnerStage {
    data object Loading : RunnerStage
    data object Welcome : RunnerStage
    data class Question(val questionId: String) : RunnerStage
    data object Submitting : RunnerStage
    data class Ending(val endingId: String) : RunnerStage
    data object Done : RunnerStage
    data class Error(val message: String) : RunnerStage
}

data class RunnerState(
    val survey: SurveyDto? = null,
    val stage: RunnerStage = RunnerStage.Loading,
    val answers: Map<String, Any?> = emptyMap(),
    val variables: Map<String, Any?> = emptyMap(),
    val language: String = "default",
    val availableLanguages: List<LanguageOption> = emptyList(),
    val showLanguageSwitch: Boolean = false,
    val validationError: String? = null,
)

@HiltViewModel(assistedFactory = SurveyRunnerViewModel.Factory::class)
class SurveyRunnerViewModel @AssistedInject constructor(
    @Assisted private val surveyId: String,
    private val surveyRepo: SurveyRepository,
    private val responseRepo: ResponseRepository,
    private val fileRepo: FileQueueRepository,
    private val config: ConfigRepository,
    private val sync: SyncScheduler,
    private val locationProvider: LocationProvider,
    private val networkMonitor: NetworkMonitor,
    private val responseQueueDao: ResponseQueueDao,
) : ViewModel(), FileUploadDelegate {

    private var startedAtMs: Long = 0L
    private var startedElapsedMs: Long = 0L
    private var capturedSurveyorId: String? = null

    private val engine = LogicEngine()
    private val backStack = ArrayDeque<RunnerStage>()
    private val ctx = LogicContext()

    private val _state = MutableStateFlow(RunnerState())
    val state: StateFlow<RunnerState> = _state.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val survey = surveyRepo.loadFromCache(surveyId)
            if (survey == null) {
                _state.update { it.copy(stage = RunnerStage.Error("Survey not in cache. Refresh while online.")) }
                return@launch
            }
            if (!survey.isVisibleInApp()) {
                _state.update { it.copy(stage = RunnerStage.Error("This survey is not enabled for collection in the app. Refresh the survey list.")) }
                return@launch
            }
            val defaults = survey.variables.associate { v ->
                v.id to (v.value ?: if (v.type == "number") 0 else "")
            }
            ctx.variables.clear(); ctx.variables.putAll(defaults)
            ctx.answers.clear()
            ctx.hiddenFields.clear()
            // Pre-load any hidden fields the surveyor entered before starting this survey.
            val hiddenFieldIds = survey.hiddenFields?.fieldIds.orEmpty()
                .filter { it !in com.fbint.collector.data.repository.AUTO_STAMPED_HIDDEN_FIELD_IDS }
            if (hiddenFieldIds.isNotEmpty()) {
                ctx.hiddenFields.putAll(config.loadHiddenFields(survey.id, hiddenFieldIds))
            }
            startedAtMs = System.currentTimeMillis()
            startedElapsedMs = android.os.SystemClock.elapsedRealtime()
            capturedSurveyorId = config.surveyorId()

            val lang = survey.defaultLanguageCode()
            val available = survey.languageOptions()
            val firstStage = when {
                survey.welcomeCard?.enabled == true -> RunnerStage.Welcome
                survey.questions.isEmpty() -> firstEndingOrDone(survey)
                else -> RunnerStage.Question(survey.questions.first().id)
            }
            _state.update {
                it.copy(
                    survey = survey,
                    stage = firstStage,
                    answers = emptyMap(),
                    variables = defaults,
                    language = lang,
                    availableLanguages = available,
                    // Show the menu whenever multiple languages exist, regardless of the
                    // showLanguageSwitch survey-level flag (often null on real surveys, and
                    // surveyors in the field need to switch languages per respondent).
                    showLanguageSwitch = available.size > 1,
                )
            }
        }
    }

    fun setLanguage(code: String) {
        if (code == _state.value.language) return
        _state.update { it.copy(language = code) }
    }

    fun startFromWelcome() {
        val survey = _state.value.survey ?: return
        val first = survey.questions.firstOrNull()
        val next = if (first != null) RunnerStage.Question(first.id) else firstEndingOrDone(survey)
        backStack.addLast(RunnerStage.Welcome)
        _state.update { it.copy(stage = next, validationError = null) }
    }

    fun setAnswer(questionId: String, value: Any?) {
        ctx.answers[questionId] = value
        _state.update { it.copy(answers = it.answers + (questionId to value), validationError = null) }
    }

    fun next() {
        val s = _state.value
        val survey = s.survey ?: return
        val current = (s.stage as? RunnerStage.Question)?.questionId?.let { id ->
            survey.questions.firstOrNull { it.id == id }
        } ?: return
        val answer = s.answers[current.id]
        if (!current.isAnswerValid(answer)) {
            _state.update { it.copy(validationError = "Required") }
            return
        }
        backStack.addLast(s.stage)
        val nextStep = engine.nextStep(current, survey, ctx)
        val newStage = when (nextStep) {
            is NextStep.Question -> RunnerStage.Question(nextStep.id)
            is NextStep.Ending -> RunnerStage.Ending(nextStep.id)
            NextStep.Done -> RunnerStage.Done
        }
        _state.update {
            it.copy(stage = newStage, validationError = null, variables = ctx.variables.toMap())
        }
        if (newStage is RunnerStage.Ending || newStage is RunnerStage.Done) {
            submit(if (newStage is RunnerStage.Ending) newStage.endingId else null)
        }
    }

    fun back() {
        if (backStack.isEmpty()) return
        val previous = backStack.removeLast()
        _state.update { it.copy(stage = previous, validationError = null) }
    }

    private fun submit(endingId: String?) {
        val survey = _state.value.survey ?: return
        _state.update { it.copy(stage = RunnerStage.Submitting) }
        viewModelScope.launch {
            try {
                val instrumentation = buildInstrumentation()
                val candidates = instrumentation.toCandidateStamps(
                    surveyorId = capturedSurveyorId,
                    deviceInstallId = config.deviceInstallId(),
                    appVersion = BuildConfig.VERSION_NAME,
                )
                responseRepo.enqueue(
                    surveyId = survey.id,
                    environmentId = survey.environmentId,
                    data = ctx.answers.toMap().filterValues { it != null },
                    finished = true,
                    // Translate our magic "default" lookup key back to the survey's actual
                    // default language code (e.g. "en-GB"). Formbricks rejects "default" as
                    // an unknown language → HTTP 400 on submit.
                    language = resolveServerLanguageCode(),
                    variables = ctx.variables.toMap(),
                    hiddenFields = ctx.hiddenFields.toMap(),
                    autoStampCandidates = candidates,
                    allowedHiddenFieldIds = survey.hiddenFields?.fieldIds.orEmpty().toSet(),
                )
                sync.requestImmediateSync()
                val finalStage = endingId?.let { RunnerStage.Ending(it) } ?: RunnerStage.Done
                _state.update { it.copy(stage = finalStage) }
            } catch (t: Throwable) {
                _state.update { it.copy(stage = RunnerStage.Error(t.message ?: "Failed to save response")) }
            }
        }
    }

    /**
     * Convert our internal language identifier into something the Formbricks server accepts.
     * The runner uses the magic key `"default"` for i18n lookup against the headline maps, but
     * the response endpoint expects an actual language code (e.g. `en-GB`) or null.
     */
    private fun resolveServerLanguageCode(): String? {
        val s = _state.value
        val survey = s.survey ?: return null
        if (s.language != "default") return s.language
        val defaultLang = survey.languages.firstOrNull { it.default }?.language
        return defaultLang?.code  // null if survey has no declared languages
    }

    private suspend fun buildInstrumentation(): Instrumentation {
        val now = System.currentTimeMillis()
        val elapsedSeconds = ((android.os.SystemClock.elapsedRealtime() - startedElapsedMs) / 1000L).coerceAtLeast(0)
        val started = if (startedAtMs > 0) startedAtMs else now
        val online = runCatching { networkMonitor.observeOnline().first() }.getOrDefault(false)
        val location = runCatching { locationProvider.current() }.getOrNull()
        val pace = runCatching {
            val sid = config.surveyorId() ?: return@runCatching 0
            val startOfDay = startOfTodayMs()
            responseQueueDao.countSince(sid, startOfDay)
        }.getOrDefault(0)
        return Instrumentation(
            startedAtIso = Instant.ofEpochMilli(started).toString(),
            submittedAtIso = Instant.ofEpochMilli(now).toString(),
            timeToCompleteSeconds = elapsedSeconds,
            surveyorPaceToday = pace,
            languageUsed = _state.value.language,
            isOfflineCapture = !online,
            location = location,
        )
    }

    private fun startOfTodayMs(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun reset() {
        backStack.clear()
        load()
    }

    fun currentQuestion(): QuestionDto? {
        val s = _state.value
        val survey = s.survey ?: return null
        val stage = s.stage as? RunnerStage.Question ?: return null
        return survey.questions.firstOrNull { it.id == stage.questionId }
    }

    fun currentEnding(): EndingDto? {
        val s = _state.value
        val survey = s.survey ?: return null
        val stage = s.stage as? RunnerStage.Ending ?: return null
        return survey.endings.firstOrNull { it.id == stage.endingId }
    }

    fun questionInitialAnswer(question: QuestionDto): Any? =
        _state.value.answers[question.id] ?: question.initialAnswer()

    /** FileUploadDelegate implementation — bridges composables to the file repository. */
    override suspend fun ingestFile(uri: Uri, questionId: String, suggestedName: String?): String {
        val survey = _state.value.survey ?: error("Survey not loaded")
        return fileRepo.ingestPickedFile(
            sourceUri = uri,
            surveyId = survey.id,
            questionId = questionId,
            environmentId = survey.environmentId,
            suggestedName = suggestedName,
        )
    }

    private fun firstEndingOrDone(survey: SurveyDto): RunnerStage =
        survey.endings.firstOrNull()?.let { RunnerStage.Ending(it.id) } ?: RunnerStage.Done

    @AssistedFactory
    interface Factory {
        fun create(surveyId: String): SurveyRunnerViewModel
    }
}

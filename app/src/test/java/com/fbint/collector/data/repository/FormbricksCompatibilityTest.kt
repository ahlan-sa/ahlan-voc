package com.fbint.collector.data.repository

import android.app.Application
import androidx.room.Room
import com.fbint.collector.data.local.AppDatabase
import com.fbint.collector.data.local.entity.QueuedFileEntity
import com.fbint.collector.data.remote.FormbricksApiFactory
import com.fbint.collector.data.remote.dto.*
import com.fbint.collector.domain.*
import com.fbint.collector.ui.setup.SetupConfig
import com.fbint.collector.ui.setup.SetupConfigCodec
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FormbricksCompatibilityTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var config: ConfigRepository
    private lateinit var surveys: SurveyRepository
    private lateinit var files: FileQueueRepository
    private lateinit var responses: ResponseRepository
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        server = MockWebServer().apply { start() }
        config = Mockito.mock(ConfigRepository::class.java)
        Mockito.`when`(config.baseUrl()).thenReturn(server.url("/").toString())
        Mockito.`when`(config.apiKey()).thenReturn("test-key")
        Mockito.`when`(config.apiKeyKnownReadOnly()).thenReturn(true)
        Mockito.`when`(config.surveyorId()).thenReturn("collector-17")
        val client = OkHttpClient()
        val factory = FormbricksApiFactory(client, moshi)
        surveys = SurveyRepository(factory, db.surveyDao(), config, context, moshi)
        files = FileQueueRepository(context, db.queuedFileDao(), factory, config, client)
        responses = ResponseRepository(db.responseQueueDao(), factory, config, files, surveys, moshi)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun workspaceAndLegacyIdsResolveToTheSameConnection() {
        val me = moshi.adapter(MeDto::class.java).fromJson(v5Me)!!
        val workspace = me.resolveWorkspace(" workspace ")
        assertEquals("workspace", workspace.workspaceId)
        assertEquals("legacy-env", workspace.environmentId)
        assertEquals(workspace, me.resolveWorkspace("legacy-env"))
        assertThrows(IllegalArgumentException::class.java) { me.resolveWorkspace("unrelated") }
    }

    @Test
    fun preV5AndNewV5WorkspacesRemainSupported() {
        val old = MeDto("old-env", ProjectDto("old-project", "Old project"))
        assertEquals("old-env", old.resolveWorkspace("old-project").environmentId)
        assertNull(old.resolveWorkspace("old-env").workspaceId)
        val new = MeDto("new-workspace", workspace = ProjectDto("new-workspace", "New"))
        assertEquals("new-workspace", new.resolveWorkspace("new-workspace").environmentId)
    }

    @Test
    fun oldAndNewSetupQrsRoundTripWithoutLosingLegacyId() {
        val old = """{"baseUrl":"https://example.test","apiKey":"test-key","environmentId":"legacy-env","projectName":"Example"}"""
        assertNull(SetupConfigCodec.decode(old)!!.workspaceId)
        val new = SetupConfig("https://example.test", "test-key", "legacy-env", "Example", "workspace")
        assertEquals(new, SetupConfigCodec.decode(SetupConfigCodec.encode(new)))
        // Older scanners can still use this field and ignore the additive workspaceId.
        assertEquals("legacy-env", SetupConfigCodec.decode(SetupConfigCodec.encode(new))!!.environmentId)
        val workspaceOnly = """{"baseUrl":"https://example.test","apiKey":"test-key","workspaceId":"workspace"}"""
        assertEquals("workspace", SetupConfigCodec.decode(workspaceOnly)!!.connectionId())
    }

    @Test
    fun workspaceSavedInOldEnvironmentPreferenceLoadsOnlyActiveSurveys() = runBlocking {
        Mockito.`when`(config.environmentId()).thenReturn("workspace")
        reply(v5Me)
        reply("""{"data":[${surveyJson("active", "inProgress")},${surveyJson("paused", "paused")},${surveyJson("draft", "draft")},${surveyJson("finished", "completed")},${surveyJson("foreign", "inProgress", "other-env", "other-workspace")}]}""")
        assertEquals(1, surveys.refresh().getOrThrow())
        assertEquals(listOf("active"), db.surveyDao().observeAll().first().map { it.id })
        assertEquals("legacy-env", surveys.loadFromCache("active")!!.environmentId)
        assertEquals("/api/v1/management/me", server.takeRequest().path)
        assertEquals("/api/v1/management/surveys", server.takeRequest().path)
    }

    @Test
    fun failedWorkspaceValidationDoesNotErasePreviouslyCachedSurveys() = runBlocking {
        Mockito.`when`(config.environmentId()).thenReturn("legacy-env")
        reply(v5Me)
        reply("""{"data":[${surveyJson("cached", "inProgress")}]}""")
        surveys.refresh().getOrThrow()
        Mockito.`when`(config.workspaceId()).thenReturn("wrong-workspace")
        reply(v5Me)
        assertTrue(surveys.refresh().isFailure)
        assertNotNull(surveys.loadFromCache("cached"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun csatAndCesAcceptOnlyWholeScoresInTheirRange() {
        for ((type, range) in listOf(QType.CSAT to 5, QType.CES to 5, QType.CES to 7)) {
            val q = QuestionDto("score", type, range = range)
            assertFalse(q.isAnswerValid(null))
            assertFalse(q.isAnswerValid(0))
            assertFalse(q.isAnswerValid(range + 1))
            assertFalse(q.isAnswerValid(2.5))
            for (score in 1..range) assertTrue(q.isAnswerValid(score))
        }
    }

    @Test
    fun v5ElementOperandsAndLegacyJumpTargetsKeepBranching() {
        val first = QuestionDto("first", QType.CHOICE_SINGLE,
            choices = listOf(ChoiceDto("yes", mapOf("default" to "Yes", "ar-SA" to "نعم"))),
            logic = listOf(LogicRuleDto("rule", ConditionGroupDto(conditions = listOf(
                ConditionNodeDto(leftOperand = OperandDto("element", "first"), operator = "equals",
                    rightOperand = OperandDto("static", "yes"))
            )), listOf(LogicActionDto(objective = "jumpToQuestion", target = "finish")))))
        val survey = SurveyDto("survey", "Example", environmentId = "legacy-env",
            questions = listOf(first, QuestionDto("second", QType.CSAT)), endings = listOf(EndingDto("finish")))
        assertEquals(NextStep.Ending("finish"), LogicEngine().nextStep(first, survey,
            LogicContext(initialAnswers = mapOf("first" to "نعم"))))
    }

    @Test
    fun responseKeepsNumericScoresAndStampsWithoutContactsUserId() = runBlocking {
        Mockito.`when`(config.environmentId()).thenReturn("legacy-env")
        reply(v5Me)
        reply("""{"data":[${surveyJson("survey", "inProgress")}]}""")
        surveys.refresh().getOrThrow()
        repeat(2) { server.takeRequest() }
        responses.enqueue("survey", "legacy-env", mapOf("score" to 5, "effort" to 7), true, "ar-SA",
            hiddenFields = mapOf("surveyor_id" to "collector-17"))
        reply("""{"data":{"id":"saved-response"}}""")
        assertEquals(1, responses.syncPending().synced)
        val req = server.takeRequest()
        assertEquals("/api/v1/client/legacy-env/responses", req.path)
        val body = moshi.adapter(Map::class.java).fromJson(req.body.readUtf8())!!
        assertFalse(body.containsKey("userId"))
        assertEquals("ar-SA", body["language"])
        val data = body["data"] as Map<*, *>
        assertEquals(5.0, data["score"])
        assertEquals(7.0, data["effort"])
        assertEquals("collector-17", data["surveyor_id"])
    }

    @Test
    fun v5FileUploadSendsElementIdAndSubstitutesItsUrlBeforeResponseSync() = runBlocking {
        val source = File.createTempFile("fbint-test", ".txt").apply { writeText("test attachment") }
        try {
            db.queuedFileDao().insert(QueuedFileEntity("file", "survey", "upload-question", "legacy-env",
                source.absolutePath, "test.txt", "text/plain", source.length(), 1))
            val id = responses.enqueue("survey", "legacy-env", mapOf("upload-question" to listOf("fbint-file:file")), true, null)
            assertTrue(responses.syncPending().retry)
            assertEquals(0, server.requestCount)
            val uploadedUrl = server.url("/storage/workspace/private/surveys/survey/elements/upload-question/test.txt").toString()
            reply("""{"data":{"signedUrl":"${server.url("/signed-upload")}","fileUrl":"$uploadedUrl"}}""")
            reply("")
            assertEquals(1, files.uploadPending().done)
            val presign = server.takeRequest()
            assertEquals("/api/v1/client/legacy-env/storage", presign.path)
            val input = moshi.adapter(Map::class.java).fromJson(presign.body.readUtf8())!!
            assertEquals("upload-question", input["elementId"])
            val upload = server.takeRequest()
            assertEquals("PUT", upload.method)
            assertEquals("test attachment", upload.body.readUtf8())
            reply("""{"data":{"id":"saved"}}""")
            assertEquals(1, responses.syncPending().synced)
            assertTrue(server.takeRequest().body.readUtf8().contains(uploadedUrl))
            assertEquals(id, db.responseQueueDao().recent(1).first().single().clientUuid)
            assertFalse(source.exists())
        } finally { source.delete() }
    }

    /** Optional read-only snapshot check. Files stay outside the repository; no live POSTs. */
    @Test
    fun liveV5SurveySnapshotParsesCachesAndTraversesLocally() = runBlocking {
        val path = System.getProperty("fbint.compatibilityFixtures").orEmpty()
        assumeTrue("Supply -PcompatibilityFixtures=/path/to/read-only/snapshots", path.isNotBlank())
        val meJson = File(path, "me.json").readText()
        val surveyJson = File(path, "surveys.json").readText()
        val me = moshi.adapter(MeDto::class.java).fromJson(meJson)!!
        val all = moshi.adapter(SurveyListEnvelope::class.java).fromJson(surveyJson)!!.data
        Mockito.`when`(config.environmentId()).thenReturn(me.workspace!!.id)
        val active = all.filter { it.status == "inProgress" }
        // Use the actual schema in local HTTP fixtures; image warmup is omitted in this check.
        val withoutImages = active.map { s -> s.copy(welcomeCard = null, endings = s.endings.map { it.copy(imageUrl = null) },
            questions = s.questions.map { q -> q.copy(imageUrl = null, choices = q.choices?.map { it.copy(imageUrl = null) }) }) }
        reply(meJson)
        reply(moshi.adapter(SurveyListEnvelope::class.java).toJson(SurveyListEnvelope(withoutImages)))
        assertEquals(active.size, surveys.refresh().getOrThrow())
        for (survey in active) {
            assertNotNull(surveys.loadFromCache(survey.id))
            assertTrue("Empty survey ${survey.id}", survey.questions.isNotEmpty())
            val ctx = LogicContext()
            for (q in survey.questions) {
                assertTrue("Unsupported ${q.type}", q.type in QType.supported)
                ctx.answers[q.id] = when (q.type) {
                    QType.CSAT, QType.CES, QType.RATING -> q.range ?: 5
                    QType.NPS -> 8
                    QType.CHOICE_SINGLE -> q.choices!!.first().label.localized("default")
                    QType.OPEN_TEXT -> "Example response"
                    else -> error("Extend snapshot traversal for ${q.type}")
                }
                assertTrue("Invalid sample for ${q.id}", q.isAnswerValid(ctx.answers[q.id]))
            }
            var next: NextStep = NextStep.Question(survey.questions.first().id)
            val visited = mutableSetOf<String>()
            while (next is NextStep.Question) {
                val id = next.id
                assertTrue("Loop at $id", visited.add(id))
                val q = survey.questions.first { it.id == id }
                next = LogicEngine().nextStep(q, survey, ctx)
            }
            assertTrue(next is NextStep.Ending || next == NextStep.Done)
        }
        println("Checked ${active.size} active surveys / ${active.sumOf { it.questions.size }} questions against local fixtures")
    }

    @Test
    fun hidingSurveyPreservesCachedMetadataAndQueuedResponses() = runBlocking {
        Mockito.`when`(config.environmentId()).thenReturn("legacy-env")
        val enabled = moshi.adapter(SurveyDto::class.java).fromJson(surveyJson("controlled", "inProgress"))!!.copy(
            variables = listOf(VariableDto("setting", SHOW_IN_APP_VARIABLE, "text", "YES")),
            hiddenFields = HiddenFieldsDto(true, listOf("surveyor_id")),
        )
        suspend fun refreshWith(survey: SurveyDto) {
            reply(v5Me)
            reply(moshi.adapter(SurveyListEnvelope::class.java).toJson(SurveyListEnvelope(listOf(survey))))
            surveys.refresh().getOrThrow()
            repeat(2) { server.takeRequest() }
        }
        refreshWith(enabled)
        assertEquals(listOf("controlled"), surveys.observeCachedSurveys().first().map { it.id })
        responses.enqueue("controlled", "legacy-env", mapOf("score" to 5), true, null,
            autoStampCandidates = mapOf("surveyor_id" to "collector-17"))

        refreshWith(enabled.copy(variables = listOf(VariableDto("setting", SHOW_IN_APP_VARIABLE, "text", "NO"))))
        assertTrue(surveys.observeCachedSurveys().first().isEmpty())
        assertNotNull(surveys.loadFromCache("controlled"))
        reply("""{"data":{"id":"saved"}}""")
        assertEquals(1, responses.syncPending().synced)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"surveyor_id\":\"collector-17\""))
        assertTrue(db.responseQueueDao().pendingOnce(Long.MAX_VALUE).isEmpty())

        refreshWith(enabled)
        assertEquals(1, surveys.observeCachedSurveys().first().size)
    }

    @Test
    fun blankManualFieldsCannotEraseCaptureStampsOrUseRenamedSurveyor() = runBlocking {
        Mockito.`when`(config.environmentId()).thenReturn("legacy-env")
        val survey = moshi.adapter(SurveyDto::class.java).fromJson(surveyJson("stamps", "inProgress"))!!.copy(
            hiddenFields = HiddenFieldsDto(true, AUTO_STAMPED_HIDDEN_FIELD_IDS.toList()))
        reply(v5Me)
        reply(moshi.adapter(SurveyListEnvelope::class.java).toJson(SurveyListEnvelope(listOf(survey))))
        surveys.refresh().getOrThrow()
        repeat(2) { server.takeRequest() }
        val stamps = mapOf("surveyor_id" to "collector-17", "surveyor_name" to "collector-17",
            "time_to_complete_seconds" to "123", "location" to "24.7,46.6")
        responses.enqueue("stamps", "legacy-env", mapOf("score" to 5), true, null,
            hiddenFields = stamps.mapValues { "" }, autoStampCandidates = stamps)
        Mockito.`when`(config.surveyorId()).thenReturn("new-collector")
        reply("""{"data":{"id":"saved"}}""")
        assertEquals(1, responses.syncPending().synced)
        val body = moshi.adapter(Map::class.java).fromJson(server.takeRequest().body.readUtf8())!!
        val data = body["data"] as Map<*, *>
        stamps.forEach { (key, value) -> assertEquals(value, data[key]) }
    }

    @Test
    fun formbricksWelcomeSubheaderSurvivesCacheSerialization() {
        val adapter = moshi.adapter(SurveyDto::class.java)
        val survey = adapter.fromJson(surveyJson("welcome", "inProgress"))!!.copy(
            welcomeCard = WelcomeCardDto(enabled = true, headline = mapOf("default" to "Hello"),
                subheader = mapOf("default" to "<b>Welcome message</b>", "ar" to "مرحبا")))
        val cached = adapter.fromJson(adapter.toJson(survey))!!
        assertEquals("Welcome message", cached.welcomeCard!!.subheader.localized("default"))
        assertEquals("مرحبا", cached.welcomeCard!!.subheader.localized("ar"))
    }

    private fun reply(body: String) { server.enqueue(MockResponse().setBody(body)) }

    private fun surveyJson(id: String, status: String, env: String = "legacy-env", workspace: String = "workspace") =
        """{"id":"$id","name":"Example","environmentId":"$env","workspaceId":"$workspace","status":"$status","questions":[{"id":"score","type":"csat","range":5}]}"""

    private val v5Me = """{"id":"legacy-env","workspace":{"id":"workspace","name":"Example"},"project":{"id":"workspace","name":"Example"}}"""
}

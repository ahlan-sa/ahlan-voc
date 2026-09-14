package com.fbint.collector.ui.runner

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.fbint.collector.data.remote.dto.EndingDto
import com.fbint.collector.data.remote.dto.QuestionDto
import com.fbint.collector.data.remote.dto.SurveyDto
import com.fbint.collector.domain.LanguageOption
import com.fbint.collector.domain.QType
import com.fbint.collector.domain.SurveyStyle
import com.fbint.collector.domain.computedStyle
import com.fbint.collector.domain.localized
import com.fbint.collector.ui.runner.components.AddressQuestion
import com.fbint.collector.ui.runner.components.CalQuestion
import com.fbint.collector.ui.runner.components.ChoiceMultiQuestion
import com.fbint.collector.ui.runner.components.ChoiceSingleQuestion
import com.fbint.collector.ui.runner.components.ConsentQuestion
import com.fbint.collector.ui.runner.components.ContactInfoQuestion
import com.fbint.collector.ui.runner.components.CtaQuestion
import com.fbint.collector.ui.runner.components.DateQuestion
import com.fbint.collector.ui.runner.components.FileUploadQuestion
import com.fbint.collector.ui.runner.components.MatrixQuestion
import com.fbint.collector.ui.runner.components.NpsQuestion
import com.fbint.collector.ui.runner.components.OpenTextQuestion
import com.fbint.collector.ui.runner.components.PictureSelectionQuestion
import com.fbint.collector.ui.runner.components.RankingQuestion
import com.fbint.collector.ui.runner.components.RatingQuestion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyRunnerScreen(nav: NavHostController, surveyId: String) {
    LocationRequired { SurveyRunnerContent(nav, surveyId) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SurveyRunnerContent(nav: NavHostController, surveyId: String) {
    val vm: SurveyRunnerViewModel = hiltViewModel<SurveyRunnerViewModel, SurveyRunnerViewModel.Factory>(
        creationCallback = { factory -> factory.create(surveyId) },
    )
    val state by vm.state.collectAsState()
    val survey = state.survey
    val style = remember(survey) { survey?.computedStyle() ?: SurveyStyle.Default }
    val isRtl = state.availableLanguages.firstOrNull { it.lookupKey == state.language }?.isRtl == true
    val direction = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    var confirmExit by remember { mutableStateOf(false) }
    val collecting = state.stage == RunnerStage.Welcome || state.stage is RunnerStage.Question
    androidx.activity.compose.BackHandler(enabled = collecting || state.stage == RunnerStage.Submitting) {
        if (collecting) confirmExit = true
    }
    if (state.restoredDraft) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { nav.popBackStack() },
            title = { Text("Resume saved response?") },
            text = { Text("Your answers and collection time were saved on this device.") },
            confirmButton = { TextButton(onClick = vm::resumeDraft) { Text("Resume") } },
            dismissButton = { TextButton(onClick = vm::reset) { Text("Discard and start new") } })
    }
    if (confirmExit) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { confirmExit = false },
            title = { Text("Leave this response?") },
            text = { Text("Save your progress to resume later, or discard this unfinished response. Discarded answers cannot be recovered.") },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        confirmExit = false
                        if (vm.saveAndExit()) nav.popBackStack()
                    }) { Text("Save and leave") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        confirmExit = false
                        vm.discardAndExit { nav.popBackStack() }
                    }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("Discard and exit")
                    }
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { confirmExit = false }) { Text("Keep collecting") }
                }
            })
    }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
    Scaffold(
        containerColor = style.backgroundColor,
        topBar = {
            TopAppBar(
                title = { Column {
                    Text(survey?.name ?: "Survey", color = style.questionTextColor)
                    if (collecting) Text(state.locationStatus, style = MaterialTheme.typography.bodySmall)
                } },
                navigationIcon = {
                    IconButton(enabled = state.stage != RunnerStage.Submitting, onClick = { if (collecting) confirmExit = true else nav.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to list",
                            tint = style.questionTextColor,
                        )
                    }
                },
                actions = {
                    if (state.showLanguageSwitch) {
                        LanguageMenu(
                            current = state.language,
                            options = state.availableLanguages,
                            onPick = vm::setLanguage,
                            tint = style.questionTextColor,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = style.backgroundColor,
                    scrolledContainerColor = style.backgroundColor,
                    titleContentColor = style.questionTextColor,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(style.backgroundColor),
        ) {
            ProgressBar(state, style)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                SurveyCard(style) {
                    when (val stage = state.stage) {
                        RunnerStage.Loading, RunnerStage.Submitting ->
                            Centered { CircularProgressIndicator(color = style.brandColor) }

                        RunnerStage.Welcome ->
                            WelcomePane(survey, state.language, style, vm::startFromWelcome)

                        is RunnerStage.Question -> {
                            val q = vm.currentQuestion() ?: return@SurveyCard
                            QuestionPane(
                                survey = survey!!,
                                question = q,
                                lang = state.language,
                                style = style,
                                answer = vm.questionInitialAnswer(q),
                                error = state.validationError,
                                onAnswer = { vm.setAnswer(q.id, it) },
                                delegate = vm,
                            )
                        }

                        is RunnerStage.Ending -> {
                            val ending = vm.currentEnding()
                            EndingPane(
                                ending = ending,
                                lang = state.language,
                                style = style,
                                onAnother = vm::reset,
                                onExit = { nav.popBackStack() },
                            )
                        }

                        RunnerStage.Done -> DonePane(style, onAnother = vm::reset, onExit = { nav.popBackStack() })

                        is RunnerStage.Error -> Centered {
                            Text(stage.message, color = style.questionTextColor)
                        }
                    }
                }
            }
            if (state.stage == RunnerStage.Done || state.stage is RunnerStage.Ending) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(if (state.receiptSynced) "Synced to Formbricks" else "Saved on this device · waiting to sync",
                        style = MaterialTheme.typography.titleSmall)
                    Text(state.receipt, style = MaterialTheme.typography.bodySmall)
                }
            }
            FooterButtons(state = state, style = style, onBack = vm::back, onNext = vm::next, onStart = vm::startFromWelcome)
        }
    }
    }
}

/** Centered card with a max width — matches the web survey's contained layout, especially on tablet. */
@Composable
private fun SurveyCard(style: SurveyStyle, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(style.cornerRadius),
        colors = CardDefaults.cardColors(containerColor = style.cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = Modifier
            .widthIn(max = 720.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp)
                .heightIn(min = 320.dp),
        ) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageMenu(
    current: String,
    options: List<LanguageOption>,
    onPick: (String) -> Unit,
    tint: Color,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentDisplay = options.firstOrNull { it.lookupKey == current }?.displayName ?: current
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.Language, contentDescription = "Language: $currentDisplay", tint = tint)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEach { opt ->
            val marker = if (opt.lookupKey == current) "  •  current" else ""
            DropdownMenuItem(
                text = { Text(opt.displayName + marker) },
                onClick = { expanded = false; onPick(opt.lookupKey) },
            )
        }
    }
}

@Composable
private fun ProgressBar(state: RunnerState, style: SurveyStyle) {
    val survey = state.survey ?: return
    val total = survey.questions.size.coerceAtLeast(1)
    val current = (state.stage as? RunnerStage.Question)?.let { stage ->
        survey.questions.indexOfFirst { it.id == stage.questionId }
    } ?: -1
    val progress = if (current < 0) 0f else (current + 1f) / total
    LinearProgressIndicator(
        progress = { progress },
        color = style.brandColor,
        trackColor = style.brandColor.copy(alpha = 0.15f),
        gapSize = 0.dp,
        drawStopIndicator = {},
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp),
    )
}

@Composable
private fun WelcomePane(survey: SurveyDto?, lang: String, style: SurveyStyle, onStart: () -> Unit) {
    val card = survey?.welcomeCard ?: return
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            card.headline.localized(lang),
            color = style.questionTextColor,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(12.dp))
        val body = card.subheader.localized(lang).ifBlank { card.html.localized(lang) }
        if (body.isNotBlank()) Text(body, color = style.questionTextColor.copy(alpha = 0.85f))
    }
}

@Composable
private fun QuestionHeader(survey: SurveyDto, question: QuestionDto, lang: String, style: SurveyStyle) {
    val total = survey.questions.size
    val idx = survey.questions.indexOfFirst { it.id == question.id }.coerceAtLeast(0)
    Text(
        text = "Question ${idx + 1} of $total",
        color = style.brandColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = question.headline.localized(lang),
        color = style.questionTextColor,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
    )
    if (!question.subheader.isNullOrEmpty()) {
        val sub = question.subheader.localized(lang)
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(text = sub, color = style.questionTextColor.copy(alpha = 0.7f))
        }
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun QuestionPane(
    survey: SurveyDto,
    question: QuestionDto,
    lang: String,
    style: SurveyStyle,
    answer: Any?,
    error: String?,
    onAnswer: (Any?) -> Unit,
    delegate: FileUploadDelegate,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        QuestionHeader(survey, question, lang, style)
        when (question.type) {
            QType.OPEN_TEXT -> OpenTextQuestion(question, lang, answer as? String, onAnswer)
            QType.CHOICE_SINGLE -> ChoiceSingleQuestion(question, lang, answer as? String, onAnswer)
            QType.CHOICE_MULTI -> {
                @Suppress("UNCHECKED_CAST")
                ChoiceMultiQuestion(question, lang, answer as? List<String>, onAnswer)
            }
            QType.RATING, QType.CSAT, QType.CES -> RatingQuestion(question, lang, (answer as? Number)?.toInt(), onAnswer)
            QType.NPS -> NpsQuestion(question, lang, (answer as? Number)?.toInt(), onAnswer)
            QType.CTA -> CtaQuestion(question, lang, answer as? String, onAnswer)
            QType.CONSENT -> ConsentQuestion(question, lang, answer as? String, onAnswer)
            QType.PICTURE_SELECTION -> {
                @Suppress("UNCHECKED_CAST")
                PictureSelectionQuestion(question, answer as? List<String>, onAnswer)
            }
            QType.DATE -> DateQuestion(question, answer as? String, onAnswer)
            QType.MATRIX -> {
                @Suppress("UNCHECKED_CAST")
                MatrixQuestion(question, lang, answer as? Map<String, String>, onAnswer)
            }
            QType.RANKING -> {
                @Suppress("UNCHECKED_CAST")
                RankingQuestion(question, lang, answer as? List<String>, onAnswer)
            }
            QType.ADDRESS -> {
                @Suppress("UNCHECKED_CAST")
                AddressQuestion(question, lang, answer as? List<String>, onAnswer)
            }
            QType.CONTACT_INFO -> {
                @Suppress("UNCHECKED_CAST")
                ContactInfoQuestion(question, lang, answer as? List<String>, onAnswer)
            }
            QType.FILE_UPLOAD -> {
                @Suppress("UNCHECKED_CAST")
                FileUploadQuestion(question, delegate, answer as? List<String>, onAnswer)
            }
            QType.CAL -> CalQuestion(question, answer as? String, onAnswer)
            else -> Text("Unsupported question type: ${question.type}", color = style.questionTextColor)
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun EndingPane(
    ending: EndingDto?,
    lang: String,
    style: SurveyStyle,
    onAnother: () -> Unit,
    onExit: () -> Unit,
) {
    val ctx = LocalContext.current
    if (ending == null) {
        DonePane(style, onAnother = onAnother, onExit = onExit)
        return
    }
    when (ending.type) {
        "redirectToUrl" -> {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    ending.label ?: "Open link",
                    color = style.questionTextColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                if (!ending.url.isNullOrBlank()) {
                    PrimaryButton(text = ending.url, style = style) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, ending.url.toUri())) }
                    }
                }
                Spacer(Modifier.height(24.dp))
                ActionButtons(style, onAnother, onExit)
            }
        }
        else -> {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    ending.headline.localized(lang).ifBlank { "Thank you" },
                    color = style.questionTextColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                if (!ending.subheader.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        ending.subheader.localized(lang),
                        color = style.questionTextColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(24.dp))
                ActionButtons(style, onAnother, onExit)
            }
        }
    }
}

@Composable
private fun DonePane(style: SurveyStyle, onAnother: () -> Unit, onExit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Response saved",
            color = style.questionTextColor,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "See the collection receipt below for upload status.",
            color = style.questionTextColor.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(24.dp))
        ActionButtons(style, onAnother, onExit)
    }
}

@Composable
private fun ActionButtons(style: SurveyStyle, onAnother: () -> Unit, onExit: () -> Unit) {
    PrimaryButton(text = "Collect another response", style = style, onClick = onAnother)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onExit,
        shape = RoundedCornerShape(style.buttonCornerRadius),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Back to surveys") }
}

@Composable
private fun FooterButtons(
    state: RunnerState,
    style: SurveyStyle,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onStart: () -> Unit,
) {
    when (val stage = state.stage) {
        RunnerStage.Welcome -> Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            PrimaryButton(text = state.survey?.welcomeCard?.buttonLabel.localized(state.language).ifBlank { "Start" }, style = style, onClick = onStart)
        }
        is RunnerStage.Question -> Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val survey = state.survey ?: return
            val canBack = survey.isBackButtonHidden != true
            if (canBack) {
                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(style.buttonCornerRadius),
                    modifier = Modifier.weight(1f),
                ) { Text("Back") }
            }
            val isLast = survey.questions.indexOfFirst { it.id == stage.questionId } == survey.questions.lastIndex
            Box(modifier = Modifier.weight(1f)) {
                PrimaryButton(text = if (isLast) "Submit" else "Next", style = style, onClick = onNext)
            }
        }
        else -> Unit
    }
}

@Composable
private fun PrimaryButton(text: String, style: SurveyStyle, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = style.brandColor,
            contentColor = style.onBrandColor,
        ),
        shape = RoundedCornerShape(style.buttonCornerRadius),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

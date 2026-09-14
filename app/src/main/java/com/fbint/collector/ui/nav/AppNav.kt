package com.fbint.collector.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fbint.collector.data.repository.ConfigRepository
import com.fbint.collector.ui.hidden.HiddenFieldsScreen
import com.fbint.collector.ui.runner.SurveyRunnerScreen
import com.fbint.collector.ui.setup.AdminSetupScreen
import com.fbint.collector.ui.setup.QrGenerateScreen
import com.fbint.collector.ui.setup.QrScanScreen
import com.fbint.collector.ui.setup.RolePickerScreen
import com.fbint.collector.ui.status.SyncStatusScreen
import com.fbint.collector.ui.surveylist.SurveyListScreen
import com.fbint.collector.ui.surveyor.SurveyorIdScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

object Routes {
    const val SPLASH = "splash"
    const val ROLE = "role"
    const val ADMIN_SETUP = "admin/setup"
    const val DOWNLOAD_QR = "download/qr"
    const val ADMIN_QR = "admin/qr"
    const val SURVEYOR_SCAN = "surveyor/scan"
    const val SURVEYOR_ID = "surveyor/id"
    const val SURVEY_LIST = "surveyList"
    const val RUNNER = "runner/{surveyId}"
    const val HIDDEN_FIELDS = "hidden/{surveyId}"
    const val SYNC_STATUS = "sync"
    fun runner(id: String) = "runner/$id"
    fun hiddenFields(id: String) = "hidden/$id"
}

sealed interface OnboardingState {
    data object Unknown : OnboardingState
    data object NoConfig : OnboardingState
    data object NoSurveyor : OnboardingState
    data object Ready : OnboardingState
}

@HiltViewModel
class StartDestinationViewModel @Inject constructor(
    config: ConfigRepository,
) : ViewModel() {
    val state = config.observeOnboardingState()
        .stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingState.Unknown)
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) { SplashScreen(nav) }
        composable(Routes.ROLE) { RolePickerScreen(nav) }
        composable(Routes.ADMIN_SETUP) { AdminSetupScreen(nav) }
        composable(Routes.DOWNLOAD_QR) { com.fbint.collector.ui.setup.DownloadQrScreen(nav) }
        composable(Routes.ADMIN_QR) { QrGenerateScreen(nav) }
        composable(Routes.SURVEYOR_SCAN) { QrScanScreen(nav) }
        composable(Routes.SURVEYOR_ID) { SurveyorIdScreen(nav) }
        composable(Routes.SURVEY_LIST) { SurveyListScreen(nav) }
        composable(
            route = Routes.RUNNER,
            arguments = listOf(navArgument("surveyId") { type = NavType.StringType }),
        ) { backStack ->
            val id = backStack.arguments?.getString("surveyId").orEmpty()
            SurveyRunnerScreen(nav, surveyId = id)
        }
        composable(
            route = Routes.HIDDEN_FIELDS,
            arguments = listOf(navArgument("surveyId") { type = NavType.StringType }),
        ) { backStack ->
            val id = backStack.arguments?.getString("surveyId").orEmpty()
            HiddenFieldsScreen(nav, surveyId = id)
        }
        composable(Routes.SYNC_STATUS) { SyncStatusScreen(nav) }
    }
}

/**
 * Shown while the app reads the encrypted prefs and decides where to send the user. The
 * dynamic-startDestination pattern doesn't work in compose-navigation 2.8 — the graph captures
 * the start at first composition. So we always start here, then navigate exactly once with
 * popUpTo(SPLASH){inclusive=true} to remove ourselves from the back stack.
 */
@Composable
private fun SplashScreen(nav: NavHostController) {
    val vm: StartDestinationViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(state) {
        val target = when (state) {
            OnboardingState.Unknown -> return@LaunchedEffect  // wait for the first real value
            OnboardingState.NoConfig -> Routes.ROLE
            OnboardingState.NoSurveyor -> Routes.SURVEYOR_ID
            OnboardingState.Ready -> Routes.SURVEY_LIST
        }
        nav.navigate(target) {
            popUpTo(Routes.SPLASH) { inclusive = true }
            launchSingleTop = true
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

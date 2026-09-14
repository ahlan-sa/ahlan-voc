package com.fbint.collector.ui.surveyor

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import com.fbint.collector.data.repository.ConfigRepository
import com.fbint.collector.sync.SyncScheduler
import com.fbint.collector.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SurveyorIdState(val id: String = "", val canSubmit: Boolean = false)

@HiltViewModel
class SurveyorIdViewModel @Inject constructor(
    private val config: ConfigRepository,
    private val sync: SyncScheduler,
) : ViewModel() {
    private val initialId = config.surveyorId().orEmpty()
    private val _state = MutableStateFlow(SurveyorIdState(id = initialId, canSubmit = initialId.isNotBlank()))
    val state: StateFlow<SurveyorIdState> = _state.asStateFlow()

    fun onIdChange(v: String) = _state.update { it.copy(id = v, canSubmit = v.isNotBlank()) }

    fun save(onSaved: () -> Unit) {
        val id = _state.value.id.trim()
        if (id.isEmpty()) return
        config.saveSurveyorId(id)
        sync.requestImmediateSurveyRefresh()
        onSaved()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyorIdScreen(
    nav: NavHostController,
    vm: SurveyorIdViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    // Ask for location once during onboarding. If denied, location-based hidden fields just
    // stay empty for this device — the runner doesn't block on it.
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* result ignored — we just record the choice; runner re-checks at submit time */ }

    Scaffold(topBar = { TopAppBar(title = { Text("Surveyor identity") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Text("Enter your name or staff ID. We attach it to every response so admins can see who collected what.")
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.id,
                onValueChange = vm::onIdChange,
                label = { Text("Name or staff ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    locationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        )
                    )
                    vm.save {
                        nav.navigate(Routes.SURVEY_LIST) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue") }
            Spacer(Modifier.height(12.dp))
            Text(
                "We'll ask for location permission next — only used if your survey has a hidden field for location. You can deny if you prefer.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
    }
}

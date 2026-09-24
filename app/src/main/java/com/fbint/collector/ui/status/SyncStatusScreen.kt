package com.fbint.collector.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.fbint.collector.data.local.entity.QueuedResponseEntity
import com.fbint.collector.data.repository.ResponseRepository
import com.fbint.collector.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class SyncStatusState(
    val pending: Int = 0,
    val synced: Int = 0,
    val struggling: Int = 0,
    val recent: List<QueuedResponseEntity> = emptyList(),
    val surveyNames: Map<String, String> = emptyMap(),
)

@HiltViewModel
class SyncStatusViewModel @Inject constructor(
    private val repo: ResponseRepository,
    private val sync: SyncScheduler,
    private val surveys: com.fbint.collector.data.local.SurveyDao,
) : ViewModel() {
    val state = combine(
        repo.pendingCount(),
        repo.syncedCount(),
        repo.strugglingCount(),
        repo.recent(),
        surveys.observeAll(),
    ) { pending, synced, struggling, recent, surveys ->
        SyncStatusState(pending, synced, struggling, recent, surveys.associate { it.id to it.name })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatusState())

    val manualSync = sync.observeManualSync().map { list -> list.firstOrNull { !it.state.isFinished } ?: list.maxByOrNull { it.outputData.getLong("finishedAt", 0) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun syncNow() = sync.requestManualSync()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusScreen(
    nav: NavHostController,
    vm: SyncStatusViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val manual by vm.manualSync.collectAsState()
    val manualBusy = manual?.state?.isFinished == false

    Scaffold(topBar = { TopAppBar(title = { Text("Sync status") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile("Pending", state.pending.toString(), Modifier.weight(1f))
                StatTile("Synced", state.synced.toString(), Modifier.weight(1f))
                StatTile("Needs retry", state.struggling.toString(), Modifier.weight(1f))
            }
            Button(
                onClick = vm::syncNow,
                enabled = !manualBusy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text(if (manualBusy) "Sync requested…" else "Sync now") }
            manual?.let { work ->
                val message = when (work.state) {
                    androidx.work.WorkInfo.State.RUNNING -> "Checking saved responses and uploading…"
                    androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED -> "Queued — waiting for a connection or Android to start the check."
                    androidx.work.WorkInfo.State.CANCELLED -> "Check interrupted. Responses remain saved; tap Sync now again."
                    else -> work.outputData.getString("message") ?: "Check finished. See the response statuses below."
                }
                Text(message, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                val completed = work.outputData.getLong("finishedAt", 0)
                if (completed > 0) Text("Last check: " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT).format(java.util.Date(completed)), modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Recent activity",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(state.recent, key = { it.clientUuid }) { item -> ResponseRow(item, state.surveyNames[item.surveyId]) }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun ResponseRow(item: QueuedResponseEntity, name: String?) {
    val statusLabel = when {
        item.syncedAt != null -> "Synced"
        item.sendingAt != null -> "Saved · awaiting upload verification"
        item.attempts == 0 -> "Saved on device · waiting to sync"
        else -> "Retry x${item.attempts}"
    }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(name ?: "Survey ${item.surveyId}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Surveyor ${item.surveyorId ?: "—"}  •  $statusLabel",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Collected " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                java.text.DateFormat.SHORT).format(java.util.Date(item.capturedAt)), style = MaterialTheme.typography.bodySmall)
            if (item.syncedAt != null) Text("Synced " + java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(item.syncedAt)),
                style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text("Response ID: ${item.clientUuid}", style = MaterialTheme.typography.labelSmall)
            }
            if (!item.lastError.isNullOrBlank() && item.syncedAt == null) {
                Text(
                    "Last recorded error: ${item.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

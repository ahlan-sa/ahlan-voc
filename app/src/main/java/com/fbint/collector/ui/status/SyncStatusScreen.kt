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
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue

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
    private val backups: com.fbint.collector.data.repository.ResponseBackupRepository,
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
    val backupBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val backupMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun exportBackup(uri: android.net.Uri) = backupOperation {
        val count = backups.export(uri)
        "$count submitted responses exported with locally available attachments. Uploaded-only attachments retain their URLs. Keep this file private."
    }
    fun restoreBackup(uri: android.net.Uri) = backupOperation {
        val count = backups.restore(uri)
        "$count responses restored; existing responses were kept. Tap Sync now to verify and upload pending responses. Keep the original phone offline."
    }
    private fun backupOperation(action: suspend () -> String) {
        if (backupBusy.value) return
        backupBusy.value = true
        viewModelScope.launch {
            try { backupMessage.value = action() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { backupMessage.value = "Backup operation failed: ${error.message}. Original responses remain saved." }
            finally { backupBusy.value = false }
        }
    }
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
    val backupBusy by vm.backupBusy.collectAsState()
    val backupMessage by vm.backupMessage.collectAsState()
    var restoreUri by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null) }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { vm.exportBackup(it) }
    }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri -> restoreUri = uri }
    if (restoreUri != null) androidx.compose.material3.AlertDialog(
        onDismissRequest = { restoreUri = null },
        title = { Text("Restore responses") },
        text = { Text("Connect this app to the original Formbricks server first. If this backup is from another phone, keep that phone offline during recovery and stop using its old queue afterward. Otherwise both phones could upload the same responses. Existing responses on this phone will not be overwritten.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            restoreUri?.let { vm.restoreBackup(it) }; restoreUri = null
        }) { Text("Original phone offline · restore") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { restoreUri = null }) { Text("Cancel") } },
    )

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
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(enabled = !backupBusy, onClick = {
                    exporter.launch("ahlan-responses-${System.currentTimeMillis()}.zip")
                }) { Text("Export backup") }
                androidx.compose.material3.TextButton(enabled = !backupBusy && !manualBusy, onClick = {
                    importer.launch("application/zip")
                }) { Text("Restore backup") }
            }
            if (backupBusy) Text("Preparing response backup…", Modifier.padding(horizontal = 16.dp))
            backupMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
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
        else -> "Saved · upload needs attention"
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
                Text(com.fbint.collector.domain.syncRecoveryAdvice(item.lastError),
                    style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    "Last recorded error: ${item.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                }
            }
        }
    }
}

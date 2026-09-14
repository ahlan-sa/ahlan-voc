package com.fbint.collector.ui.surveylist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.fbint.collector.ui.nav.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyListScreen(
    nav: NavHostController,
    vm: SurveyListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val updateState by vm.updateState.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.silentlyCheckOnLaunch()
        vm.refresh()
    }
    UpdateDialog(updateState, vm::downloadAndInstall, vm::dismissUpdate)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.projectName ?: "Surveys")
                        Text(
                            "Surveyor: ${state.surveyorId ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.SYNC_STATUS) }) {
                        Icon(Icons.Filled.CloudSync, contentDescription = "Sync status")
                    }
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    OverflowMenu(nav = nav, onReset = vm::resetDevice, onCheckUpdate = vm::checkForUpdate, pending = state.pendingResponses, verifyAdmin = vm::verifyAdminPassword, hasPassword = vm::hasAdminPassword, savePassword = vm::saveAdminPassword)
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            QueueBanner(
                pending = state.pendingResponses,
                synced = state.syncedResponses,
                struggling = state.strugglingResponses,
                online = state.online,
                onSyncNow = vm::syncNow,
            )
            state.surveys.maxOfOrNull { it.cachedAt }?.let { time ->
                Text("Surveys saved " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT).format(java.util.Date(time)),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
            if (state.refreshing) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(strokeWidth = 2.dp) }
            }
            if (state.refreshError != null) {
                Text(
                    "Refresh failed: ${state.refreshError}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.surveys.isEmpty() && !state.refreshing) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No surveys enabled for this app. Set show_in_app to YES in Formbricks, then refresh while online.",
                        modifier = Modifier.padding(24.dp))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(state.surveys, key = { it.id }) { survey ->
                        val counts = state.perSurveyCounts[survey.id]
                        val offlineReady = survey.id in state.offlineReadyIds
                        val canOpen = survey.id in state.savedDefinitionIds && (state.online || offlineReady)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .alpha(if (canOpen) 1f else 0.5f)
                                .clickable(enabled = canOpen) { vm.onSurveyTapped(survey, nav) },
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(survey.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    val pinned = survey.id in state.pinnedSurveyIds
                                    androidx.compose.material3.IconToggleButton(checked = pinned,
                                        onCheckedChange = { vm.toggleSurveyPin(survey.id) }) {
                                        Icon(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                            contentDescription = if (pinned) "Unpin ${survey.name}" else "Pin ${survey.name} to top",
                                            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    when {
                                        offlineReady -> "Available offline"
                                        survey.id !in state.savedDefinitionIds -> if (state.online) "Not ready — refresh surveys" else "Connect to download"
                                        state.online -> "Online only — offline download incomplete"
                                        else -> "Connect to download"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (counts != null) {
                                    Spacer(Modifier.height(6.dp))
                                    val syncedNow = counts.total - counts.pending
                                    Text(
                                        "Captured: ${counts.total}  •  Synced: $syncedNow  •  Pending: ${counts.pending}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverflowMenu(
    nav: NavHostController,
    onReset: () -> Unit,
    onCheckUpdate: () -> Unit,
    pending: Int,
    verifyAdmin: (String) -> Boolean,
    hasPassword: () -> Boolean,
    savePassword: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var adminMode by remember { mutableStateOf(false) }
    var unlock by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var changingPassword by remember { mutableStateOf(false) }
    if (unlock) {
        val creating = changingPassword || !hasPassword()
        fun closePasswordDialog() {
            unlock = false; password = ""; confirmation = ""; passwordError = null; changingPassword = false
        }
        AlertDialog(onDismissRequest = { closePasswordDialog() },
            title = { Text(if (creating) "Set admin password" else "Unlock admin controls") },
            text = { Column {
                Text(if (creating) "Choose a password of at least 4 characters for this device. Sharing the setup QR does not require it."
                    else "Enter this device's admin password.")
                androidx.compose.material3.OutlinedTextField(value = password, onValueChange = { password = it; passwordError = null },
                    label = { Text("Password") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    isError = passwordError != null, singleLine = true)
                if (creating) androidx.compose.material3.OutlinedTextField(value = confirmation,
                    onValueChange = { confirmation = it; passwordError = null }, label = { Text("Confirm password") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true)
                passwordError?.let { Text(it) }
            } },
            confirmButton = { TextButton(onClick = {
                if (creating) {
                    if (password.length < 4) passwordError = "Use at least 4 characters."
                    else if (password != confirmation) passwordError = "Passwords do not match."
                    else {
                        try { savePassword(password); adminMode = true; closePasswordDialog() }
                        catch (_: Exception) { passwordError = "Could not save password. Try again." }
                    }
                } else if (verifyAdmin(password)) { adminMode = true; closePasswordDialog() }
                else passwordError = "Incorrect password."
            }) { Text(if (creating) "Save password" else "Unlock") } },
            dismissButton = { TextButton(onClick = { closePasswordDialog() }) { Text("Cancel") } })
    }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = {
                Text(
                    "Version ${com.fbint.collector.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = {},
            enabled = false,
        )
        DropdownMenuItem(
            text = { Text("Check for update") },
            onClick = { expanded = false; onCheckUpdate() },
        )
        DropdownMenuItem(
            text = { Text("Change surveyor name") },
            onClick = { expanded = false; nav.navigate(Routes.SURVEYOR_ID) },
        )
        DropdownMenuItem(
            text = { Text("Download app QR") },
            onClick = { expanded = false; nav.navigate(Routes.DOWNLOAD_QR) },
        )
        DropdownMenuItem(
            text = { Text("Share setup QR") },
            onClick = { expanded = false; nav.navigate(Routes.ADMIN_QR) },
        )
        DropdownMenuItem(
            text = { Text(if (adminMode) "Lock admin controls" else "Admin controls…") },
            onClick = { expanded = false; if (adminMode) adminMode = false else unlock = true },
        )
        if (adminMode) {
        DropdownMenuItem(text = { Text("Change admin password") },
            onClick = { expanded = false; changingPassword = true; unlock = true })
        if (pending > 0) DropdownMenuItem(text = { Text("Sync pending responses before changing setup") }, onClick = {}, enabled = false)
        DropdownMenuItem(
            text = { Text("Scan setup QR") },
            enabled = pending == 0,
            onClick = { expanded = false; nav.navigate(Routes.SURVEYOR_SCAN) },
        )
        DropdownMenuItem(
            text = { Text("Re-enter API key") },
            enabled = pending == 0,
            onClick = { expanded = false; nav.navigate(Routes.ADMIN_SETUP) },
        )
        DropdownMenuItem(
            text = { Text("Reset device…") },
            enabled = pending == 0,
            onClick = { expanded = false; confirmReset = true },
        )
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset device?") },
            text = { Text("This clears the API key, workspace, admin password, surveyor identity, and saved drafts. Synced response history stays on this device. You'll need to scan the QR or re-run admin setup.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                    nav.navigate(Routes.SPLASH) {
                        popUpTo(0) { inclusive = true }
                    }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QueueBanner(
    pending: Int,
    synced: Int,
    struggling: Int,
    online: Boolean,
    onSyncNow: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(online = online)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (online) "Online" else "Offline",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Queue: $pending pending  •  $synced synced", style = MaterialTheme.typography.bodyMedium)
                if (struggling > 0) {
                    Text("$struggling struggling — tap sync to retry", style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onSyncNow) {
                Icon(Icons.Filled.CloudSync, contentDescription = "Sync now")
            }
        }
    }
}

@Composable
private fun UpdateDialog(
    state: com.fbint.collector.ui.surveylist.UpdateUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val info = state.info
    if (state.checking) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Checking for update…") },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
        return
    }
    if (state.errorMessage != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update check failed") },
            text = { Text(state.errorMessage) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        return
    }
    if (state.message != null && info == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Up to date") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        return
    }
    if (info != null) {
        AlertDialog(
            onDismissRequest = { if (!state.downloading) onDismiss() },
            title = { Text("Update available") },
            text = {
                Column {
                    Text("Installed: v${info.installedVersion}")
                    Text("Latest: v${info.latestVersion}")
                    val sizeMb = info.sizeBytes / 1024 / 1024
                    Text("Download size: ${sizeMb} MB")
                    if (state.downloading) {
                        Spacer(Modifier.size(8.dp))
                        if (state.downloadProgress >= 0) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { state.downloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("${state.downloadProgress}%")
                        } else {
                            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (state.message != null) {
                        Spacer(Modifier.size(8.dp))
                        Text(state.message)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm, enabled = !state.downloading) {
                    Text(if (state.downloading) "Downloading…" else "Update")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, enabled = !state.downloading) { Text("Later") }
            },
        )
    }
}

@Composable
private fun StatusDot(online: Boolean) {
    val color = if (online) androidx.compose.ui.graphics.Color(0xFF16A34A) else androidx.compose.ui.graphics.Color(0xFF9CA3AF)
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, androidx.compose.foundation.shape.CircleShape),
    )
}

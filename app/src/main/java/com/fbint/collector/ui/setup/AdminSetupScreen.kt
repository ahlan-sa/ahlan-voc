package com.fbint.collector.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.fbint.collector.ui.nav.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminSetupScreen(
    nav: NavHostController,
    vm: AdminSetupViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Admin setup") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
        ) {
            Text("Enter your Formbricks server, API key, and Workspace ID. We'll check that the key can access that workspace. Legacy Environment IDs also work.")
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = vm::onBaseUrlChange,
                label = { Text("Base URL") },
                placeholder = { Text("https://ksa.formbricks.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = vm::onApiKeyChange,
                label = { Text("API key (x-api-key)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.workspaceId,
                onValueChange = vm::onWorkspaceIdChange,
                label = { Text("Workspace ID") },
                placeholder = { Text("Copy from your workspace's connection settings") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(state.errorMessage!!)
            }
            if (state.successProjectName != null) {
                Spacer(Modifier.height(12.dp))
                Text("Connected to workspace: ${state.successProjectName}")
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { vm.validate { nav.navigate(Routes.ADMIN_QR) } },
                enabled = state.canSubmit && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) CircularProgressIndicator(strokeWidth = 2.dp)
                else Text("Validate & continue")
            }
        }
    }
}

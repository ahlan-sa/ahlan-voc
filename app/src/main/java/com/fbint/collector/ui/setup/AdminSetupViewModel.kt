package com.fbint.collector.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fbint.collector.data.remote.FormbricksApiFactory
import com.fbint.collector.data.repository.ConfigRepository
import com.fbint.collector.data.remote.dto.resolveWorkspace
import kotlinx.coroutines.CancellationException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AdminSetupState(
    val baseUrl: String = "https://ksa.formbricks.com",
    val apiKey: String = "",
    val workspaceId: String = "",
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val successProjectName: String? = null,
) {
    val canSubmit: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && workspaceId.isNotBlank()
}

@HiltViewModel
class AdminSetupViewModel @Inject constructor(
    private val factory: FormbricksApiFactory,
    private val config: ConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AdminSetupState(
        baseUrl = config.baseUrl() ?: "https://ksa.formbricks.com",
        apiKey = config.apiKey().orEmpty(),
        workspaceId = config.workspaceId() ?: config.environmentId().orEmpty(),
    ))
    val state: StateFlow<AdminSetupState> = _state.asStateFlow()

    fun onBaseUrlChange(v: String) = _state.update { it.copy(baseUrl = v, errorMessage = null) }
    fun onApiKeyChange(v: String) = _state.update { it.copy(apiKey = v, errorMessage = null) }
    fun onWorkspaceIdChange(v: String) = _state.update { it.copy(workspaceId = v, errorMessage = null) }

    fun validate(onSuccess: () -> Unit) {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(busy = true, errorMessage = null, successProjectName = null) }
        viewModelScope.launch {
            try {
                val api = factory.management { s.baseUrl.trim() }
                val connection = api.me(s.apiKey.trim()).resolveWorkspace(s.workspaceId)
                val projectName = connection.name
                config.saveServerConfig(s.baseUrl, s.apiKey, connection.environmentId, projectName, connection.workspaceId)
                _state.update { it.copy(busy = false, successProjectName = projectName) }
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, errorMessage = t.message ?: "Validation failed") }
            }
        }
    }
}

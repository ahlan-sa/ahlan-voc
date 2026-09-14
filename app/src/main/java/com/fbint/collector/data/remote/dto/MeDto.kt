package com.fbint.collector.data.remote.dto

import com.squareup.moshi.JsonClass

/**
 * Note: unlike most management endpoints, /me does NOT wrap its response in `{data: ...}`.
 * Verified live against ksa.formbricks.com 2026-05-01 — response is the raw user object.
 * v5 returns workspace plus the project alias, and a legacy environment ID in id.
 * Older servers return project without workspace.
 */
@JsonClass(generateAdapter = true)
data class MeDto(
    val id: String,
    val project: ProjectDto? = null,
    val type: String? = null,
    val appSetupCompleted: Boolean? = null,
    val workspace: ProjectDto? = null,
)

data class WorkspaceConnection(
    val workspaceId: String?,
    val environmentId: String,
    val name: String,
)

/** v5 exposes a workspace plus a legacy v1 ID; older servers expose only a project. */
fun MeDto.resolveWorkspace(enteredId: String): WorkspaceConnection {
    val identifier = enteredId.trim()
    val owner = workspace ?: project
    require(identifier.isNotBlank() && (identifier == id || identifier == owner?.id)) {
        "This API key does not match the Workspace ID. Copy the Workspace ID from the workspace's connection settings, or use its legacy Environment ID."
    }
    return WorkspaceConnection(workspace?.id, id, owner?.name ?: "Formbricks workspace")
}

@JsonClass(generateAdapter = true)
data class ProjectDto(
    val id: String,
    val name: String,
)

package com.fbint.collector.domain

import com.fbint.collector.data.remote.dto.SurveyDto

/** Administrator-controlled initial value in Formbricks' survey Variables section. */
const val SHOW_IN_APP_VARIABLE = "show_in_app"

fun SurveyDto.isVisibleInApp(): Boolean {
    if (!status.equals("inProgress", ignoreCase = true)) return false
    val setting = variables.singleOrNull { it.name == SHOW_IN_APP_VARIABLE } ?: return false
    return setting.type == "text" && (setting.value as? String)?.trim().equals("YES", ignoreCase = true)
}

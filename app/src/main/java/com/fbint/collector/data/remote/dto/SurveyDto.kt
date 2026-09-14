package com.fbint.collector.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SurveyListEnvelope(val data: List<SurveyDto>)

@JsonClass(generateAdapter = true)
data class SurveyEnvelope(val data: SurveyDto)

/**
 * Mirrors the Formbricks v1 Management API survey shape. Optional fields use defaults so
 * partial responses don't crash deserialization. Formbricks 5.4.2 derives questions from
 * blocks in its v1 compatibility API, including conversion of block jump targets.
 */
@JsonClass(generateAdapter = true)
data class SurveyDto(
    val id: String,
    val name: String,
    val type: String? = null,
    val status: String? = null,
    val environmentId: String,
    val workspaceId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val questions: List<QuestionDto> = emptyList(),
    val welcomeCard: WelcomeCardDto? = null,
    val endings: List<EndingDto> = emptyList(),
    val languages: List<LanguageDto> = emptyList(),
    val showLanguageSwitch: Boolean? = null,
    val hiddenFields: HiddenFieldsDto? = null,
    val variables: List<VariableDto> = emptyList(),
    val isBackButtonHidden: Boolean? = null,
    val styling: StylingDto? = null,
)

@JsonClass(generateAdapter = true)
data class StylingDto(
    val roundness: Int? = null,
    val background: BackgroundDto? = null,
    val brandColor: ColorTokenDto? = null,
    val questionColor: ColorTokenDto? = null,
    val inputColor: ColorTokenDto? = null,
    val inputTextColor: ColorTokenDto? = null,
    val buttonBgColor: ColorTokenDto? = null,
    val buttonTextColor: ColorTokenDto? = null,
    val buttonBorderColor: ColorTokenDto? = null,
    val optionBgColor: ColorTokenDto? = null,
    val cardBgColor: ColorTokenDto? = null,
    val cardBorderColor: ColorTokenDto? = null,
    val isLogoHidden: Boolean? = null,
)

@JsonClass(generateAdapter = true)
data class BackgroundDto(
    val bg: String? = null,
    val bgType: String? = null,
    val brightness: Int? = null,
)

@JsonClass(generateAdapter = true)
data class ColorTokenDto(
    val light: String? = null,
    val dark: String? = null,
)

@JsonClass(generateAdapter = true)
data class WelcomeCardDto(
    val enabled: Boolean = false,
    val headline: Map<String, String>? = null,
    val html: Map<String, String>? = null,
    val buttonLabel: Map<String, String>? = null,
    val fileUrl: String? = null,
)

/**
 * Endings come in two shapes — endScreen (terminal "thank you" card) and redirectToUrl (open
 * a URL). The runner queues redirects until network is available.
 */
@JsonClass(generateAdapter = true)
data class EndingDto(
    val id: String,
    val type: String? = null,
    val headline: Map<String, String>? = null,
    val subheader: Map<String, String>? = null,
    val buttonLabel: Map<String, String>? = null,
    val buttonLink: String? = null,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val url: String? = null,
    val label: String? = null,
)

@JsonClass(generateAdapter = true)
data class LanguageDto(
    val language: LanguageCodeDto? = null,
    val default: Boolean = false,
    val enabled: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class LanguageCodeDto(
    val id: String? = null,
    val code: String,
    val alias: String? = null,
)

@JsonClass(generateAdapter = true)
data class HiddenFieldsDto(
    val enabled: Boolean = false,
    val fieldIds: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class VariableDto(
    val id: String,
    val name: String,
    val type: String,             // "number" | "text"
    val value: Any? = null,
)

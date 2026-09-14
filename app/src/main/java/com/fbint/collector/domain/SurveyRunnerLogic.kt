package com.fbint.collector.domain

import com.fbint.collector.data.remote.dto.QuestionDto
import com.fbint.collector.data.remote.dto.SurveyDto

/** Question-type discriminators emitted by Formbricks v1. */
object QType {
    const val OPEN_TEXT = "openText"
    const val CHOICE_SINGLE = "multipleChoiceSingle"
    const val CHOICE_MULTI = "multipleChoiceMulti"
    const val RATING = "rating"
    const val CSAT = "csat"
    const val CES = "ces"
    const val NPS = "nps"
    const val CTA = "cta"
    const val CONSENT = "consent"
    const val PICTURE_SELECTION = "pictureSelection"
    const val DATE = "date"
    const val FILE_UPLOAD = "fileUpload"
    const val CAL = "cal"
    const val MATRIX = "matrix"
    const val ADDRESS = "address"
    const val CONTACT_INFO = "contactInfo"
    const val RANKING = "ranking"

    val supported = setOf(OPEN_TEXT, CHOICE_SINGLE, CHOICE_MULTI, RATING, CSAT, CES,
        NPS, CTA, CONSENT, PICTURE_SELECTION, DATE, FILE_UPLOAD, CAL, MATRIX,
        ADDRESS, CONTACT_INFO, RANKING)
}

/**
 * Friendly display name + i18n-map lookup key for a survey language. Formbricks stores the
 * default-language translation under the magic key `"default"`, not under the language code,
 * so the default option's lookupKey is `"default"` even though we display the human alias.
 *
 * `isRtl` flips the runner's layout direction when this language is active.
 */
data class LanguageOption(val displayName: String, val lookupKey: String, val isRtl: Boolean = false)

fun SurveyDto.defaultLanguageCode(): String = "default"

fun SurveyDto.languageOptions(): List<LanguageOption> {
    if (languages.isEmpty()) return listOf(LanguageOption("default", "default"))
    val opts = mutableListOf<LanguageOption>()
    val defaultLang = languages.firstOrNull { it.default && it.enabled }
    if (defaultLang != null) {
        val display = defaultLang.language?.let { it.alias ?: it.code } ?: "default"
        opts += LanguageOption(
            displayName = display,
            lookupKey = "default",
            isRtl = defaultLang.language?.code.isRtlCode(),
        )
    }
    languages.filter { it.enabled && !it.default }.forEach { l ->
        val lang = l.language ?: return@forEach
        val display = lang.alias ?: lang.code
        opts += LanguageOption(
            displayName = display,
            lookupKey = lang.code,
            isRtl = lang.code.isRtlCode(),
        )
    }
    return opts.ifEmpty { listOf(LanguageOption("default", "default")) }
}

/** ISO 639 codes for RTL scripts. Matches "ar", "ar-SA", "he", "fa-IR", etc. */
private val rtlPrefixes = setOf("ar", "he", "fa", "ur", "ps", "sd", "yi", "iw")
private fun String?.isRtlCode(): Boolean {
    if (this.isNullOrBlank()) return false
    val prefix = substringBefore('-').lowercase()
    return prefix in rtlPrefixes
}

/**
 * Pull a translation out of a Formbricks i18n map. The Formbricks rich-text editor wraps
 * even plain text in HTML (`<p class="fb-editor-paragraph">…</p>`), so we strip tags and
 * decode common entities before returning. For richer rendering (bold/italic preserved) we'd
 * need an AnnotatedString converter — out of scope for v1.
 */
fun Map<String, String>?.localized(lang: String): String {
    val raw = this?.get(lang) ?: this?.get("default") ?: this?.values?.firstOrNull().orEmpty()
    return raw.stripHtml().trim()
}

private val tagRegex = Regex("<[^>]+>")
private val whitespaceRegex = Regex("\\s+")

private fun String.stripHtml(): String {
    if (!contains('<')) return this
    return tagRegex.replace(this, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(whitespaceRegex, " ")
}

/**
 * True if the answer satisfies the question's `required` constraint. Branching logic in
 * Formbricks can dynamically toggle this per session via `requireAnswer` actions, but at
 * v1 we apply only the static `required` flag — `requireAnswer` is unusual in field
 * collection workflows and the safe default is "respect the static schema."
 */
fun QuestionDto.isAnswerValid(answer: Any?): Boolean {
    if (!required) return true
    return when (type) {
        QType.OPEN_TEXT -> (answer as? String)?.isNotBlank() == true
        QType.CHOICE_SINGLE -> (answer as? String)?.isNotBlank() == true
        QType.CHOICE_MULTI -> (answer as? List<*>)?.isNotEmpty() == true
        QType.RATING, QType.CSAT, QType.CES -> {
            val score = (answer as? Number)?.toDouble() ?: return false
            score % 1.0 == 0.0 && score >= 1 && score <= (range ?: 5)
        }
        QType.NPS -> {
            val score = (answer as? Number)?.toDouble() ?: return false
            score % 1.0 == 0.0 && score in 0.0..10.0
        }
        QType.CTA -> answer == "clicked"
        QType.CONSENT -> answer == "accepted"
        QType.PICTURE_SELECTION -> (answer as? List<*>)?.isNotEmpty() == true
        QType.DATE -> (answer as? String)?.isNotBlank() == true
        QType.FILE_UPLOAD -> (answer as? List<*>)?.isNotEmpty() == true
        QType.CAL -> answer == "booked"
        QType.MATRIX -> validateMatrix(answer)
        QType.ADDRESS -> validateAddress(answer)
        QType.CONTACT_INFO -> validateContactInfo(answer)
        QType.RANKING -> {
            val list = (answer as? List<*>) ?: return false
            val total = choices?.size ?: 0
            list.size == total && list.all { (it as? String)?.isNotBlank() == true }
        }
        else -> answer != null
    }
}

private fun QuestionDto.validateMatrix(answer: Any?): Boolean {
    val map = (answer as? Map<*, *>) ?: return false
    val rowCount = rows?.size ?: 0
    if (rowCount == 0) return true
    return map.size >= rowCount && map.values.all { (it as? String)?.isNotBlank() == true }
}

/**
 * Address answers are positional `string[]` of length 6. Required sub-fields must be filled;
 * unused slots may be empty strings.
 */
private fun QuestionDto.validateAddress(answer: Any?): Boolean {
    val list = (answer as? List<*>) ?: return false
    val configs = listOf(addressLine1, addressLine2, city, state, zip, country)
    return configs.withIndex().all { (i, cfg) ->
        if (cfg?.show != true || cfg.required != true) true
        else (list.getOrNull(i) as? String)?.isNotBlank() == true
    }
}

private fun QuestionDto.validateContactInfo(answer: Any?): Boolean {
    val list = (answer as? List<*>) ?: return false
    val configs = listOf(firstName, lastName, email, phone, company)
    return configs.withIndex().all { (i, cfg) ->
        if (cfg?.show != true || cfg.required != true) true
        else (list.getOrNull(i) as? String)?.isNotBlank() == true
    }
}

/** Initial values for a freshly opened question. Lets the runner show pre-populated UI. */
fun QuestionDto.initialAnswer(): Any? = when (type) {
    QType.CHOICE_MULTI, QType.PICTURE_SELECTION, QType.RANKING, QType.FILE_UPLOAD -> emptyList<String>()
    QType.MATRIX -> emptyMap<String, String>()
    QType.ADDRESS -> List(6) { "" }
    QType.CONTACT_INFO -> List(5) { "" }
    else -> null
}

package com.fbint.collector.ui.runner.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fbint.collector.data.remote.dto.QuestionDto
import com.fbint.collector.data.remote.dto.ChoiceDto
import com.fbint.collector.domain.localized

private const val OTHER_CHOICE_ID = "other"

private fun ChoiceDto.matches(answer: String): Boolean = id != OTHER_CHOICE_ID &&
    (answer == id || label.orEmpty().keys.any { answer == label.localized(it) })

/**
 * Single-choice. Stored value is the chosen choice's localized **label** (Formbricks server
 * convention). For an "other" choice, the user's free-text replaces the label.
 */
@Composable
fun ChoiceSingleQuestion(
    question: QuestionDto,
    lang: String,
    answer: String?,
    onAnswer: (Any?) -> Unit,
) {
    val choices = question.choices.orEmpty()
    var otherText by rememberSaveable(question.id) {
        mutableStateOf(answer?.takeIf { value -> choices.none { it.matches(value) } }.orEmpty())
    }
    choices.forEach { choice ->
        val choiceLabel = choice.label.localized(lang)
        val isOther = choice.id == OTHER_CHOICE_ID
        val selected = if (isOther) answer != null && choices.none { it.matches(answer) }
            else answer != null && choice.matches(answer)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .selectable(
                    selected = selected,
                    onClick = {
                        if (isOther) onAnswer(otherText.takeIf { it.isNotBlank() } ?: "")
                        else onAnswer(choiceLabel)
                    },
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(choiceLabel)
            }
            if (isOther && selected) {
                OutlinedTextField(
                    value = otherText,
                    onValueChange = {
                        otherText = it
                        onAnswer(it)
                    },
                    label = { Text(question.otherOptionPlaceholder.localized(lang).ifBlank { "Please specify" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Multi-choice. Stored value is `string[]` of localized labels. */
@Composable
fun ChoiceMultiQuestion(
    question: QuestionDto,
    lang: String,
    answer: List<String>?,
    onAnswer: (Any?) -> Unit,
) {
    val choices = question.choices.orEmpty()
    val current = answer.orEmpty()
    var otherText by rememberSaveable(question.id) {
        mutableStateOf(current.firstOrNull { c -> c.isNotBlank() && choices.none { it.matches(c) } } ?: "")
    }
    choices.forEach { choice ->
        val choiceLabel = choice.label.localized(lang)
        val isOther = choice.id == OTHER_CHOICE_ID
        val selected = if (isOther) current.any { c -> choices.none { it.matches(c) } }
            else current.any { choice.matches(it) }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .toggleable(
                    value = selected,
                    onValueChange = { isOn ->
                        val withoutOther = current.filter { c -> choices.any { it.matches(c) } }
                        val next = when {
                            isOther && isOn -> withoutOther + listOf("", otherText)
                            isOther && !isOn -> withoutOther
                            isOn -> current + choiceLabel
                            else -> current.filterNot { choice.matches(it) }
                        }
                        onAnswer(next)
                    },
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = selected, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(choiceLabel)
            }
            if (isOther && selected) {
                OutlinedTextField(
                    value = otherText,
                    onValueChange = { value ->
                        otherText = value
                        val withoutOther = current.filter { c -> choices.any { it.matches(c) } }
                        onAnswer(withoutOther + listOf("", value))
                    },
                    label = { Text(question.otherOptionPlaceholder.localized(lang).ifBlank { "Please specify" }) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

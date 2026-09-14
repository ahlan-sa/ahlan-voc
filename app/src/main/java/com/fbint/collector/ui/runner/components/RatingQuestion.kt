package com.fbint.collector.ui.runner.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fbint.collector.data.remote.dto.QuestionDto
import com.fbint.collector.domain.localized

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RatingQuestion(
    question: QuestionDto,
    lang: String,
    answer: Int?,
    onAnswer: (Any?) -> Unit,
) {
    val range = (question.range ?: 5).coerceIn(2, 10)
    Column {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (1..range).forEach { value ->
                FilterChip(
                    selected = answer == value,
                    onClick = { onAnswer(value) },
                    label = { Text(value.toString()) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(question.lowerLabel.localized(lang), style = MaterialTheme.typography.bodySmall)
            Text(question.upperLabel.localized(lang), style = MaterialTheme.typography.bodySmall)
        }
    }
}

package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.PredictionResult

/**
 * A horizontal bar showing word and letter predictions.
 * Word predictions are shown as chips, letter predictions as small character buttons.
 */
@Composable
fun PredictionBar(
    predictions: PredictionResult,
    onWordSelected: (String) -> Unit,
    onLetterSelected: (Char) -> Unit,
    fontSizeScale: Float = 1f,
    modifier: Modifier = Modifier
) {
    if (predictions.words.isEmpty() && predictions.letters.isEmpty()) {
        return
    }
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Word predictions as chips
        predictions.words.forEach { word ->
            SuggestionChip(
                onClick = { onWordSelected(word) },
                label = {
                    Text(
                        text = word,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontSizeScale
                        )
                    )
                },
                shape = RoundedCornerShape(16.dp),
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            )
        }
        
        // Separator if both words and letters are present
        if (predictions.words.isNotEmpty() && predictions.letters.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Divider(
                modifier = Modifier
                    .height(24.dp)
                    .width(1.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        
        // Letter predictions as small buttons
        predictions.letters.forEach { letter ->
            Surface(
                modifier = Modifier
                    .size((32.dp * fontSizeScale).coerceAtLeast(28.dp))
                    .clickable { onLetterSelected(letter) },
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = letter.toString(),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * fontSizeScale,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

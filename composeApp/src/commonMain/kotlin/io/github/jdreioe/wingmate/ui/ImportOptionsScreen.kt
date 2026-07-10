package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.import_options_classic_desc
import wingmatekmp.composeapp.generated.resources.import_options_classic_title
import wingmatekmp.composeapp.generated.resources.import_options_modern_desc
import wingmatekmp.composeapp.generated.resources.import_options_modern_title
import wingmatekmp.composeapp.generated.resources.import_options_recommended
import wingmatekmp.composeapp.generated.resources.import_options_scratch_desc
import wingmatekmp.composeapp.generated.resources.import_options_scratch_title
import wingmatekmp.composeapp.generated.resources.import_options_skip
import wingmatekmp.composeapp.generated.resources.import_options_subtitle
import wingmatekmp.composeapp.generated.resources.import_options_title

@Composable
fun ImportOptionsScreen(
    onImportClassic: () -> Unit,
    onImportModern: () -> Unit,
    onCreateFromScratch: () -> Unit,
    onSkip: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(Res.string.import_options_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(Res.string.import_options_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            ImportOptionCard(
                title = stringResource(Res.string.import_options_classic_title),
                description = stringResource(Res.string.import_options_classic_desc),
                onClick = onImportClassic
            )

            Spacer(modifier = Modifier.height(16.dp))

            ImportOptionCard(
                title = stringResource(Res.string.import_options_modern_title),
                description = stringResource(Res.string.import_options_modern_desc),
                onClick = onImportModern,
                isRecommended = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            ImportOptionCard(
                title = stringResource(Res.string.import_options_scratch_title),
                description = stringResource(Res.string.import_options_scratch_desc),
                onClick = onCreateFromScratch
            )

            Spacer(modifier = Modifier.height(32.dp))

            TextButton(onClick = onSkip) {
                Text(stringResource(Res.string.import_options_skip))
            }
        }
    }
}

@Composable
fun ImportOptionCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    isRecommended: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRecommended) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isRecommended) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isRecommended) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            stringResource(Res.string.import_options_recommended),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isRecommended) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}

package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.CommunicationAction
import io.github.jdreioe.wingmate.domain.CommunicationFailureKind
import io.github.jdreioe.wingmate.domain.CommunicationPersistenceStatus
import io.github.jdreioe.wingmate.domain.CommunicationSessionState

@Composable
internal fun CommunicationSessionStatus(
    state: CommunicationSessionState,
    onAction: (CommunicationAction) -> Unit,
) {
    val persistenceFailed = state.persistenceStatus == CommunicationPersistenceStatus.Failed ||
        state.lastFailure?.kind == CommunicationFailureKind.Persistence
    if (!persistenceFailed && state.lastFailure?.kind != CommunicationFailureKind.Playback) return

    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(stringResource(
                if (persistenceFailed) R.string.communication_storage_failed
                else R.string.communication_playback_failed,
            ))
            Button(
                enabled = !persistenceFailed || state.persistenceStatus != CommunicationPersistenceStatus.Saving,
                onClick = {
                    onAction(
                        if (persistenceFailed) CommunicationAction.RetryPersistence
                        else CommunicationAction.DismissFailure,
                    )
                },
            ) {
                Text(stringResource(if (persistenceFailed) R.string.common_retry else R.string.update_dismiss))
            }
        }
    }
}

@Preview
@Composable
private fun CommunicationSessionStatusPreview() {
    AppTheme {
        CommunicationSessionStatus(
            state = CommunicationSessionState(persistenceStatus = CommunicationPersistenceStatus.Failed),
            onAction = {},
        )
    }
}

package dev.lumen.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.lumen.companion.BandClaim
import dev.lumen.companion.R

/**
 * Generating the band's key with a Meta account ([BandClaim]): the button, the warning before
 * it (the band leaves Meta's app and glasses), and how it's going.
 */
@Composable
internal fun ClaimSection(state: CompanionUiState, actions: CompanionActions) {
    var confirm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
        when (val claim = state.claim) {
            is BandClaim.State.Running -> {
                StepLine(StepState.NOW, claim.step, stringResource(R.string.claim_pairing_hint))
                PillButton(stringResource(R.string.action_cancel), primary = false, modifier = Modifier.fillMaxWidth()) { actions.cancelClaim() }
            }
            BandClaim.State.Claimed -> StepLine(StepState.DONE, stringResource(R.string.claim_done), null)
            is BandClaim.State.DryRunDone -> StepLine(StepState.DONE, stringResource(R.string.claim_dry_run_done, claim.serial), null)
            is BandClaim.State.Failed -> StepLine(StepState.TODO, stringResource(R.string.claim_failed, claim.message), null)
            BandClaim.State.Idle -> Unit
        }
        if (state.claim !is BandClaim.State.Running) {
            PillButton(stringResource(R.string.claim_button), primary = !state.bandKeyPresent, icon = LumenIcons.band, modifier = Modifier.fillMaxWidth()) { confirm = true }
            if (state.debuggable) {
                PillButton(stringResource(R.string.claim_dry_run), primary = false, modifier = Modifier.fillMaxWidth()) { actions.claimWithMeta(dryRun = true) }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Lumen.elevation1,
            title = { Text(stringResource(R.string.claim_confirm_title)) },
            text = { Text(stringResource(R.string.claim_confirm_text), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { confirm = false; actions.claimWithMeta(dryRun = false) }) {
                    Text(stringResource(R.string.claim_confirm_go), color = Lumen.negative)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
        )
    }
}

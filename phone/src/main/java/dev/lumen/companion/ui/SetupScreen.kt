package dev.lumen.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lumen.companion.GlassesSetup
import dev.lumen.companion.R
import dev.lumen.companion.update.UpdateManager.Step
import kotlinx.coroutines.delay

/** The home's call to finish setting up the glasses, until the glasses app answers. */
@Composable
internal fun SetupCard(setup: GlassesSetup.State, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Lumen.radiusCard))
            .background(Lumen.surface)
            .border(BorderStroke(1.5.dp, Lumen.accent), RoundedCornerShape(Lumen.radiusCard))
            .clickable(onClick = onOpen)
            .padding(Lumen.spacingMedLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Lumen.accent), contentAlignment = Alignment.Center) {
            Icon(LumenIcons.glasses, contentDescription = null, modifier = Modifier.size(24.dp), tint = Lumen.textPrimary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.setup_card_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.setup_card_subtitle, setup.stepsDone, GlassesSetup.STEPS), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
        }
        Icon(LumenIcons.chevron, contentDescription = null, modifier = Modifier.size(20.dp), tint = Lumen.textSecondary)
    }
}

/**
 * Setting up the glasses from the phone, no computer: authorize in Hi Rokid, the phone's Wi-Fi
 * on, Lumen installed on the glasses through Rokid's link, and its accessibility switch, which
 * the wearer turns on on the glasses (it opens there by itself).
 */
@Composable
internal fun SetupPage(state: CompanionUiState, actions: CompanionActions, onBack: () -> Unit) {
    val setup = state.setup
    PageHeader(stringResource(R.string.setup_title), onBack)
    LaunchedEffect(Unit) { actions.checkSetup() }
    // While the glasses haven't answered: ask again every few seconds (the switch goes on there).
    LaunchedEffect(setup.done, setup.linkReady) {
        while (!setup.done && setup.linkReady) {
            actions.checkSetup()
            delay(5_000)
        }
    }
    Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium, color = Lumen.textSecondary)

    // 1. Hi Rokid
    val authorized = setup.authorized && setup.linkReady
    SetupStep(1, authorized, stringResource(R.string.setup_step_auth), stringResource(if (authorized) R.string.setup_step_auth_done else R.string.setup_step_auth_hint)) {
        if (!setup.authorized) PillButton(stringResource(R.string.action_authorize), primary = true, icon = LumenIcons.link) { actions.authorize() }
        else if (!setup.linkReady) PillButton(stringResource(R.string.action_reconnect), primary = false, icon = LumenIcons.refresh) { actions.reconnect() }
    }

    // 2. The phone's Wi-Fi
    SetupStep(2, setup.wifiOn, stringResource(R.string.setup_step_wifi), stringResource(R.string.setup_step_wifi_hint)) {
        if (!setup.wifiOn) PillButton(stringResource(R.string.update_open_wifi), primary = true, icon = LumenIcons.wifi) { actions.openWifiSettings() }
    }

    // 3. Lumen on the glasses
    val installed = setup.installed == true || setup.responding
    val step = state.update.glassesStep
    SetupStep(3, installed, stringResource(R.string.setup_step_install), stringResource(if (installed) R.string.setup_step_install_done else R.string.setup_step_install_hint)) {
        when {
            installed -> Unit
            step is Step.Downloading -> StepLine(StepState.NOW, stringResource(R.string.progress_download), stringResource(R.string.progress_bytes, formatSize(step.done), formatSize(step.total)))
            step == Step.Waiting -> StepLine(StepState.NOW, stringResource(R.string.progress_waiting), null)
            step == Step.Sending -> StepLine(StepState.NOW, stringResource(R.string.progress_sending), stringResource(R.string.progress_sending_hint))
            step is Step.Failed -> ProblemLine(step.problem, actions, onRetry = { actions.installGlasses() })
            else -> PillButton(
                stringResource(R.string.setup_install),
                primary = true, icon = LumenIcons.download, enabled = authorized && setup.wifiOn,
            ) { actions.installGlasses() }
        }
    }

    // 4. The accessibility switch, on the glasses
    SetupStep(4, setup.responding, stringResource(R.string.setup_step_access), stringResource(if (setup.responding) R.string.setup_step_access_done else R.string.setup_step_access_hint)) {
        if (installed && !setup.responding) {
            PillButton(stringResource(R.string.setup_open_on_glasses), primary = false, icon = LumenIcons.glasses) { actions.openSetupOnGlasses() }
        }
    }

    // 5. The self-arm, run on the glasses from here
    SetupStep(5, setup.armed, stringResource(R.string.setup_step_arm), stringResource(if (setup.armed) R.string.setup_step_arm_done else R.string.setup_step_arm_hint)) {
        if (setup.responding) {
            setup.selfArm?.message?.takeIf { it.isNotBlank() }?.let {
                val hint = when (setup.selfArm.state) {
                    "usb_debugging_off" -> stringResource(R.string.setup_arm_hint_usb)
                    "wifi_enable_timeout", "wireless_setup_timeout" -> stringResource(R.string.setup_arm_hint_wifi)
                    else -> null
                }
                StepLine(if (setup.arming) StepState.NOW else StepState.TODO, it, hint)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
                PillButton(stringResource(R.string.setup_open_hi_rokid), primary = false, modifier = Modifier.weight(1f)) { actions.openHiRokid() }
                PillButton(stringResource(R.string.setup_arm), primary = true, enabled = !setup.arming, modifier = Modifier.weight(1f)) { actions.prepareGlasses() }
            }
        }
    }

    // 6. The band's key, from this phone to the glasses
    SetupStep(6, setup.keyDone, stringResource(R.string.setup_step_key), stringResource(if (setup.keyDone) R.string.setup_step_key_done else R.string.setup_step_key_hint)) {
        if (setup.responding) {
            setup.keyResult?.takeIf { !it.ok }?.let { StepLine(StepState.TODO, stringResource(R.string.setup_key_refused, it.error), null) }
            if (setup.phoneHasKey) {
                PillButton(stringResource(R.string.setup_send_key), primary = true, icon = LumenIcons.band) { actions.sendBandKey() }
            } else {
                PillButton(stringResource(R.string.setup_import_key), primary = true, icon = LumenIcons.band) { actions.importBandKey() }
            }
        }
    }

    if (setup.done) {
        Text(stringResource(R.string.setup_done), style = MaterialTheme.typography.bodyMedium, color = Lumen.textSecondary)
        PillButton(stringResource(R.string.action_done), primary = true, modifier = Modifier.fillMaxWidth(), onClick = onBack)
    }
}

@Composable
private fun SetupStep(number: Int, done: Boolean, title: String, text: String, content: @Composable () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed)) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (done) Lumen.positive else Lumen.elevation1),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(LumenIcons.check, contentDescription = null, modifier = Modifier.size(18.dp), tint = Lumen.textPrimary)
                else Text(number.toString(), style = MaterialTheme.typography.titleSmall, color = Lumen.textPrimary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
            }
        }
        if (!done) content()
    }
}

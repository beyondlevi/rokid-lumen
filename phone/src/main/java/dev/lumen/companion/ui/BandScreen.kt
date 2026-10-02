package dev.lumen.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.lumen.companion.R
import dev.lumen.protocol.BandStatus
import dev.lumen.protocol.Setting
import dev.lumen.protocol.SettingsAction
import dev.lumen.protocol.SettingsEvent

/** The band's settings, drawn from the schema the glasses sent ([SettingsEvent.Schema]). */
@Composable
internal fun BandScreen(state: CompanionUiState, actions: CompanionActions) {
    val schema = state.bandSchema
    val error = state.bandError
    var choosing by remember { mutableStateOf<Setting?>(null) }
    var confirming by remember { mutableStateOf<SettingsAction?>(null) }

    Header(stringResource(R.string.band_title), stringResource(R.string.band_subtitle))
    BandDeviceCard(state, actions)
    BandStatusCard(if (state.bandOnPhone) state.phoneBandStatus else state.bandStatus)
    PhoneGestures(state, actions) { choosing = it }
    if (schema == null) {
        Card {
            Text(stringResource(R.string.band_waiting), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.band_waiting_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
        }
        return
    }
    if (error != null) {
        Text(
            stringResource(R.string.band_refused, error.error),
            style = MaterialTheme.typography.bodySmall,
            color = Lumen.negative,
            modifier = Modifier.padding(horizontal = Lumen.spacingSmall),
        )
    }
    schema.settings.map { it.section }.distinct().forEach { section ->
        val visible = schema.settings.filter { it.section == section && it.isVisible(schema.settings) }
        if (visible.isEmpty()) return@forEach
        SectionTitle(BandLabels.section(section)?.let { stringResource(it) } ?: section)
        Group {
            visible.forEach { setting ->
                val title = BandLabels.setting(setting.key)?.let { stringResource(it) } ?: setting.label
                when (setting.kind) {
                    Setting.Kind.TOGGLE -> SwitchRow(title, null, setting.checked, { actions.setBandSetting(setting.key, it.toString()) })
                    Setting.Kind.CHOICE -> ListRow(title = title, value = optionLabel(setting, setting.value), onClick = { choosing = setting })
                }
            }
        }
    }
    if (schema.actions.isNotEmpty()) {
        Group {
            schema.actions.forEach { action ->
                val label = BandLabels.action(action.name)?.let { stringResource(it) } ?: action.label
                DangerRow(label, action.destructive) { if (action.destructive) confirming = action else actions.bandAction(action.name) }
            }
        }
    }

    choosing?.let { setting ->
        ChoiceDialog(setting, onDismiss = { choosing = null }) { value ->
            choosing = null
            if (setting.key.startsWith(PHONE_PREFIX)) actions.setPhoneSetting(setting.key, value) else actions.setBandSetting(setting.key, value)
        }
    }
    confirming?.let { action ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            containerColor = Lumen.elevation1,
            title = { Text(stringResource(R.string.band_forget_title)) },
            text = { Text(stringResource(R.string.band_forget_text), color = Lumen.textSecondary) },
            confirmButton = {
                TextButton(onClick = { confirming = null; actions.bandAction(action.name) }) {
                    Text(BandLabels.action(action.name)?.let { stringResource(it) } ?: action.label, color = Lumen.negative)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
        )
    }
}

@Composable
private fun optionLabel(setting: Setting, id: String): String {
    if (id.isEmpty()) return stringResource(R.string.band_not_chosen)
    BandLabels.option(setting.key, id)?.let { return stringResource(it) }
    return setting.options.firstOrNull { it.id == id }?.label ?: id
}

private const val PHONE_PREFIX = "phone."

/**
 * What the gestures do on this phone (kept here, not on the glasses), and the two permissions some
 * of the actions need: the accessibility service and "Modify system settings".
 */
@Composable
private fun PhoneGestures(state: CompanionUiState, actions: CompanionActions, choose: (Setting) -> Unit) {
    val visible = state.phoneSettings.filter { it.isVisible(state.phoneSettings) }
    if (visible.isEmpty()) return
    SectionTitle(stringResource(R.string.band_section_phone))
    Group {
        visible.forEach { setting ->
            ListRow(
                title = BandLabels.setting(setting.key)?.let { stringResource(it) } ?: setting.label,
                value = optionLabel(setting, setting.value),
                onClick = { choose(setting) },
            )
        }
    }
    Group {
        ListRow(
            title = stringResource(R.string.phone_touch),
            subtitle = stringResource(R.string.phone_touch_hint),
            trailing = {
                StatusPill(
                    stringResource(if (state.touchEnabled) R.string.phone_touch_on else R.string.phone_touch_off),
                    if (state.touchEnabled) Lumen.positive else Lumen.warning,
                )
            },
            onClick = actions::openTouchSettings,
        )
        ListRow(
            title = stringResource(R.string.phone_brightness),
            subtitle = stringResource(R.string.phone_brightness_hint),
            trailing = {
                StatusPill(
                    stringResource(if (state.writeSettingsGranted) R.string.phone_allowed else R.string.phone_not_allowed),
                    if (state.writeSettingsGranted) Lumen.positive else Lumen.warning,
                )
            },
            onClick = actions::allowWriteSettings,
        )
    }
}

/**
 * Which device the band controls, and the switch: it connects to one at a time. Using it here
 * needs its key (imported from a file) and Bluetooth.
 */
@Composable
private fun BandDeviceCard(state: CompanionUiState, actions: CompanionActions) {
    Card {
        Text(stringResource(R.string.band_device_title), style = MaterialTheme.typography.labelMedium, color = Lumen.textSecondary)
        Text(
            stringResource(if (state.bandOnPhone) R.string.band_device_phone else R.string.band_device_glasses),
            style = MaterialTheme.typography.titleLarge,
        )
        val hint = when {
            !state.bandKeyPresent -> R.string.band_key_missing
            !state.bluetoothGranted -> R.string.band_bluetooth_missing
            else -> R.string.band_device_hint
        }
        Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed), modifier = Modifier.fillMaxWidth()) {
            when {
                !state.bandKeyPresent -> PillButton(stringResource(R.string.band_import_key), primary = true, modifier = Modifier.weight(1f)) { actions.importBandKey() }
                !state.bluetoothGranted -> PillButton(stringResource(R.string.band_allow_bluetooth), primary = true, modifier = Modifier.weight(1f)) { actions.allowBluetooth() }
                state.bandOnPhone -> PillButton(stringResource(R.string.band_use_glasses), primary = true, modifier = Modifier.weight(1f)) { actions.useBandOnGlasses() }
                else -> PillButton(stringResource(R.string.band_use_phone), primary = true, modifier = Modifier.weight(1f)) { actions.useBandOnPhone() }
            }
        }
    }
}

@Composable
internal fun BandStatusCard(status: BandStatus) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Lumen.spacingMedium)) {
            Box(
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(Lumen.radiusRow)).background(Lumen.elevation1),
                contentAlignment = Alignment.Center,
            ) { Icon(LumenIcons.band, contentDescription = null, modifier = Modifier.size(32.dp), tint = Lumen.textPrimary) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(status.name.ifEmpty { stringResource(R.string.band_title) }, style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
                    StatusPill(
                        BandLabels.phase(status.phase)?.let { stringResource(it) } ?: status.phase,
                        if (status.connected) Lumen.positive else Lumen.warning,
                    )
                    if (status.battery >= 0) {
                        val battery = stringResource(R.string.band_battery, status.battery)
                        StatusPill(
                            if (status.charging) "$battery · ${stringResource(R.string.band_charging)}" else battery,
                            if (status.battery <= 20) Lumen.warning else Lumen.positive,
                        )
                    }
                    if (status.paused) StatusPill(stringResource(R.string.band_paused), Lumen.warning)
                }
            }
        }
    }
}

@Composable
private fun DangerRow(label: String, destructive: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(Lumen.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Lumen.spacingMedium, vertical = Lumen.spacingSmMed),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (destructive) Lumen.negative else Lumen.textPrimary)
    }
}

@Composable
private fun ChoiceDialog(setting: Setting, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lumen.elevation1,
        title = { Text(BandLabels.setting(setting.key)?.let { stringResource(it) } ?: setting.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                setting.options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.RadioButton) { onChoose(option.id) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall),
                    ) {
                        RadioButton(
                            selected = option.id == setting.value,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = Lumen.accent, unselectedColor = Lumen.textPlaceholder),
                        )
                        Text(optionLabel(setting, option.id), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
    )
}

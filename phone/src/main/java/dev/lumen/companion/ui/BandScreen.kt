package dev.lumen.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lumen.companion.PhoneProfiles
import dev.lumen.companion.PhoneSettings
import dev.lumen.companion.R
import dev.lumen.protocol.BandStatus
import dev.lumen.protocol.Setting
import dev.lumen.protocol.SettingsAction

/**
 * The band: where it is (and the switch: it talks to one device at a time), then each device's
 * own settings, apart. The phone's are its gesture profiles, pinch and turn and the locked phone,
 * plus the keyboard and the permissions some actions need; the glasses' come from the schema they
 * send. The key has its own page ([BandKeyPage]); a profile's name and copies, too ([ProfilePage]).
 */
@Composable
internal fun BandScreen(state: CompanionUiState, actions: CompanionActions, onProfile: (String) -> Unit, onKey: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(if (state.bandOnPhone) TAB_PHONE else TAB_GLASSES) }

    Header(stringResource(R.string.band_title), stringResource(R.string.band_subtitle))
    BandHereCard(state, actions, onKey)
    Column(verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
        SectionTitle(stringResource(R.string.band_settings_of))
        Segmented(
            listOf(
                Segment(stringResource(R.string.band_tab_phone), LumenIcons.phone, state.bandOnPhone),
                Segment(stringResource(R.string.band_tab_glasses), LumenIcons.glasses, !state.bandOnPhone),
            ),
            selected = if (tab == TAB_PHONE) 0 else 1,
            height = 40.dp,
        ) { tab = if (it == 0) TAB_PHONE else TAB_GLASSES }
    }
    if (tab == TAB_PHONE) PhoneSettingsTab(state, actions, onProfile, onKey) else GlassesSettingsTab(state, actions)
}

private const val TAB_PHONE = "phone"
private const val TAB_GLASSES = "glasses"

/** The band's state, and which device it controls (a tap on the other hands it over). */
@Composable
private fun BandHereCard(state: CompanionUiState, actions: CompanionActions, onKey: () -> Unit) {
    val status = if (state.bandOnPhone) state.phoneBandStatus else state.bandStatus
    Card {
        BandStatusRow(status, lockedPause = state.bandOnPhone && !state.phoneListening)
        Column(verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
            Text(stringResource(R.string.band_device_now), style = MaterialTheme.typography.labelMedium, color = Lumen.textSecondary)
            val ready = state.bandKeyPresent && state.bluetoothGranted
            Segmented(
                listOf(
                    Segment(stringResource(R.string.band_tab_glasses), LumenIcons.glasses),
                    Segment(stringResource(R.string.band_device_this_phone), LumenIcons.phone),
                ),
                selected = if (state.bandOnPhone) 1 else 0,
                enabled = ready,
            ) { if (it == 1) actions.useBandOnPhone() else actions.useBandOnGlasses() }
            val hint = when {
                !state.bandKeyPresent -> R.string.band_key_missing
                !state.bluetoothGranted -> R.string.band_bluetooth_missing
                else -> R.string.band_device_switch_hint
            }
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder)
            when {
                !state.bandKeyPresent -> PillButton(stringResource(R.string.band_key_row), primary = true, modifier = Modifier.fillMaxWidth()) { onKey() }
                !state.bluetoothGranted -> PillButton(stringResource(R.string.band_allow_bluetooth), primary = true, modifier = Modifier.fillMaxWidth()) { actions.allowBluetooth() }
            }
        }
    }
}

// ---- The phone ----

@Composable
private fun PhoneSettingsTab(state: CompanionUiState, actions: CompanionActions, onProfile: (String) -> Unit, onKey: () -> Unit) {
    val profiles = state.profiles ?: return
    val current = profiles.current
    var choosingSwitch by remember { mutableStateOf(false) }
    var choosingHand by remember { mutableStateOf(false) }

    SectionTitle(stringResource(R.string.profile_section))
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall),
        ) {
            profiles.profiles.forEach { profile ->
                ProfileChip(profile.name, profileIcon(profile.kind), selected = profile.id == profiles.active) { actions.selectProfile(profile.id) }
            }
            ProfileChip(stringResource(R.string.profile_new), LumenIcons.plus, selected = false) { actions.addProfile() }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Lumen.radiusRow))
                .background(Lumen.elevation1)
                .clickable(role = Role.Button) { choosingSwitch = true }
                .padding(Lumen.spacingSmMed),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
        ) {
            Icon(LumenIcons.refresh, contentDescription = null, tint = Lumen.purple, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (profiles.switchGesture == PhoneSettings.NONE) stringResource(R.string.profile_switch_none)
                    else stringResource(R.string.profile_switch_title, gestureLabel(profiles.switchGesture).lowercase()),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(stringResource(R.string.profile_switch_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
            }
            Icon(LumenIcons.chevron, contentDescription = null, tint = Lumen.textPlaceholder, modifier = Modifier.size(18.dp))
        }
    }

    SectionTitle(stringResource(R.string.profile_gestures, current.name))
    ProfileRows(state, actions, current)
    PillButton(stringResource(R.string.profile_edit), primary = false, modifier = Modifier.fillMaxWidth()) { onProfile(current.id) }

    SectionTitle(stringResource(R.string.band_on_this_phone))
    Group {
        ListRow(
            title = stringResource(R.string.band_handwriting_keyboard),
            subtitle = stringResource(R.string.band_handwriting_keyboard_hint),
            icon = LumenIcons.keyboard,
            trailing = {
                StatusPill(
                    stringResource(if (state.handwritingKeyboardOn) R.string.band_handwriting_on else R.string.band_handwriting_off),
                    if (state.handwritingKeyboardOn) Lumen.positive else Lumen.warning,
                )
            },
            onClick = if (state.handwritingKeyboardOn) actions::chooseKeyboard else actions::openKeyboardSettings,
        )
        ListRow(
            title = stringResource(R.string.phone_touch),
            subtitle = stringResource(R.string.phone_touch_hint),
            icon = LumenIcons.grid,
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
            icon = LumenIcons.gear,
            trailing = {
                StatusPill(
                    stringResource(if (state.writeSettingsGranted) R.string.phone_allowed else R.string.phone_not_allowed),
                    if (state.writeSettingsGranted) Lumen.positive else Lumen.warning,
                )
            },
            onClick = actions::allowWriteSettings,
        )
        ListRow(
            title = stringResource(R.string.band_setting_hand),
            icon = LumenIcons.band,
            value = optionText(state.phoneHand),
            onClick = { choosingHand = true },
        )
    }

    SectionTitle(stringResource(R.string.band_key_section))
    Group { ListRow(title = stringResource(R.string.band_key_row), subtitle = stringResource(R.string.band_key_row_hint), icon = LumenIcons.shield, onClick = onKey) }

    if (choosingSwitch) {
        Choices(
            title = stringResource(R.string.profile_switch_choose),
            options = (listOf(PhoneSettings.NONE) + PhoneSettings.GESTURES).map {
                it to if (it == PhoneSettings.NONE) stringResource(R.string.band_option_none) else gestureLabel(it)
            },
            selected = profiles.switchGesture,
            onDismiss = { choosingSwitch = false },
        ) { choosingSwitch = false; actions.setSwitchGesture(it) }
    }
    if (choosingHand) {
        Choices(
            title = stringResource(R.string.band_setting_hand),
            options = listOf("band", "left", "right").map { it to optionText(it) },
            selected = state.phoneHand,
            onDismiss = { choosingHand = false },
        ) { choosingHand = false; actions.setPhoneSetting(PhoneSettings.HAND, it) }
    }
}

/** A profile's gestures, its pinch and turn and the locked phone: on the tab and on its page. */
@Composable
private fun ProfileRows(state: CompanionUiState, actions: CompanionActions, profile: PhoneProfiles.Profile) {
    val profiles = state.profiles ?: return
    var gesture by remember { mutableStateOf<String?>(null) }
    var app by remember { mutableStateOf<String?>(null) }
    var dial by remember { mutableStateOf(false) }
    Group {
        PhoneSettings.GESTURES.forEach { key ->
            val switching = key == profiles.switchGesture
            ListRow(
                title = gestureLabel(key),
                value = if (switching) stringResource(R.string.action_next_profile) else actionLabel(profile, key, profiles),
                trailing = if (switching) ({ Tag(stringResource(R.string.profile_all)) }) else null,
                onClick = if (switching) null else ({ gesture = key }),
            )
        }
        ListRow(
            title = stringResource(R.string.band_setting_dial),
            subtitle = stringResource(R.string.profile_dial_hint),
            value = dialText(profile.dial),
            onClick = { dial = true },
        )
    }
    Group {
        SwitchRow(
            stringResource(R.string.profile_locked),
            stringResource(R.string.profile_locked_hint),
            profile.whenLocked,
            { actions.setProfileWhenLocked(profile.id, it) },
        )
    }
    if (profile.actions.values.any { PhoneSettings.needsTouch(it) } && !state.touchEnabled) {
        Text(
            stringResource(R.string.profile_needs_touch),
            style = MaterialTheme.typography.bodySmall,
            color = Lumen.warning,
            modifier = Modifier.padding(horizontal = Lumen.spacingSmall),
        )
    }

    gesture?.let { key ->
        ActionPicker(gestureLabel(key), profile, key, profiles, onDismiss = { gesture = null }) { action ->
            gesture = null
            if (action == PhoneSettings.OPEN_APP) app = key else actions.setProfileAction(profile.id, key, action, "")
        }
    }
    app?.let { key ->
        Choices(
            title = stringResource(R.string.band_setting_app),
            options = state.phoneApps.map { it.id to it.label },
            selected = profile.app(key),
            onDismiss = { app = null },
        ) { app = null; actions.setProfileAction(profile.id, key, PhoneSettings.OPEN_APP, it) }
    }
    if (dial) {
        Choices(
            title = stringResource(R.string.profile_dial_choose),
            options = PhoneProfiles.DIALS.keys.map { it to dialText(it) },
            selected = profile.dial,
            onDismiss = { dial = false },
        ) { dial = false; actions.setProfileDial(profile.id, it) }
    }
}

/** A profile's own page: its name, its gestures, a copy, and deleting it. */
@Composable
internal fun ProfilePage(state: CompanionUiState, actions: CompanionActions, id: String, onBack: () -> Unit) {
    val profiles = state.profiles
    val profile = profiles?.profiles?.firstOrNull { it.id == id }
    LaunchedEffect(profile == null) { if (profile == null) onBack() }
    if (profiles == null || profile == null) return
    var name by remember(id) { mutableStateOf(profile.name) }
    var deleting by remember { mutableStateOf(false) }
    PageHeader(profile.name, onBack)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it; if (it.isNotBlank()) actions.renameProfile(id, it.trim()) },
        label = { Text(stringResource(R.string.profile_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Lumen.radiusRow),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Lumen.accent, unfocusedBorderColor = Lumen.border, focusedLabelColor = Lumen.accent),
    )
    SectionTitle(stringResource(R.string.profile_gestures_short))
    ProfileRows(state, actions, profile)
    Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed), modifier = Modifier.fillMaxWidth()) {
        PillButton(stringResource(R.string.profile_duplicate), primary = false, modifier = Modifier.weight(1f)) { actions.duplicateProfile(id) }
        PillButton(stringResource(R.string.profile_delete), primary = false, modifier = Modifier.weight(1f), enabled = profiles.profiles.size > 1) { deleting = true }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = Lumen.elevation1,
            title = { Text(stringResource(R.string.profile_delete_title, profile.name)) },
            text = { Text(stringResource(R.string.profile_delete_text), color = Lumen.textSecondary) },
            confirmButton = {
                TextButton(onClick = { deleting = false; actions.removeProfile(id); onBack() }) {
                    Text(stringResource(R.string.profile_delete), color = Lumen.negative)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
        )
    }
}

/** The band's key: whether it's here, sending, exporting, importing, and claiming a band. */
@Composable
internal fun BandKeyPage(state: CompanionUiState, actions: CompanionActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.band_key_row), onBack)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed)) {
            Icon(LumenIcons.shield, contentDescription = null, tint = if (state.bandKeyPresent) Lumen.positive else Lumen.warning, modifier = Modifier.size(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(if (state.bandKeyPresent) R.string.band_key_here else R.string.band_key_missing_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (state.bandKeyPresent) R.string.band_key_here_hint else R.string.band_key_missing),
                    style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary,
                )
            }
        }
        state.setup.keyResult?.let {
            Text(
                if (it.ok) stringResource(R.string.setup_step_key_done) else stringResource(R.string.setup_key_refused, it.error),
                style = MaterialTheme.typography.bodySmall, color = if (it.ok) Lumen.positive else Lumen.warning,
            )
        }
    }
    Group {
        if (state.bandKeyPresent && state.setup.responding) {
            ListRow(title = stringResource(R.string.setup_send_key), icon = LumenIcons.glasses, onClick = actions::sendBandKey)
        }
        if (state.bandKeyPresent) {
            ListRow(title = stringResource(R.string.band_export_key), subtitle = stringResource(R.string.band_export_key_hint), icon = LumenIcons.download, onClick = actions::exportBandKey)
        }
        ListRow(title = stringResource(R.string.band_import_key), subtitle = stringResource(R.string.band_import_key_hint), icon = LumenIcons.plus, onClick = actions::importBandKey)
    }
    SectionTitle(stringResource(R.string.band_key_new_section))
    Card { ClaimSection(state, actions) }
}

// ---- The glasses ----

@Composable
private fun GlassesSettingsTab(state: CompanionUiState, actions: CompanionActions) {
    val schema = state.bandSchema
    var choosing by remember { mutableStateOf<Setting?>(null) }
    var confirming by remember { mutableStateOf<SettingsAction?>(null) }
    Card {
        Text(stringResource(R.string.glasses_fixed_title), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall), modifier = Modifier.fillMaxWidth()) {
            FixedGesture(LumenIcons.grid, stringResource(R.string.glasses_fixed_swipe), Modifier.weight(1f))
            FixedGesture(LumenIcons.check, stringResource(R.string.glasses_fixed_index), Modifier.weight(1f))
            FixedGesture(LumenIcons.back, stringResource(R.string.glasses_fixed_middle), Modifier.weight(1f))
        }
    }
    if (schema == null) {
        Card {
            Text(stringResource(R.string.band_waiting), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.band_waiting_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
        }
        return
    }
    state.bandError?.let { error ->
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
        Choices(
            title = BandLabels.setting(setting.key)?.let { stringResource(it) } ?: setting.label,
            options = setting.options.map { it.id to optionLabel(setting, it.id) },
            selected = setting.value,
            onDismiss = { choosing = null },
        ) { choosing = null; actions.setBandSetting(setting.key, it) }
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
private fun FixedGesture(icon: ImageVector, label: String, modifier: Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(Lumen.radiusRow)).background(Lumen.elevation1).padding(vertical = Lumen.spacingSmMed, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Lumen.textPrimary, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
    }
}

// ---- Pieces ----

@Composable
internal fun BandStatusCard(status: BandStatus) {
    Card { BandStatusRow(status, lockedPause = false) }
}

@Composable
private fun BandStatusRow(status: BandStatus, lockedPause: Boolean) {
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
                else if (lockedPause) StatusPill(stringResource(R.string.band_paused_locked), Lumen.warning)
            }
        }
    }
}

private data class Segment(val label: String, val icon: ImageVector?, val badge: Boolean = false)

/** A pill-shaped segmented control (a tab row or a two-way switch). */
@Composable
private fun Segmented(items: List<Segment>, selected: Int, height: Dp = 48.dp, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Lumen.bar)
            .border(1.dp, Lumen.border, CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { index, item ->
            val on = index == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(height - 8.dp)
                    .clip(CircleShape)
                    .background(if (on) Lumen.elevation2 else Lumen.bar)
                    .clickable(enabled = enabled && !on, role = Role.Tab) { onSelect(index) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                item.icon?.let { Icon(it, contentDescription = null, tint = if (on) Lumen.textPrimary else Lumen.textSecondary, modifier = Modifier.size(18.dp)) }
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    color = if (on) Lumen.textPrimary else Lumen.textSecondary,
                )
                if (item.badge) Box(Modifier.size(6.dp).clip(CircleShape).background(Lumen.positive))
            }
        }
    }
}

@Composable
private fun ProfileChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(if (selected) Lumen.accent else Lumen.elevation1)
            .then(if (selected) Modifier else Modifier.border(1.dp, Lumen.border, CircleShape))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Lumen.spacingMedium, vertical = Lumen.spacingSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Lumen.textPrimary, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium))
        if (selected) Icon(LumenIcons.check, contentDescription = null, tint = Lumen.textPrimary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Lumen.purple,
        modifier = Modifier.clip(CircleShape).background(Lumen.purple.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun profileIcon(kind: String) = when (kind) {
    "media" -> LumenIcons.mic
    "navigation" -> LumenIcons.grid
    else -> LumenIcons.band
}

/** An action for a phone gesture, grouped: profiles, media and volume, the screen, keys, the rest. */
@Composable
private fun ActionPicker(
    title: String,
    profile: PhoneProfiles.Profile,
    gesture: String,
    profiles: PhoneProfiles.State,
    onDismiss: () -> Unit,
    onChoose: (String) -> Unit,
) {
    val groups = listOf(
        null to listOf(PhoneSettings.NONE),
        R.string.action_group_profiles to listOf(PhoneProfiles.NEXT, PhoneProfiles.PREVIOUS) +
            profiles.profiles.filter { it.id != profile.id }.map { PhoneProfiles.GO_PREFIX + it.id },
        R.string.action_group_media to listOf("media.play_pause", "media.next", "media.previous", "volume.up", "volume.down", "volume.mute"),
        R.string.action_group_screen to listOf("screen.back", "screen.home", "screen.recents", "screen.swipe_up", "screen.swipe_down", "screen.swipe_left", "screen.swipe_right"),
        R.string.action_group_keys to listOf("key.dpad_up", "key.dpad_down", "key.dpad_left", "key.dpad_right", "key.enter"),
        R.string.action_group_other to listOf("brightness.up", "brightness.down", "torch.toggle", PhoneSettings.OPEN_APP, PhoneSettings.SWITCH_TO_GLASSES),
    )
    val current = profile.action(gesture)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lumen.elevation1,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title)
                Text(stringResource(R.string.profile_in, profile.name), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                groups.forEach { (group, ids) ->
                    if (group != null) {
                        Text(
                            stringResource(group).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Lumen.textPlaceholder,
                            modifier = Modifier.padding(top = Lumen.spacingSmMed, bottom = 2.dp),
                        )
                    }
                    ids.forEach { id -> Option(actionText(id, profiles), id == current) { onChoose(id) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
    )
}

@Composable
private fun Choices(title: String, options: List<Pair<String, String>>, selected: String, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lumen.elevation1,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (id, label) -> Option(label, id == selected) { onChoose(id) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
    )
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = Lumen.accent, unselectedColor = Lumen.textPlaceholder),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
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

// ---- Labels ----

private val GESTURE_LABELS = mapOf(
    "swipe_up" to R.string.phone_gesture_swipe_up,
    "swipe_down" to R.string.phone_gesture_swipe_down,
    "swipe_left" to R.string.phone_gesture_swipe_left,
    "swipe_right" to R.string.phone_gesture_swipe_right,
    "index_tap" to R.string.phone_gesture_index_tap,
    "index_double" to R.string.band_setting_index_double,
    "middle_tap" to R.string.phone_gesture_middle_tap,
    "middle_double" to R.string.band_setting_middle_double,
)

@Composable
private fun gestureLabel(key: String): String = GESTURE_LABELS[key]?.let { stringResource(it) } ?: key

/** A gesture's action in [profile], with the app's name for "open an app". */
@Composable
private fun actionLabel(profile: PhoneProfiles.Profile, gesture: String, profiles: PhoneProfiles.State): String {
    val action = profile.action(gesture)
    if (action == PhoneSettings.OPEN_APP && profile.app(gesture).isNotEmpty()) return profile.app(gesture).substringAfterLast('.')
    return actionText(action, profiles)
}

@Composable
private fun actionText(action: String, profiles: PhoneProfiles.State): String = when {
    action == PhoneSettings.NONE -> stringResource(R.string.band_option_none)
    action == PhoneProfiles.NEXT -> stringResource(R.string.action_next_profile)
    action == PhoneProfiles.PREVIOUS -> stringResource(R.string.action_previous_profile)
    action.startsWith(PhoneProfiles.GO_PREFIX) -> stringResource(
        R.string.action_go_profile,
        profiles.profiles.firstOrNull { it.id == action.removePrefix(PhoneProfiles.GO_PREFIX) }?.name ?: "?",
    )
    else -> BandLabels.option("phone.gesture", action)?.let { stringResource(it) } ?: PhoneSettings.ACTIONS[action] ?: action
}

@Composable
private fun dialText(id: String): String = when (id) {
    "brightness" -> stringResource(R.string.phone_dial_brightness)
    "arrows" -> stringResource(R.string.dial_arrows)
    "volume" -> stringResource(R.string.band_option_volume)
    else -> stringResource(R.string.band_option_none)
}

@Composable
private fun optionText(id: String): String = BandLabels.option("hand", id)?.let { stringResource(it) } ?: id

@Composable
private fun optionLabel(setting: Setting, id: String): String {
    if (id.isEmpty()) return stringResource(R.string.band_not_chosen)
    BandLabels.option(setting.key, id)?.let { return stringResource(it) }
    return setting.options.firstOrNull { it.id == id }?.label ?: id
}

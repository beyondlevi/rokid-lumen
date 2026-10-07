package dev.lumen.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lumen.companion.PhoneProfiles
import dev.lumen.companion.PhoneSettings
import dev.lumen.companion.R
import dev.lumen.companion.computer.ComputerKeys
import dev.lumen.companion.computer.ComputerLink
import dev.lumen.companion.computer.ComputerProfiles

/**
 * The band for a computer, through this phone as its Bluetooth keyboard and mouse: the
 * computer's gesture profiles and settings (a tab of the Band screen), its computers (a page),
 * and a profile's own page. The switch gesture is the phone's, shared by every profile.
 */
@Composable
internal fun ComputerSettingsTab(
    state: CompanionUiState,
    actions: CompanionActions,
    onComputers: () -> Unit,
    onComputerProfile: (String) -> Unit,
    onKey: () -> Unit,
) {
    val profiles = state.computerProfiles ?: return
    val phoneProfiles = state.profiles ?: return
    val current = profiles.current
    var choosingLayout by remember { mutableStateOf(false) }

    SectionTitle(stringResource(R.string.computer_profile_section))
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall),
        ) {
            profiles.profiles.forEach { profile ->
                ProfileChip(profile.name, computerProfileIcon(profile.kind), selected = profile.id == profiles.active) { actions.selectComputerProfile(profile.id) }
            }
            ProfileChip(stringResource(R.string.profile_new), LumenIcons.plus, selected = false) { actions.addComputerProfile() }
        }
        SwitchGestureRow(phoneProfiles, actions, stringResource(R.string.computer_switch_hint))
    }

    SectionTitle(stringResource(R.string.profile_gestures, current.name))
    ComputerProfileRows(state, actions, current)
    if (PhoneSettings.GESTURES.any { it != phoneProfiles.switchGesture && current.action(it) == ComputerKeys.WRITE }) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Lumen.radiusCard)).background(Lumen.surface).padding(Lumen.spacingMedium),
            horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
        ) {
            Icon(LumenIcons.write, contentDescription = null, tint = Lumen.purple, modifier = Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.computer_write_title), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                Text(stringResource(R.string.computer_write_text), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
            }
        }
    }
    if (PhoneSettings.GESTURES.any { it != phoneProfiles.switchGesture && current.action(it) == ComputerKeys.POINTER }) {
        PointerCard(LumenIcons.laptop, stringResource(R.string.computer_pointer_text))
    }
    PillButton(stringResource(R.string.profile_edit), primary = false, modifier = Modifier.fillMaxWidth()) { onComputerProfile(current.id) }

    SectionTitle(stringResource(R.string.computer_section))
    Group {
        ListRow(
            title = stringResource(R.string.computer_layout),
            subtitle = stringResource(R.string.computer_layout_hint),
            icon = LumenIcons.keyboard,
            value = layoutText(state.computer.layout),
            onClick = { choosingLayout = true },
        )
        SliderRow(
            title = stringResource(R.string.computer_scroll_speed),
            hint = stringResource(R.string.computer_scroll_speed_hint),
            value = state.computer.scrollSteps.toFloat(),
            range = ComputerKeys.SCROLL_MIN.toFloat()..ComputerKeys.SCROLL_MAX.toFloat(),
            steps = ComputerKeys.SCROLL_MAX - ComputerKeys.SCROLL_MIN - 1,
            start = stringResource(R.string.computer_scroll_slow),
            end = stringResource(R.string.computer_scroll_fast),
        ) { actions.setComputerScrollSteps(Math.round(it)) }
        SwitchRow(
            stringResource(R.string.computer_invert_scroll),
            stringResource(R.string.computer_invert_scroll_hint),
            state.computer.invertScroll,
            { actions.setComputerInvertScroll(it) },
        )
        ListRow(
            title = stringResource(R.string.computer_locked),
            subtitle = stringResource(R.string.computer_locked_hint),
            icon = LumenIcons.shield,
            value = stringResource(R.string.computer_locked_always),
        )
        ListRow(
            title = stringResource(R.string.computer_computers),
            subtitle = stringResource(R.string.computer_computers_hint),
            icon = LumenIcons.laptop,
            onClick = onComputers,
        )
    }
    Text(
        stringResource(R.string.computer_own_keyboards),
        style = MaterialTheme.typography.bodySmall,
        color = Lumen.textPlaceholder,
        modifier = Modifier.padding(horizontal = Lumen.spacingSmall),
    )

    SectionTitle(stringResource(R.string.computer_pointer_section))
    Group {
        SliderRow(
            title = stringResource(R.string.computer_pointer_speed),
            hint = stringResource(R.string.computer_pointer_speed_hint),
            value = state.computer.pointerSpeed.toFloat(),
            range = ComputerProfiles.POINTER_SPEEDS.first.toFloat()..ComputerProfiles.POINTER_SPEEDS.last.toFloat(),
            steps = (ComputerProfiles.POINTER_SPEEDS.last - ComputerProfiles.POINTER_SPEEDS.first) / 5 - 1,
            start = stringResource(R.string.computer_scroll_slow),
            end = stringResource(R.string.computer_scroll_fast),
        ) { actions.setComputerPointerSpeed(Math.round(it)) }
        SliderRow(
            title = stringResource(R.string.computer_pointer_steadiness),
            hint = stringResource(R.string.computer_pointer_steadiness_hint),
            value = state.computer.pointerSteadiness,
            range = 0f..1f,
            steps = 9,
            start = stringResource(R.string.computer_pointer_responsive),
            end = stringResource(R.string.computer_pointer_steady),
        ) { actions.setComputerPointerSteadiness(it) }
        SliderRow(
            title = stringResource(R.string.computer_pointer_boost),
            hint = stringResource(R.string.computer_pointer_boost_hint),
            value = state.computer.pointerBoost,
            range = ComputerProfiles.POINTER_BOOSTS,
            steps = 14,
            start = stringResource(R.string.computer_pointer_boost_none),
            end = stringResource(R.string.computer_pointer_boost_more),
        ) { actions.setComputerPointerBoost(it) }
    }

    SectionTitle(stringResource(R.string.band_key_section))
    Group { ListRow(title = stringResource(R.string.band_key_row), subtitle = stringResource(R.string.band_key_row_hint), icon = LumenIcons.shield, onClick = onKey) }

    if (choosingLayout) {
        Choices(
            title = stringResource(R.string.computer_layout),
            options = ComputerKeys.Layout.entries.map { it.id to layoutText(it) },
            selected = state.computer.layout.id,
            onDismiss = { choosingLayout = false },
        ) { id -> choosingLayout = false; actions.setComputerLayout(ComputerKeys.Layout.of(id)) }
    }
}

/**
 * What the air mouse does where it's mapped (a computer, this phone, the glasses), marked
 * experimental; [note] is a line under it (what it needs).
 */
@Composable
internal fun PointerCard(icon: ImageVector, text: String, note: String? = null, noteColor: Color = Lumen.textSecondary) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Lumen.radiusCard)).background(Lumen.surface).padding(Lumen.spacingMedium),
        horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
    ) {
        Icon(icon, contentDescription = null, tint = Lumen.purple, modifier = Modifier.size(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.computer_pointer_title), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                Tag(stringResource(R.string.computer_tag_experimental))
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = noteColor)
        }
    }
}

/** A setting on a slider (the scrolling, the air mouse), saved when it's let go. */
@Composable
internal fun SliderRow(
    title: String,
    hint: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    start: String,
    end: String,
    onChange: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Lumen.surface)
            .padding(horizontal = Lumen.spacingMedium, vertical = Lumen.spacingSmMed),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onChange(current) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Lumen.accent,
                activeTrackColor = Lumen.accent,
                inactiveTrackColor = Lumen.elevation2,
                activeTickColor = Lumen.accent,
                inactiveTickColor = Lumen.elevation2,
            ),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(start, style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder, modifier = Modifier.weight(1f))
            Text(end, style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder)
        }
    }
}

/** The computer connected (or not) on the Band card, with the way to its computers. */
@Composable
internal fun ComputerRow(computer: ComputerUiState, onComputers: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Lumen.radiusRow))
            .background(Lumen.elevation1)
            .clickable(role = Role.Button, onClick = onComputers)
            .padding(Lumen.spacingSmMed),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Lumen.elevation2),
            contentAlignment = Alignment.Center,
        ) { Icon(LumenIcons.laptop, contentDescription = null, tint = Lumen.textPrimary, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                computer.name ?: stringResource(R.string.computer_none),
                style = MaterialTheme.typography.titleMedium,
            )
            StatusPill(statusText(computer), statusColor(computer.status))
        }
        Text(stringResource(R.string.computer_change), style = MaterialTheme.typography.labelLarge, color = Lumen.accent)
    }
}

/** A computer profile's gestures and pinch and turn: on the tab and on its page. */
@Composable
private fun ComputerProfileRows(state: CompanionUiState, actions: CompanionActions, profile: ComputerProfiles.Profile) {
    val profiles = state.computerProfiles ?: return
    val switchGesture = state.profiles?.switchGesture ?: PhoneSettings.NONE
    var gesture by remember { mutableStateOf<String?>(null) }
    var dial by remember { mutableStateOf(false) }
    Group {
        PhoneSettings.GESTURES.forEach { key ->
            val switching = key == switchGesture
            ListRow(
                title = gestureLabel(key),
                value = if (switching) stringResource(R.string.action_next_profile) else computerActionText(profile.action(key), profiles),
                trailing = when {
                    switching -> ({ Tag(stringResource(R.string.profile_all)) })
                    profile.action(key) == ComputerKeys.WRITE -> ({ Tag(stringResource(R.string.computer_tag_writing)) })
                    profile.action(key) == ComputerKeys.POINTER -> ({ Tag(stringResource(R.string.computer_tag_experimental)) })
                    else -> null
                },
                onClick = if (switching) null else ({ gesture = key }),
            )
        }
        ListRow(
            title = stringResource(R.string.band_setting_dial),
            subtitle = stringResource(R.string.computer_dial_hint),
            value = computerDialText(profile.dial),
            onClick = { dial = true },
        )
    }
    gesture?.let { key ->
        ComputerActionPicker(gestureLabel(key), profile, key, profiles, onDismiss = { gesture = null }) { action ->
            gesture = null
            actions.setComputerAction(profile.id, key, action)
        }
    }
    if (dial) {
        Choices(
            title = stringResource(R.string.profile_dial_choose),
            options = ComputerProfiles.DIALS.map { it to computerDialText(it) },
            selected = profile.dial,
            onDismiss = { dial = false },
        ) { dial = false; actions.setComputerDial(profile.id, it) }
    }
}

/** A computer profile's own page: its name, its gestures, a copy, and deleting it. */
@Composable
internal fun ComputerProfilePage(state: CompanionUiState, actions: CompanionActions, id: String, onBack: () -> Unit) {
    val profiles = state.computerProfiles
    val profile = profiles?.profiles?.firstOrNull { it.id == id }
    LaunchedEffect(profile == null) { if (profile == null) onBack() }
    if (profiles == null || profile == null) return
    var name by remember(id) { mutableStateOf(profile.name) }
    var deleting by remember { mutableStateOf(false) }
    PageHeader(profile.name, onBack)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it; if (it.isNotBlank()) actions.renameComputerProfile(id, it.trim()) },
        label = { Text(stringResource(R.string.profile_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Lumen.radiusRow),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Lumen.accent, unfocusedBorderColor = Lumen.border, focusedLabelColor = Lumen.accent),
    )
    SectionTitle(stringResource(R.string.profile_gestures_short))
    ComputerProfileRows(state, actions, profile)
    Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed), modifier = Modifier.fillMaxWidth()) {
        PillButton(stringResource(R.string.profile_duplicate), primary = false, modifier = Modifier.weight(1f)) { actions.duplicateComputerProfile(id) }
        PillButton(stringResource(R.string.profile_delete), primary = false, modifier = Modifier.weight(1f), enabled = profiles.profiles.size > 1) { deleting = true }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = Lumen.elevation1,
            title = { Text(stringResource(R.string.profile_delete_title, profile.name)) },
            text = { Text(stringResource(R.string.profile_delete_text), color = Lumen.textSecondary) },
            confirmButton = {
                TextButton(onClick = { deleting = false; actions.removeComputerProfile(id); onBack() }) {
                    Text(stringResource(R.string.profile_delete), color = Lumen.negative)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
        )
    }
}

/**
 * The computers this phone has been a keyboard for: connect to one, forget one, or pair a new one
 * (the phone turns visible and the computer pairs from its own Bluetooth settings).
 */
@Composable
internal fun ComputersPage(state: CompanionUiState, actions: CompanionActions, onBack: () -> Unit) {
    val computer = state.computer
    PageHeader(stringResource(R.string.computer_computers), onBack)
    Text(
        stringResource(R.string.computer_computers_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = Lumen.textSecondary,
        modifier = Modifier.padding(horizontal = Lumen.spacingSmall),
    )
    if (!state.bandOnComputer) {
        Card {
            Text(stringResource(R.string.computer_mode_off), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.computer_mode_off_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
            PillButton(stringResource(R.string.computer_mode_on), primary = true, modifier = Modifier.fillMaxWidth()) { actions.useBandOnComputer() }
        }
    }
    if (computer.computers.isEmpty()) {
        Card {
            Text(stringResource(R.string.computer_list_empty), style = MaterialTheme.typography.bodyMedium, color = Lumen.textSecondary)
        }
    } else {
        Group {
            computer.computers.forEach { item ->
                val current = item.address == computer.address && computer.status in setOf(ComputerLink.Status.CONNECTED, ComputerLink.Status.CONNECTING)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .background(Lumen.surface)
                        .clickable(role = Role.RadioButton, enabled = state.bandOnComputer && !current) { actions.connectComputer(item.address) }
                        .padding(horizontal = Lumen.spacingMedium, vertical = Lumen.spacingSmMed),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
                ) {
                    RadioButton(
                        selected = current,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = Lumen.accent, unselectedColor = Lumen.textPlaceholder),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        StatusPill(
                            if (current) statusText(computer) else stringResource(R.string.computer_paired),
                            if (current) statusColor(computer.status) else Lumen.textPlaceholder,
                        )
                    }
                    TextButton(onClick = { actions.forgetComputer(item.address) }) {
                        Text(stringResource(R.string.computer_forget), color = Lumen.textSecondary)
                    }
                }
            }
        }
    }
    if (computer.pairing) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Lumen.radiusCard))
                .background(Lumen.surface)
                .padding(Lumen.spacingMedLg),
            verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed)) {
                Icon(LumenIcons.link, contentDescription = null, tint = Lumen.accent, modifier = Modifier.size(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.computer_pair_ready), style = MaterialTheme.typography.titleMedium)
                    Text(
                        pluralStringResource(R.plurals.computer_pair_visible, ComputerLink.PAIRING_SECONDS / 60, computer.phoneName, ComputerLink.PAIRING_SECONDS / 60),
                        style = MaterialTheme.typography.bodySmall,
                        color = Lumen.textSecondary,
                    )
                }
            }
            PairStep(1, stringResource(R.string.computer_pair_step1))
            PairStep(2, stringResource(R.string.computer_pair_step2, computer.phoneName))
            PairStep(3, stringResource(R.string.computer_pair_step3))
            PillButton(stringResource(R.string.cancel), primary = false, modifier = Modifier.fillMaxWidth()) { actions.cancelComputerPairing() }
        }
    } else {
        PillButton(
            stringResource(R.string.computer_pair_new),
            primary = true,
            modifier = Modifier.fillMaxWidth(),
            icon = LumenIcons.plus,
            enabled = state.bandOnComputer && computer.status != ComputerLink.Status.UNAVAILABLE,
        ) { actions.pairComputer() }
    }
    if (computer.status == ComputerLink.Status.UNAVAILABLE) {
        Text(
            stringResource(R.string.computer_unavailable_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Lumen.warning,
            modifier = Modifier.padding(horizontal = Lumen.spacingSmall),
        )
    }
    Text(
        stringResource(R.string.computer_forget_hint),
        style = MaterialTheme.typography.bodySmall,
        color = Lumen.textPlaceholder,
        modifier = Modifier.padding(horizontal = Lumen.spacingSmall).clickable(role = Role.Button) { actions.openBluetoothSettings() },
    )
}

@Composable
private fun PairStep(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed)) {
        Box(
            modifier = Modifier.size(24.dp).clip(CircleShape).background(Lumen.elevation2),
            contentAlignment = Alignment.Center,
        ) { Text(number.toString(), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)) }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Lumen.textSecondary, modifier = Modifier.weight(1f))
    }
}

/** An action for a computer gesture, grouped: writing, keys, desktops, scrolling, media, profiles. */
@Composable
private fun ComputerActionPicker(
    title: String,
    profile: ComputerProfiles.Profile,
    gesture: String,
    profiles: ComputerProfiles.State,
    onDismiss: () -> Unit,
    onChoose: (String) -> Unit,
) {
    // A hold can't switch the air mouse: while it runs, the pinches are its clicks.
    val groups: List<Pair<Int?, List<String>>> = listOf(null to listOf(PhoneSettings.NONE)) +
        ComputerProfiles.GROUPS.map { (group, ids) ->
            GROUP_LABELS.getValue(group) to ids.filter { gesture !in PhoneSettings.HOLDS || it != ComputerKeys.POINTER }
        }.filter { (_, ids) -> ids.isNotEmpty() } +
        listOf(
            R.string.action_group_profiles to listOf(PhoneProfiles.NEXT, PhoneProfiles.PREVIOUS) +
                profiles.profiles.filter { it.id != profile.id }.map { PhoneProfiles.GO_PREFIX + it.id },
        )
    val current = profile.action(gesture)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lumen.elevation1,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title)
                Text(stringResource(R.string.computer_profile_in, profile.name), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
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
                    ids.forEach { id ->
                        val tag = if (id == ComputerKeys.POINTER) stringResource(R.string.computer_tag_experimental) else null
                        Option(computerActionText(id, profiles), id == current, tag) { onChoose(id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
    )
}

// ---- Labels ----

private val GROUP_LABELS = mapOf(
    "writing" to R.string.computer_group_writing,
    "mouse" to R.string.computer_group_mouse,
    "keys" to R.string.action_group_keys,
    "desktops" to R.string.computer_group_desktops,
    "scroll" to R.string.computer_group_scroll,
    "media" to R.string.computer_group_media,
    "band" to R.string.action_group_band,
)

private val ACTION_LABELS = mapOf(
    ComputerKeys.WRITE to R.string.pc_write,
    ComputerKeys.POINTER to R.string.pc_pointer,
    PhoneSettings.PAUSE to R.string.action_pause,
    "pc.key.up" to R.string.pc_key_up,
    "pc.key.down" to R.string.pc_key_down,
    "pc.key.left" to R.string.pc_key_left,
    "pc.key.right" to R.string.pc_key_right,
    "pc.key.enter" to R.string.pc_key_enter,
    "pc.key.escape" to R.string.pc_key_escape,
    "pc.key.tab" to R.string.pc_key_tab,
    "pc.key.space" to R.string.pc_key_space,
    "pc.key.backspace" to R.string.pc_key_backspace,
    "pc.desktop.next" to R.string.pc_desktop_next,
    "pc.desktop.previous" to R.string.pc_desktop_previous,
    "pc.mission_control" to R.string.pc_mission_control,
    "pc.app_switch" to R.string.pc_app_switch,
    "pc.scroll.up" to R.string.pc_scroll_up,
    "pc.scroll.down" to R.string.pc_scroll_down,
    "pc.media.play_pause" to R.string.pc_media_play_pause,
    "pc.media.next" to R.string.pc_media_next,
    "pc.media.previous" to R.string.pc_media_previous,
    "pc.volume.up" to R.string.pc_volume_up,
    "pc.volume.down" to R.string.pc_volume_down,
    "pc.volume.mute" to R.string.pc_volume_mute,
    "pc.brightness.up" to R.string.pc_brightness_up,
    "pc.brightness.down" to R.string.pc_brightness_down,
)

@Composable
private fun computerActionText(action: String, profiles: ComputerProfiles.State): String = when {
    action == PhoneSettings.NONE -> stringResource(R.string.band_option_none)
    action == PhoneProfiles.NEXT -> stringResource(R.string.action_next_profile)
    action == PhoneProfiles.PREVIOUS -> stringResource(R.string.action_previous_profile)
    action.startsWith(PhoneProfiles.GO_PREFIX) -> stringResource(
        R.string.action_go_profile,
        profiles.profiles.firstOrNull { it.id == action.removePrefix(PhoneProfiles.GO_PREFIX) }?.name ?: "?",
    )
    else -> ACTION_LABELS[action]?.let { stringResource(it) } ?: action
}

@Composable
private fun computerDialText(id: String): String = when (id) {
    "scroll" -> stringResource(R.string.computer_dial_scroll)
    else -> dialText(id)
}


@Composable
private fun layoutText(layout: ComputerKeys.Layout): String = stringResource(
    when (layout) {
        ComputerKeys.Layout.US -> R.string.computer_layout_us
        ComputerKeys.Layout.ABNT2 -> R.string.computer_layout_abnt2
    },
)

@Composable
private fun statusText(computer: ComputerUiState): String = stringResource(
    when {
        computer.writing -> R.string.computer_status_writing
        computer.pointer -> R.string.computer_status_pointer
        else -> when (computer.status) {
            ComputerLink.Status.OFF -> R.string.computer_status_off
            ComputerLink.Status.STARTING -> R.string.computer_status_starting
            ComputerLink.Status.READY -> R.string.computer_status_ready
            ComputerLink.Status.CONNECTING -> R.string.computer_status_connecting
            ComputerLink.Status.CONNECTED -> R.string.computer_status_connected
            ComputerLink.Status.UNAVAILABLE -> R.string.computer_status_unavailable
        }
    },
)

private fun statusColor(status: ComputerLink.Status) = when (status) {
    ComputerLink.Status.CONNECTED -> Lumen.positive
    ComputerLink.Status.UNAVAILABLE -> Lumen.negative
    else -> Lumen.warning
}

private fun computerProfileIcon(kind: String) = when (kind) {
    "notebook" -> LumenIcons.laptop
    "presentation" -> LumenIcons.slides
    else -> LumenIcons.band
}

package dev.lumen.companion.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.lumen.companion.AutoOff
import dev.lumen.companion.GlassesAdb
import dev.lumen.companion.GlassesMirror
import dev.lumen.companion.R
import dev.lumen.companion.TailnetRelay
import dev.lumen.protocol.DebugStatus
import dev.lumen.protocol.SettingsOps
import kotlinx.coroutines.delay

/**
 * Settings › Developer: the glasses' wireless debugging, the same network's `adb connect`,
 * "Also through Tailscale" with this phone's forward ([TailnetRelay]), authorizing a computer
 * (pairing), turning off by itself, and the glasses' screen.
 */
@Composable
internal fun WirelessDebugSection(state: CompanionUiState, actions: CompanionActions, onScreen: () -> Unit) {
    val debug = state.glassesDebug
    val pending = state.glassesDebugPending
    var pairing by rememberSaveable { mutableStateOf<String?>(null) }
    var autoOff by rememberSaveable { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.debug_section))
    Group {
        SwitchRow(
            stringResource(R.string.debug_switch),
            stringResource(R.string.debug_switch_hint),
            checked = pending ?: debug.enabled,
            busy = pending != null,
            onChange = actions::setWirelessDebug,
        )
        val on = debug.enabled && pending == null
        if (on) {
            SameNetworkBlock(debug, state.debugViaTailscale, state, actions)
            SwitchRow(
                stringResource(R.string.debug_tailscale),
                stringResource(R.string.debug_tailscale_hint),
                checked = state.debugViaTailscale,
                onChange = actions::setDebugViaTailscale,
            )
            if (state.debugViaTailscale) TailscaleBlock(debug, state.tailnet, actions)
            ListRow(
                title = stringResource(R.string.debug_authorize),
                subtitle = stringResource(R.string.debug_authorize_hint),
                icon = LumenIcons.key,
                onClick = { pairing = actions.startAdbPairing() },
            )
            ListRow(
                title = stringResource(R.string.debug_auto_off),
                subtitle = autoOffLabel(state.debugAutoOffHours),
                icon = LumenIcons.timer,
                onClick = { autoOff = true },
            )
        }
        ListRow(
            title = stringResource(R.string.debug_screen),
            subtitle = stringResource(if (on) R.string.debug_screen_hint else R.string.debug_screen_off_hint),
            icon = LumenIcons.glasses,
            onClick = if (on) onScreen else null,
        )
    }
    pairing?.let { code ->
        PairingSheet(code, debug, state.debugViaTailscale, state.tailnet, actions) {
            actions.stopAdbPairing()
            pairing = null
        }
    }
    if (autoOff) {
        AutoOffDialog(state.debugAutoOffHours, onPick = { actions.setDebugAutoOff(it); autoOff = false }) { autoOff = false }
    }
}

@Composable
private fun autoOffLabel(hours: Int) = stringResource(
    when (hours) {
        1 -> R.string.debug_auto_off_1
        AutoOff.NEVER -> R.string.debug_auto_off_never
        else -> R.string.debug_auto_off_4
    },
)

@Composable
private fun Details(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Lumen.surface).padding(horizontal = Lumen.spacingMedium, vertical = Lumen.spacingSmMed),
        verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
    ) { content() }
}

@Composable
private fun CommandBox(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Lumen.elevation1).padding(Lumen.spacingSmMed),
    )
}

@Composable
private fun CopyShare(text: String, actions: CompanionActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
        PillButton(stringResource(R.string.debug_copy), primary = true, icon = LumenIcons.copy, modifier = Modifier.weight(1f)) { actions.copyText(text) }
        PillButton(stringResource(R.string.debug_share), primary = false, icon = LumenIcons.share, modifier = Modifier.weight(1f)) { actions.shareText(text) }
    }
}

/** Where the glasses are and, on a network a computer can share, its `adb connect`. */
@Composable
private fun SameNetworkBlock(debug: DebugStatus, viaTailscale: Boolean, state: CompanionUiState, actions: CompanionActions) {
    val problem = when {
        !debug.wifiOn -> stringResource(R.string.debug_wifi_off)
        debug.address.isEmpty() -> stringResource(if (viaTailscale) R.string.debug_waiting_network else R.string.debug_no_network)
        !debug.listening -> stringResource(R.string.debug_port_closed, debug.port)
        else -> null
    }
    Details {
        when {
            debug.address.isEmpty() -> Unit
            debug.onPhoneHotspot && viaTailscale -> {
                Text(stringResource(R.string.debug_on_phone_hotspot), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
                Text(stringResource(R.string.debug_on_phone_hotspot_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder)
            }
            debug.onPhoneHotspot -> Text(stringResource(R.string.debug_on_hotspot), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
            else -> {
                Text(
                    if (debug.ssid.isNotEmpty()) stringResource(R.string.debug_where_glasses, debug.ssid, debug.address)
                    else stringResource(R.string.debug_where_glasses_unnamed, debug.address),
                    style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary,
                )
                Text(stringResource(R.string.debug_same_network), style = MaterialTheme.typography.labelLarge)
                CommandBox(debug.command)
                // With Tailscale on, the copy buttons go with its command.
                if (!viaTailscale) CopyShare(debug.command, actions)
            }
        }
        if (problem != null) {
            Text(problem, style = MaterialTheme.typography.bodySmall, color = Lumen.warning)
        } else if (!viaTailscale && !debug.onPhoneHotspot && state.glassesDebugSameNetwork == false) {
            Text(stringResource(R.string.debug_other_network), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
        }
    }
}

/** This phone's Tailscale address and its `adb connect`, or why there's none. */
@Composable
private fun TailscaleBlock(debug: DebugStatus, relay: TailnetRelay.State, actions: CompanionActions) {
    Details {
        val address = relay.address
        when {
            address == null -> {
                Text(stringResource(R.string.debug_tailscale_off), style = MaterialTheme.typography.bodySmall, color = Lumen.warning)
                PillButton(stringResource(R.string.debug_open_tailscale), primary = false, modifier = Modifier.fillMaxWidth(), onClick = actions::openTailscale)
            }
            else -> {
                val command = "adb connect $address:${TailnetRelay.ADB_PORT}"
                CommandBox(command)
                CopyShare(command, actions)
                when {
                    relay.error -> Text(stringResource(R.string.debug_relay_failed), style = MaterialTheme.typography.bodySmall, color = Lumen.warning)
                    !debug.reachableFromPhone -> Text(stringResource(R.string.debug_waiting_network), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
                    else -> Text(stringResource(R.string.debug_tailscale_only), style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder)
                }
            }
        }
    }
}

/** Authorizing a computer: the code chosen here, the glasses' window, `adb pair` to run. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingSheet(
    code: String,
    debug: DebugStatus,
    viaTailscale: Boolean,
    relay: TailnetRelay.State,
    actions: CompanionActions,
    onDismiss: () -> Unit,
) {
    val tailscale = viaTailscale && relay.address != null
    var useTailscale by rememberSaveable { mutableStateOf(tailscale) }
    // Counted here from when the window showed up (the glasses' clock may differ).
    var left by remember { mutableIntStateOf((SettingsOps.ADB_PAIRING_WINDOW_MS / 1000).toInt()) }
    var opened by remember { mutableStateOf(false) }
    val open = debug.pairingPort > 0
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        opened = true
        while (left > 0) {
            delay(1_000)
            left--
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Lumen.surface,
        shape = RoundedCornerShape(topStart = Lumen.radiusCard, topEnd = Lumen.radiusCard),
    ) {
        Column(
            Modifier.padding(horizontal = Lumen.spacingMedLg).padding(bottom = Lumen.spacingLarge).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Lumen.spacingMedium),
        ) {
            Text(stringResource(R.string.debug_authorize), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.pair_text), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
            if (tailscale) {
                Segmented(
                    listOf(stringResource(R.string.pair_via_tailscale), stringResource(R.string.debug_same_network)),
                    if (useTailscale) 0 else 1,
                ) { useTailscale = it == 0 }
            }
            val error = when (debug.pairingError) {
                DebugStatus.PAIRING_NO_WIFI -> R.string.pair_error_no_wifi
                DebugStatus.PAIRING_NOT_ARMED -> R.string.pair_error_not_armed
                DebugStatus.PAIRING_FAILED -> R.string.pair_error_failed
                else -> null
            }
            when {
                error != null && !open -> Text(stringResource(error), style = MaterialTheme.typography.bodyMedium, color = Lumen.warning)
                opened && (!open || left == 0) -> Text(stringResource(R.string.pair_closed), style = MaterialTheme.typography.bodyMedium, color = Lumen.textSecondary)
                !open -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed)) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Lumen.accent, strokeWidth = 2.dp)
                    Text(stringResource(R.string.pair_opening), style = MaterialTheme.typography.bodyMedium, color = Lumen.textSecondary)
                }
                else -> {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Lumen.elevation1).padding(Lumen.spacingMedium),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(stringResource(R.string.pair_code), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
                        Text(code, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp))
                    }
                    val command = if (useTailscale && tailscale) "adb pair ${relay.address}:${TailnetRelay.PAIRING_PORT} $code"
                    else "adb pair ${debug.address}:${debug.pairingPort} $code"
                    CommandBox(command)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
                        Icon(LumenIcons.timer, contentDescription = null, modifier = Modifier.size(18.dp), tint = Lumen.textSecondary)
                        Text(stringResource(R.string.pair_valid, "%d:%02d".format(left / 60, left % 60)), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
                        PillButton(stringResource(R.string.debug_copy), primary = true, icon = LumenIcons.copy, modifier = Modifier.weight(1f)) { actions.copyText(command) }
                        PillButton(stringResource(R.string.action_cancel), primary = false, modifier = Modifier.weight(1f), onClick = onDismiss)
                    }
                    Text(stringResource(R.string.pair_after), style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder)
                }
            }
            if (!open) PillButton(stringResource(R.string.action_cancel), primary = false, modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}

@Composable
private fun AutoOffDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lumen.elevation1,
        title = { Text(stringResource(R.string.debug_auto_off_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                AutoOff.CHOICES.forEach { hours ->
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).selectable(selected = hours == current, role = Role.RadioButton) { onPick(hours) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
                    ) {
                        RadioButton(selected = hours == current, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Lumen.accent))
                        Text(autoOffLabel(hours), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Lumen.textPrimary) } },
    )
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(Lumen.window).border(1.dp, Lumen.textPrimary.copy(alpha = 0.25f), CircleShape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            Box(
                Modifier.weight(1f).height(36.dp).clip(CircleShape).background(if (i == selected) Lumen.elevation2 else Color.Transparent)
                    .selectable(selected = i == selected, role = Role.Tab) { onPick(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (i == selected) Lumen.textPrimary else Lumen.textSecondary)
            }
        }
    }
}

/** The glasses' screen on this phone ([GlassesMirror]). */
@Composable
internal fun GlassesScreenPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var phase by remember { mutableStateOf(GlassesMirror.State()) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var smooth by rememberSaveable { mutableStateOf(false) }
    var green by rememberSaveable { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val mirror = remember(attempt) { GlassesMirror(context.applicationContext) { phase = it } }
    var surface by remember { mutableStateOf<Surface?>(null) }
    DisposableEffect(mirror, surface) {
        surface?.let { mirror.start(it, if (smooth) GlassesMirror.Quality.SMOOTH else GlassesMirror.Quality.LIGHT) }
        onDispose { mirror.stop() }
    }
    LaunchedEffect(paused) { mirror.setPaused(paused) }
    LaunchedEffect(smooth) { mirror.setQuality(if (smooth) GlassesMirror.Quality.SMOOTH else GlassesMirror.Quality.LIGHT) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = onBack) { Icon(LumenIcons.back, contentDescription = stringResource(R.string.action_back), tint = Lumen.textPrimary) }
        Text(stringResource(R.string.debug_screen), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        when (phase.phase) {
            GlassesMirror.Phase.LIVE -> StatusPill(stringResource(R.string.screen_live), Lumen.positive)
            GlassesMirror.Phase.CONNECTING -> StatusPill(stringResource(R.string.screen_connecting), Lumen.warning)
            GlassesMirror.Phase.PAUSED, GlassesMirror.Phase.ASLEEP -> StatusPill(stringResource(R.string.screen_paused), Lumen.textPlaceholder)
            GlassesMirror.Phase.ERROR -> StatusPill(stringResource(R.string.screen_no_picture), Lumen.negative)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
    Box(
        Modifier.fillMaxWidth(0.88f).aspectRatio(3f / 4f).clip(RoundedCornerShape(Lumen.radiusCard))
            .background(Color.Black).border(1.dp, Lumen.textPrimary.copy(alpha = 0.25f), RoundedCornerShape(Lumen.radiusCard)),
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            surface = Surface(texture)
                        }
                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            surface = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            },
            update = { view -> view.setLayerPaint(if (green) GREEN else null) },
            modifier = Modifier.fillMaxSize().let { if (phase.phase == GlassesMirror.Phase.ASLEEP) it.background(Color.Black) else it },
        )
        when (phase.phase) {
            GlassesMirror.Phase.CONNECTING -> Overlay {
                CircularProgressIndicator(Modifier.size(40.dp), color = Lumen.accent, strokeWidth = 3.dp)
                Text(stringResource(R.string.screen_starting), style = MaterialTheme.typography.bodyMedium, color = Lumen.textSecondary)
            }
            GlassesMirror.Phase.ASLEEP -> Overlay(scrim = true) {
                Text(stringResource(R.string.screen_asleep), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                PillButton(stringResource(R.string.screen_wake), primary = true, icon = LumenIcons.sun) { mirror.wake() }
            }
            GlassesMirror.Phase.ERROR -> Overlay(scrim = true) {
                Icon(LumenIcons.wifiOff, contentDescription = null, modifier = Modifier.size(40.dp), tint = Lumen.textSecondary)
                val (title, hint) = when (phase.error) {
                    GlassesAdb.Reason.NO_WIFI -> R.string.screen_error_wifi to R.string.screen_error_wifi_hint
                    GlassesAdb.Reason.NOT_PAIRED -> R.string.screen_error_pair to R.string.screen_error_pair_hint
                    else -> R.string.screen_error_reach to R.string.screen_error_reach_hint
                }
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary, textAlign = TextAlign.Center)
                PillButton(stringResource(R.string.screen_retry), primary = true, icon = LumenIcons.refresh) { attempt++ }
            }
            else -> Unit
        }
    }
    }
    if (phase.phase == GlassesMirror.Phase.ASLEEP) {
        Text(stringResource(R.string.screen_asleep_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder)
    }
    val working = phase.phase == GlassesMirror.Phase.LIVE || phase.phase == GlassesMirror.Phase.PAUSED
    Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
        PillButton(
            stringResource(if (paused) R.string.screen_resume else R.string.screen_pause), primary = false,
            icon = if (paused) LumenIcons.play else LumenIcons.pause, enabled = working, modifier = Modifier.weight(1f),
        ) { paused = !paused }
        PillButton(stringResource(R.string.screen_save), primary = false, icon = LumenIcons.download, enabled = working, modifier = Modifier.weight(1f)) {
            mirror.saveImage { ok -> Toast.makeText(context, if (ok) R.string.screen_saved else R.string.screen_save_failed, Toast.LENGTH_SHORT).show() }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Segmented(listOf(stringResource(R.string.screen_light), stringResource(R.string.screen_smooth)), if (smooth) 1 else 0) { smooth = it == 1 }
        Text(stringResource(R.string.screen_quality_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder)
    }
    Group {
        SwitchRow(stringResource(R.string.screen_green), stringResource(R.string.screen_green_hint), checked = green, onChange = { green = it })
    }
}

@Composable
private fun Overlay(scrim: Boolean = false, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().let { if (scrim) it.background(Color.Black.copy(alpha = 0.75f)) else it }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(Lumen.spacingLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Lumen.spacingMedium),
        ) { content() }
    }
}

/** The lens' look: brightness as green, the way the glasses' display shows it. */
private val GREEN = Paint().apply {
    colorFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.04f, 0.08f, 0.02f, 0f, 0f,
                0.30f, 0.59f, 0.11f, 0f, 0f,
                0.08f, 0.15f, 0.03f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

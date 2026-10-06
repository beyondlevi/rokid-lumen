package dev.lumen.companion.ui

import dev.lumen.protocol.Setting
import java.util.Date
import androidx.compose.ui.platform.LocalContext
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.lumen.companion.LinkState
import dev.lumen.companion.R
import dev.lumen.protocol.BandStatus
import dev.lumen.protocol.GridEvent
import dev.lumen.protocol.GridItem
import dev.lumen.protocol.SettingsEvent

/** What the screens show; the activity fills it from the service and the preferences. */
data class CompanionUiState(
    val link: LinkState = LinkState.STOPPED,
    val authorized: Boolean = false,
    val notificationAccess: Boolean = false,
    val modelDownloaded: Boolean = false,
    /** The language of the Vosk model the dictation language picks, in its own name. */
    val modelLanguage: String = "",
    val modelProgress: String? = null,
    val sendNotifications: Boolean = true,
    val bannerOnlyScreenOff: Boolean = true,
    val hideText: Boolean = false,
    val focusBanner: Boolean = false,
    /** The glasses' banner snooze end (wall clock), 0 when off. */
    val snoozedUntil: Long = 0L,
    val blockedCount: Int = 0,
    val message: String? = null,
    val version: String = "",
    val bandSchema: SettingsEvent.Schema? = null,
    val bandStatus: BandStatus = BandStatus(),
    /** The band is used on this phone ([dev.lumen.companion.PhoneBand]). */
    val bandOnPhone: Boolean = false,
    /** The phone's own link to the band, while it's here. */
    val phoneBandStatus: BandStatus = BandStatus(),
    val bandKeyPresent: Boolean = false,
    val bluetoothGranted: Boolean = false,
    /** What the band's gestures do on the phone ([dev.lumen.companion.PhoneSettings]). */
    val phoneSettings: List<Setting> = emptyList(),
    val touchEnabled: Boolean = false,
    /** Lumen's handwriting keyboard is turned on in the system's keyboard settings. */
    val handwritingKeyboardOn: Boolean = false,
    val writeSettingsGranted: Boolean = false,
    val bandError: SettingsEvent.Result? = null,
    val gridItems: List<GridItem> = emptyList(),
    val gridAvailable: List<GridItem> = emptyList(),
    val gridKnown: Boolean = false,
    val gridIcons: Map<String, android.graphics.Bitmap> = emptyMap(),
    val gridError: GridEvent.Result? = null,
    /** The offline package being handed over from this phone, or the last one's outcome. */
    val packageTransfer: dev.lumen.companion.PackageShare.Transfer? = null,
    /** The glasses' wireless debugging, and the value asked for while they haven't confirmed it. */
    val glassesDebug: dev.lumen.protocol.DebugStatus = dev.lumen.protocol.DebugStatus(),
    val glassesDebugPending: Boolean? = null,
    /** Whether this phone's Wi-Fi is on the glasses' network; null when unknown. */
    val glassesDebugSameNetwork: Boolean? = null,
    val dictation: DictationUiState = DictationUiState(),
    /** Updates from GitHub's releases, for this companion and the glasses app. */
    val update: dev.lumen.companion.update.UpdateManager.State = dev.lumen.companion.update.UpdateManager.State(),
    /** "Share logs" in progress, ready or failed. */
    val logs: dev.lumen.companion.LogShare.State = dev.lumen.companion.LogShare.State.Idle,
    /** The text field focused in the glasses' web app, for the companion's keyboard. */
    val keyboardField: dev.lumen.protocol.KeyboardField = dev.lumen.protocol.KeyboardField(false),
    /** First setup with no computer ([dev.lumen.companion.GlassesSetup]). */
    val setup: dev.lumen.companion.GlassesSetup.State = dev.lumen.companion.GlassesSetup.State(),
    /** Generating the band's key with a Meta account ([dev.lumen.companion.BandClaim]). */
    val claim: dev.lumen.companion.BandClaim.State = dev.lumen.companion.BandClaim.State.Idle,
    /** A debug build: the claim's test that touches nothing is offered. */
    val debuggable: Boolean = false,
)

/** What the screens can ask for. */
interface CompanionActions {
    fun authorize()
    fun reconnect()
    fun stop()
    fun downloadModel()
    fun openNotificationAccess()
    fun setSendNotifications(on: Boolean)
    fun setBannerOnlyScreenOff(on: Boolean)
    fun setHideText(on: Boolean)
    fun setFocusBanner(on: Boolean)
    fun setSnooze(on: Boolean)
    fun chooseBlockedApps()
    fun sendTestNotification()
    /** Ask the glasses for the band's settings again. */
    fun refreshBand()
    fun setBandSetting(key: String, value: String)
    fun bandAction(name: String)
    /** Ask the glasses for the grid again. */
    fun refreshGrid()
    /** The companion's keyboard for the glasses' web apps ([dev.lumen.companion.KeyboardLink]). */
    /** First setup: install the glasses app through Rokid's link, open its setup entry there, ask again. */
    fun installGlasses()
    fun openSetupOnGlasses()
    fun checkSetup()
    /** Setup, on the glasses: run the self-arm; open Hi Rokid (USB debugging, Wi-Fi); send the band's key. */
    fun prepareGlasses()
    /** Sign in to Meta and claim the band; [dryRun] stops before anything is sent to Meta. */
    fun claimWithMeta(dryRun: Boolean)
    fun cancelClaim()
    fun openHiRokid()
    fun sendBandKey()
    /** The system's keyboard settings (to turn on the handwriting keyboard), and its picker. */
    fun openKeyboardSettings()
    fun chooseKeyboard()
    fun exportBandKey()
    fun keyboardOpen()
    fun keyboardClose()
    fun keyboardText(text: String)
    fun keyboardEnter()
    /** The grid in this order (the rest hidden as before), after a drag in the Apps tab. */
    fun reorderGrid(order: List<String>)
    fun hideGridItem(id: String)
    fun addGridItem(id: String)
    fun addWebApp(url: String, name: String)
    fun addPackage(url: String)
    /** Pick a .zip on this phone and install it on the glasses. */
    fun pickPackageFile()
    /** Pick a .zip on this phone to update the offline app [id] with. */
    fun replacePackage(id: String)
    fun dismissPackageTransfer()
    fun setWirelessDebug(on: Boolean)
    fun copyAdbCommand()
    fun shareAdbCommand()
    fun useBandOnPhone()
    fun useBandOnGlasses()
    fun importBandKey()
    fun allowBluetooth()
    fun setPhoneSetting(key: String, value: String)
    fun openTouchSettings()
    fun allowWriteSettings()
    fun setGridEngine(id: String, engine: String)
    /** One of a web app's configuration values; empty clears it. */
    fun setGridConfig(id: String, key: String, value: String)
    fun deleteWebApp(id: String)
    /** Gives a web app a name that updates keep. */
    fun renameWebApp(id: String, name: String)
    /** Installs a web app a second time under [name] (its own data and settings). */
    fun copyWebApp(id: String, name: String)
    fun setSpeechEngine(engine: dev.lumen.companion.speech.SpeechEngine)
    fun setSpeechLanguage(language: dev.lumen.companion.speech.SpeechLanguage)
    fun setSpeechPatience(patience: dev.lumen.companion.speech.SpeechPatience)
    fun saveSpeechKey(provider: dev.lumen.companion.speech.SpeechProvider, key: String)
    fun removeSpeechKey(provider: dev.lumen.companion.speech.SpeechProvider)
    fun saveAzureRegion(region: String)
    /** The phone's microphone permission (the Android recognizer checks it). */
    fun allowMicrophone()
    fun checkUpdates()
    fun updateAll()
    fun cancelUpdate()
    fun dismissUpdate()
    fun setAutoUpdate(on: Boolean)
    fun setBetaUpdates(on: Boolean)
    /** Android's "install unknown apps" for this app (its own updates). */
    fun allowInstalls()
    fun openWifiSettings()
    /** Gathers both apps' logs into a zip and shares it. */
    fun shareLogs()
}

/** A page over the tabs: the updates, or one release's notes (`notes:<tag>`). */
const val PAGE_UPDATES = "updates"
const val PAGE_NOTES = "notes"
const val PAGE_KEYBOARD = "keyboard"
const val PAGE_SETUP = "setup"

/** Set while a list row is dragged: the page doesn't scroll under the finger meanwhile. */
internal val LocalScrollLock = androidx.compose.runtime.staticCompositionLocalOf { mutableStateOf(false) }

private enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, LumenIcons.home),
    APPS(R.string.tab_apps, LumenIcons.grid),
    BAND(R.string.tab_band, LumenIcons.band),
    NOTIFICATIONS(R.string.tab_notifications, LumenIcons.bell),
    SETTINGS(R.string.tab_settings, LumenIcons.gear),
}

@Composable
fun CompanionApp(state: CompanionUiState, actions: CompanionActions, startPage: String? = null) {
    var tab by rememberSaveable { mutableStateOf(if (startPage != null) Tab.SETTINGS else Tab.HOME) }
    var page by rememberSaveable(startPage) { mutableStateOf(startPage) }
    androidx.activity.compose.BackHandler(enabled = page != null) {
        page = if (page?.startsWith(PAGE_NOTES) == true && tab == Tab.SETTINGS) PAGE_UPDATES else null
    }
    val scrollLock = remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Lumen.window,
        bottomBar = {
            NavigationBar(containerColor = Lumen.bar) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = {
                            tab = entry
                            page = null
                            if (entry == Tab.BAND) actions.refreshBand()
                            if (entry == Tab.APPS) actions.refreshGrid()
                        },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(stringResource(entry.label), style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Lumen.accent,
                            selectedTextColor = Lumen.accent,
                            unselectedIconColor = Lumen.textPlaceholder,
                            unselectedTextColor = Lumen.textPlaceholder,
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState(), enabled = !scrollLock.value)
                .padding(horizontal = Lumen.spacingMedium, vertical = Lumen.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(Lumen.spacingMedium),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalScrollLock provides scrollLock) {
            val current = page
            when {
                current == PAGE_SETUP -> SetupPage(state, actions, onBack = { page = null })
                current == PAGE_KEYBOARD -> KeyboardPage(state.keyboardField, state.link.healthy, actions, onBack = { page = null })
                current == PAGE_UPDATES -> UpdatesPage(state.update, actions, onNotes = { page = PAGE_NOTES }, onBack = { page = null })
                current != null && current.startsWith(PAGE_NOTES) -> NotesPage(
                    state.update, current.substringAfter(':', "").ifEmpty { null }, actions,
                    onPick = { page = "$PAGE_NOTES:$it" },
                    onBack = { page = if (tab == Tab.SETTINGS) PAGE_UPDATES else null },
                )
                else -> when (tab) {
                Tab.HOME -> HomeScreen(state, actions, onKeyboard = { page = PAGE_KEYBOARD }, onSetup = { page = PAGE_SETUP }) { page = PAGE_NOTES }
                Tab.APPS -> AppsScreen(state.gridItems, state.gridAvailable, state.gridKnown, state.gridIcons, state.gridError, state.packageTransfer, actions)
                Tab.BAND -> BandScreen(state, actions)
                Tab.NOTIFICATIONS -> NotificationsScreen(state, actions)
                Tab.SETTINGS -> SettingsScreen(state, actions, onSetup = { page = PAGE_SETUP }) { page = PAGE_UPDATES }
                }
            }
            }
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary, modifier = Modifier.padding(horizontal = Lumen.spacingSmall))
            }
        }
    }
}

@Composable
private fun HomeScreen(state: CompanionUiState, actions: CompanionActions, onKeyboard: () -> Unit, onSetup: () -> Unit, onUpdate: () -> Unit) {
    Header(stringResource(R.string.home_title), stringResource(R.string.home_subtitle))
    if (!state.setup.done) SetupCard(state.setup, onSetup)
    state.update.offered?.let { UpdateCard(it, onUpdate) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Lumen.spacingMedium)) {
            Box(
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(Lumen.radiusRow)).background(Lumen.elevation1),
                contentAlignment = Alignment.Center,
            ) { Icon(LumenIcons.glasses, contentDescription = null, modifier = Modifier.size(32.dp), tint = Lumen.textPrimary) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.home_glasses), style = MaterialTheme.typography.titleLarge)
                StatusPill(stringResource(state.link.label), if (state.link.healthy) Lumen.positive else Lumen.warning)
            }
        }
    }
    BandStatusCard(state.bandStatus)
    SectionTitle(stringResource(R.string.home_now))
    Group {
        ListRow(
            title = stringResource(R.string.home_dictation),
            subtitle = engineName(state.dictation.engine) + " · " +
                stringResource(if (state.dictation.missing == null) R.string.speech_ready else R.string.speech_needs_setup),
            icon = LumenIcons.mic,
        )
        ListRow(
            title = stringResource(R.string.keyboard_title),
            subtitle = if (state.keyboardField.focused) {
                stringResource(R.string.home_keyboard_field, fieldName(state.keyboardField), state.keyboardField.app)
            } else {
                stringResource(R.string.home_keyboard_no_field)
            },
            icon = LumenIcons.keyboard,
            onClick = onKeyboard,
        )
        ListRow(
            title = stringResource(R.string.notifications_title),
            subtitle = stringResource(
                when {
                    !state.notificationAccess -> R.string.home_notifications_no_access
                    state.sendNotifications -> R.string.home_notifications_on
                    else -> R.string.home_notifications_off
                },
            ),
            icon = LumenIcons.bell,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed), modifier = Modifier.fillMaxWidth()) {
        if (state.authorized) {
            PillButton(stringResource(R.string.action_stop), primary = false, modifier = Modifier.weight(1f)) { actions.stop() }
            PillButton(stringResource(R.string.action_reconnect), primary = true, icon = LumenIcons.refresh, modifier = Modifier.weight(1f)) { actions.reconnect() }
        } else {
            PillButton(stringResource(R.string.action_authorize), primary = true, icon = LumenIcons.link, modifier = Modifier.weight(1f)) { actions.authorize() }
        }
    }
}

@Composable
private fun NotificationsScreen(state: CompanionUiState, actions: CompanionActions) {
    Header(stringResource(R.string.notifications_title), stringResource(R.string.notifications_subtitle))
    Group {
        SwitchRow(stringResource(R.string.notifications_send), null, state.sendNotifications, actions::setSendNotifications)
        SwitchRow(
            stringResource(R.string.notifications_banner_screen_off),
            stringResource(R.string.notifications_banner_screen_off_hint),
            state.bannerOnlyScreenOff,
            actions::setBannerOnlyScreenOff,
        )
        SwitchRow(stringResource(R.string.notifications_hide_text), stringResource(R.string.notifications_hide_text_hint), state.hideText, actions::setHideText)
        SwitchRow(stringResource(R.string.notifications_focus), stringResource(R.string.notifications_focus_hint), state.focusBanner, actions::setFocusBanner)
        SwitchRow(
            stringResource(R.string.notifications_snooze),
            if (state.snoozedUntil > 0) {
                stringResource(R.string.notifications_snooze_until, DateFormat.getTimeFormat(LocalContext.current).format(Date(state.snoozedUntil)))
            } else {
                stringResource(R.string.notifications_snooze_hint)
            },
            state.snoozedUntil > 0,
            actions::setSnooze,
        )
    }
    SectionTitle(stringResource(R.string.notifications_apps))
    Group {
        ListRow(
            title = stringResource(R.string.notifications_blocked),
            subtitle = stringResource(R.string.notifications_blocked_hint),
            value = if (state.blockedCount == 0) stringResource(R.string.notifications_blocked_none) else pluralStringResource(R.plurals.notifications_blocked_count, state.blockedCount, state.blockedCount),
            onClick = actions::chooseBlockedApps,
        )
        ListRow(
            title = stringResource(R.string.notifications_access),
            trailing = {
                StatusPill(
                    stringResource(if (state.notificationAccess) R.string.notifications_access_granted else R.string.notifications_access_missing),
                    if (state.notificationAccess) Lumen.positive else Lumen.warning,
                )
            },
            onClick = actions::openNotificationAccess,
        )
    }
    PillButton(stringResource(R.string.notifications_test), primary = false, icon = LumenIcons.bell, modifier = Modifier.fillMaxWidth()) { actions.sendTestNotification() }
}

@Composable
private fun SettingsScreen(state: CompanionUiState, actions: CompanionActions, onSetup: () -> Unit, onUpdates: () -> Unit) {
    Header(stringResource(R.string.settings_title), null)
    SectionTitle(stringResource(R.string.settings_link))
    Group {
        ListRow(
            title = stringResource(R.string.settings_hi_rokid),
            subtitle = stringResource(R.string.settings_hi_rokid_hint),
            icon = LumenIcons.link,
            trailing = {
                StatusPill(
                    stringResource(if (state.authorized) R.string.settings_authorized else R.string.settings_not_authorized),
                    if (state.authorized) Lumen.positive else Lumen.warning,
                )
            },
        )
        ListRow(title = stringResource(if (state.authorized) R.string.settings_authorize_again else R.string.action_authorize), onClick = actions::authorize)
        ListRow(
            title = stringResource(R.string.setup_title),
            subtitle = stringResource(if (state.setup.done) R.string.setup_settings_done else R.string.setup_settings_hint),
            icon = LumenIcons.glasses,
            onClick = onSetup,
        )
    }
    DictationSection(state, actions)
    WirelessDebugSection(state, actions)
    SectionTitle(stringResource(R.string.settings_about))
    Group {
        ListRow(title = stringResource(R.string.app_name), subtitle = stringResource(R.string.settings_version, state.version))
        ListRow(
            title = stringResource(R.string.updates_title),
            subtitle = state.update.offered?.let { stringResource(R.string.updates_available, it.version.toString()) }
                ?: stringResource(R.string.updates_up_to_date),
            icon = LumenIcons.download,
            onClick = onUpdates,
        )
        val logs = state.logs
        ListRow(
            title = stringResource(R.string.logs_share),
            subtitle = when (logs) {
                is dev.lumen.companion.LogShare.State.Collecting ->
                    if (logs.total == 0) stringResource(R.string.logs_asking) else stringResource(R.string.logs_receiving, logs.done, logs.total)
                is dev.lumen.companion.LogShare.State.Ready ->
                    if (logs.withGlasses) logs.file.name else stringResource(R.string.logs_without_glasses, logs.file.name)
                dev.lumen.companion.LogShare.State.Failed -> stringResource(R.string.logs_failed)
                else -> stringResource(R.string.logs_share_hint)
            },
            trailing = {
                if (logs is dev.lumen.companion.LogShare.State.Collecting) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = Lumen.accent, strokeWidth = 2.dp)
                } else {
                    Icon(LumenIcons.download, contentDescription = null, modifier = Modifier.size(20.dp), tint = Lumen.textSecondary)
                }
            },
            onClick = actions::shareLogs,
        )
    }
}

/** The glasses' wireless debugging: the switch, then where adb finds them (or why it can't). */
@Composable
private fun WirelessDebugSection(state: CompanionUiState, actions: CompanionActions) {
    val debug = state.glassesDebug
    val pending = state.glassesDebugPending
    SectionTitle(stringResource(R.string.debug_section))
    Group {
        SwitchRow(
            stringResource(R.string.debug_switch),
            stringResource(R.string.debug_switch_hint),
            checked = pending ?: debug.enabled,
            busy = pending != null,
            onChange = actions::setWirelessDebug,
        )
        if (debug.enabled && pending == null) {
            val problem = when {
                !debug.wifiOn -> stringResource(R.string.debug_wifi_off)
                debug.address.isEmpty() -> stringResource(R.string.debug_no_network)
                debug.onPhoneHotspot -> stringResource(R.string.debug_on_hotspot)
                !debug.listening -> stringResource(R.string.debug_port_closed, debug.port)
                else -> null
            }
            Column(
                Modifier.fillMaxWidth().background(Lumen.surface).padding(horizontal = Lumen.spacingMedium, vertical = Lumen.spacingSmMed),
                verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
            ) {
                if (debug.address.isNotEmpty()) {
                    Text(
                        if (debug.ssid.isNotEmpty()) stringResource(R.string.debug_where, debug.ssid) else stringResource(R.string.debug_where_unnamed),
                        style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary,
                    )
                    Text(
                        debug.command,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Lumen.elevation1).padding(Lumen.spacingSmMed),
                    )
                }
                if (problem != null) {
                    Text(problem, style = MaterialTheme.typography.bodySmall, color = Lumen.warning)
                } else if (state.glassesDebugSameNetwork == false) {
                    Text(stringResource(R.string.debug_other_network), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
                }
                if (debug.address.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
                        PillButton(stringResource(R.string.debug_copy), primary = true, modifier = Modifier.weight(1f), onClick = actions::copyAdbCommand)
                        PillButton(stringResource(R.string.debug_share), primary = false, modifier = Modifier.weight(1f), onClick = actions::shareAdbCommand)
                    }
                }
                Text(stringResource(R.string.debug_notification_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textPlaceholder)
            }
        }
    }
}

@Composable
internal fun Header(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = Lumen.spacingSmall, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Lumen.textSecondary)
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Lumen.textPlaceholder,
        modifier = Modifier.padding(start = Lumen.spacingSmall, end = Lumen.spacingSmall, top = Lumen.spacingSmall),
    )
}

@Composable
internal fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Lumen.radiusCard)).background(Lumen.surface).padding(Lumen.spacingMedLg),
        verticalArrangement = Arrangement.spacedBy(Lumen.spacingMedium),
    ) { content() }
}

@Composable
internal fun Group(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Lumen.radiusCard)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

@Composable
internal fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(Lumen.surface)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Lumen.spacingMedium, vertical = Lumen.spacingSmMed),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
    ) {
        if (icon != null) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Lumen.elevation2), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = Lumen.textPrimary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
        }
        trailing?.invoke()
        if (value != null || (onClick != null && trailing == null)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = Lumen.textPlaceholder)
                Icon(LumenIcons.chevron, contentDescription = null, modifier = Modifier.size(18.dp), tint = Lumen.textPlaceholder)
            }
        }
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, busy: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(Lumen.surface)
            .clickable(role = Role.Switch, enabled = !busy) { onChange(!checked) }
            .padding(horizontal = Lumen.spacingMedium, vertical = Lumen.spacingSmMed),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmMed),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
        }
        // Waiting on the other side: a spinner where the switch was, until it answers.
        if (busy) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = Lumen.accent, strokeWidth = 2.dp)
            return@Row
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Lumen.accent,
                checkedThumbColor = Lumen.textPrimary,
                uncheckedTrackColor = Lumen.elevation2,
                uncheckedThumbColor = Lumen.textPrimary,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
internal fun StatusPill(text: String, dot: Color) {
    Row(
        modifier = Modifier.clip(CircleShape).background(Lumen.elevation1).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Lumen.textPrimary)
    }
}

@Composable
internal fun PillButton(text: String, primary: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Lumen.accent else Lumen.elevation1,
            contentColor = Lumen.textPrimary,
        ),
        contentPadding = PaddingValues(horizontal = Lumen.spacingMedLg),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Lumen.spacingSmall))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

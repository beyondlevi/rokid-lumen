package dev.lumen.companion

import dev.lumen.protocol.BandStatus
import dev.lumen.band.Identity
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import dev.lumen.companion.computer.ComputerKeys
import dev.lumen.companion.computer.ComputerLink
import dev.lumen.companion.computer.ComputerPointer
import dev.lumen.companion.computer.ComputerProfiles
import dev.lumen.companion.computer.ComputerWriter
import dev.lumen.companion.ui.CompanionActions
import dev.lumen.companion.ui.ComputerUiState
import dev.lumen.companion.ui.CompanionApp
import dev.lumen.companion.ui.CompanionUiState
import dev.lumen.companion.ui.DictationUiState
import dev.lumen.companion.speech.SpeechEngine
import dev.lumen.companion.speech.SpeechLanguage
import dev.lumen.companion.speech.SpeechPatience
import dev.lumen.companion.speech.SpeechProvider
import dev.lumen.companion.speech.SpeechSecrets
import dev.lumen.companion.speech.SpeechSettings
import dev.lumen.companion.ui.LumenTheme
import dev.lumen.protocol.GridOps
import dev.lumen.protocol.SettingsOps

/**
 * Authorizes this app with Hi Rokid (the link to the glasses and their microphone), prepares
 * the speech model, grants notification access and starts [CompanionService]. After that the
 * phone needs no attention: notifications reach the glasses, and the glasses app asks for
 * dictation when a web app's text field is activated. The screens are [CompanionApp].
 */
class CompanionActivity : ComponentActivity(), CompanionActions {
    private val state = mutableStateOf(CompanionUiState())
    private val listener: (LinkState) -> Unit = { refresh() }
    private val bandListener: () -> Unit = { refresh() }
    private val gridListener: () -> Unit = { refresh() }
    private val snoozeListener: () -> Unit = { refresh() }
    private val snoozeEnded = Runnable { refresh() }
    private val updateListener: () -> Unit = { refresh() }
    /** The logs zip shared once when it's ready (not again on every refresh). */
    private var sharedLogs: java.io.File? = null
    private val logsListener: () -> Unit = {
        (LogShare.state as? LogShare.State.Ready)?.file?.let { file ->
            if (file != sharedLogs) {
                sharedLogs = file
                LogShare.share(this, file)
            }
        }
        refresh()
    }
    /** The page the launching intent asks for (the update notification's). */
    private val startPage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LumenTheme { CompanionApp(state.value, this, startPage.value) }
        }
        startPage.value = intent?.getStringExtra(EXTRA_PAGE)
        askRuntimePermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startPage.value = intent.getStringExtra(EXTRA_PAGE)
    }

    override fun onResume() {
        super.onResume()
        CompanionService.listeners += listener
        BandStore.listeners += bandListener
        PhoneBand.listeners += bandListener
        ComputerLink.listeners += bandListener
        ComputerWriter.listeners += bandListener
        ComputerPointer.listeners += bandListener
        GridCache.listeners += gridListener
        KeyboardLink.listeners += gridListener
        GlassesSetup.listeners += gridListener
        BandClaim.listeners += gridListener
        PhoneSnooze.listeners += snoozeListener
        dev.lumen.companion.update.UpdateManager.listeners += updateListener
        LogShare.listeners += logsListener
        dev.lumen.companion.update.UpdateManager.checkIfDue(this)
        if (CompanionPrefs.token(this) != null) CompanionService.start(this)
        refresh()
        dev.lumen.companion.meta.MetaLoginActivity.lastError?.let {
            dev.lumen.companion.meta.MetaLoginActivity.lastError = null
            say(getString(R.string.claim_sign_in_failed, it))
        }
        // Setup isn't confirmed until the glasses app answers: ask it (and Rokid's link) again.
        if (!GlassesSetup.state(this).done) GlassesSetup.check()
    }

    override fun onPause() {
        CompanionService.listeners -= listener
        BandStore.listeners -= bandListener
        PhoneBand.listeners -= bandListener
        ComputerLink.listeners -= bandListener
        ComputerWriter.listeners -= bandListener
        ComputerPointer.listeners -= bandListener
        GridCache.listeners -= gridListener
        KeyboardLink.listeners -= gridListener
        GlassesSetup.listeners -= gridListener
        BandClaim.listeners -= gridListener
        PhoneSnooze.listeners -= snoozeListener
        dev.lumen.companion.update.UpdateManager.listeners -= updateListener
        LogShare.listeners -= logsListener
        window.decorView.removeCallbacks(snoozeEnded)
        super.onPause()
    }

    private fun refresh(message: String? = state.value.message, modelProgress: String? = state.value.modelProgress) {
        state.value = CompanionUiState(
            link = CompanionService.state,
            authorized = CompanionPrefs.token(this) != null,
            notificationAccess = hasNotificationAccess(),
            modelDownloaded = PhoneModel.isDownloaded(this),
            modelLanguage = PhoneModel.chosen(this).language.nativeName,
            modelProgress = modelProgress,
            sendNotifications = CompanionPrefs.notificationsEnabled(this),
            bannerOnlyScreenOff = CompanionPrefs.pauseWhileScreenOn(this),
            hideText = CompanionPrefs.hideContent(this),
            focusBanner = CompanionPrefs.focusBanner(this),
            snoozedUntil = if (PhoneSnooze.isActive()) PhoneSnooze.until else 0L,
            blockedCount = CompanionPrefs.blockedApps(this).size,
            message = message,
            version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty(),
            bandSchema = BandStore.schema,
            bandStatus = BandStore.status,
            bandOnPhone = CompanionPrefs.bandOnPhone(this),
            phoneBandStatus = BandStatus(
                phase = PhoneBand.phase.name.lowercase(),
                name = Identity.bandName(this).orEmpty(),
                battery = PhoneBand.battery(),
                charging = PhoneBand.status.optBoolean("charging"),
                paused = PhoneBand.status.optBoolean("paused"),
                onPhone = true,
            ),
            bandKeyPresent = Identity.present(this),
            bluetoothGranted = PhoneBand.problem(this) != PhoneBand.Problem.NO_BLUETOOTH,
            profiles = PhoneProfiles.state(this),
            phoneHand = PhoneSettings.hand(this),
            phoneApps = phoneApps,
            phoneListening = PhoneBand.listening,
            touchEnabled = PhoneTouchService.instance != null,
            handwritingKeyboardOn = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.enabledInputMethodList?.any { it.packageName == packageName } == true,
            writeSettingsGranted = Settings.System.canWrite(this),
            bandError = BandStore.lastError,
            gridItems = GridCache.items,
            gridAvailable = GridCache.available,
            gridKnown = GridCache.known,
            gridIcons = HashMap(GridCache.icons),
            gridError = GridCache.lastError,
            packageTransfer = PackageShare.transfer,
            glassesDebug = BandStore.debug,
            glassesDebugPending = GlassesDebug.pending,
            glassesDebugSameNetwork = GlassesDebug.phoneOnSameNetwork(BandStore.debug.address),
            dictation = DictationUiState(
                engine = SpeechSettings.engine(this),
                language = SpeechSettings.language(this),
                patience = SpeechSettings.patience(this),
                keys = SpeechProvider.entries.filter { SpeechSecrets.hasKey(this, it) }.toSet(),
                azureRegion = SpeechSecrets.azureRegion(this),
                missing = SpeechSettings.missing(this),
            ),
            update = dev.lumen.companion.update.UpdateManager.state(this),
            logs = LogShare.state,
            keyboardField = KeyboardLink.field,
            setup = GlassesSetup.state(this),
            claim = BandClaim.state,
            debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            bandOnComputer = CompanionPrefs.bandOnComputer(this),
            computer = ComputerUiState(
                status = ComputerLink.status,
                name = ComputerLink.computer?.name,
                address = ComputerLink.computer?.address,
                computers = ComputerLink.computers(this),
                pairing = ComputerLink.pairingUntil > 0,
                phoneName = ComputerLink.phoneName(this),
                layout = ComputerProfiles.layout(this),
                invertScroll = ComputerProfiles.invertScroll(this),
                scrollSteps = ComputerProfiles.scrollSteps(this),
                writing = ComputerWriter.writing,
                pointer = ComputerPointer.on,
                pointerSpeed = ComputerProfiles.pointerSpeed(this),
                pointerSteadiness = ComputerProfiles.pointerSteadiness(this),
                pointerBoost = ComputerProfiles.pointerBoost(this),
            ),
            computerProfiles = ComputerProfiles.state(this),
        )
        // The switch turns off by itself when the snooze runs out.
        window.decorView.removeCallbacks(snoozeEnded)
        if (PhoneSnooze.isActive()) window.decorView.postDelayed(snoozeEnded, PhoneSnooze.until - System.currentTimeMillis() + 500)
    }

    private fun say(message: String) = refresh(message = message)

    override fun authorize() {
        // isRokidAppInstalled only knows the Chinese app (com.rokid.sprite.aiapp); the request
        // itself picks the global Hi Rokid (com.rokid.sprite.global.aiapp) when that's the one.
        if (!AuthorizationHelper.isRequiredHiRokidInstalled(this) && !AuthorizationHelper.isRokidAppInstalled(this)) {
            say(getString(R.string.auth_hi_rokid_missing))
        }
        // Not an activity to start: the SDK asks Hi Rokid's content provider and hands back the
        // answer as (resultCode, Intent with auth_result/auth_token), ready to parse.
        val request = AuthorizationHelper.requestAuthorization(this, arrayOf(GlassPermission.MICROPHONE), REQUEST_AUTH)
        if (request == null) {
            say(getString(R.string.auth_no_answer))
            return
        }
        handleAuthorization(request.first ?: RESULT_CANCELED, request.second)
    }

    private fun handleAuthorization(resultCode: Int, data: Intent?) {
        when (val result = if (data == null) null else AuthorizationHelper.parseAuthorizationResult(resultCode, data)) {
            is AuthResult.AuthSuccess -> {
                CompanionPrefs.setToken(this, result.token)
                CompanionPrefs.setPermissions(this, listOf(GlassPermission.MICROPHONE))
                CompanionService.start(this, reconnect = true)
                say(getString(R.string.auth_done))
            }
            else -> say(getString(R.string.auth_failed))
        }
    }

    @Deprecated("Activity result API of the platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_AUTH) handleAuthorization(resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE) refresh()
        // Granted with Bluetooth's other permissions (one group): go on to pairing.
        if (requestCode == REQUEST_ADVERTISE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) pairComputer()
    }

    override fun reconnect() = CompanionService.start(this, reconnect = true)

    override fun checkUpdates() = dev.lumen.companion.update.UpdateManager.check(this)

    override fun updateAll() = dev.lumen.companion.update.UpdateManager.updateAll(this)

    override fun cancelUpdate() = dev.lumen.companion.update.UpdateManager.cancel()

    override fun dismissUpdate() = dev.lumen.companion.update.UpdateManager.dismiss()

    override fun setAutoUpdate(on: Boolean) = dev.lumen.companion.update.UpdateManager.setAutoCheck(this, on)

    override fun setBetaUpdates(on: Boolean) {
        dev.lumen.companion.update.UpdateManager.setIncludeBeta(this, on)
        dev.lumen.companion.update.UpdateManager.check(this)
    }

    override fun allowInstalls() {
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
    }

    override fun openWifiSettings() = startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))

    override fun shareLogs() {
        // A zip already made is shared again; a tap while collecting does nothing.
        when (val logs = LogShare.state) {
            is LogShare.State.Collecting -> Unit
            else -> {
                if (logs is LogShare.State.Ready) LogShare.dismiss()
                LogShare.start(this)
            }
        }
    }

    override fun stop() {
        stopService(Intent(this, CompanionService::class.java))
        refresh()
    }

    override fun downloadModel() {
        PhoneModel.load(this, PhoneModel.chosen(this), onStatus = { refresh(modelProgress = it) }) { _, problem ->
            refresh(message = problem ?: getString(R.string.model_ready), modelProgress = null)
        }
    }

    override fun openNotificationAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    override fun setSendNotifications(on: Boolean) {
        CompanionPrefs.setNotificationsEnabled(this, on)
        NotificationForwarder.requestSync()
        refresh()
    }

    override fun setBannerOnlyScreenOff(on: Boolean) {
        CompanionPrefs.setPauseWhileScreenOn(this, on)
        refresh()
    }

    override fun useBandOnPhone() {
        PhoneBand.useHere(this)
        refresh()
    }

    override fun useBandOnGlasses() {
        PhoneBand.useOnGlasses(this)
        refresh()
    }

    override fun useBandOnComputer() {
        PhoneBand.useOnComputer(this)
        refresh()
    }

    override fun connectComputer(address: String) {
        ComputerLink.connect(this, address)
        refresh()
    }

    override fun forgetComputer(address: String) {
        ComputerLink.forget(this, address)
        refresh()
    }

    /** Android asks the person before the phone shows up in other devices' Bluetooth lists. */
    private val discoverable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // The result code is the time it stays visible, or "canceled".
        if (result.resultCode > 0) ComputerLink.pairingStarted() else say(getString(R.string.computer_pair_refused))
        refresh()
    }

    override fun pairComputer() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE), REQUEST_ADVERTISE)
            return
        }
        discoverable.launch(
            Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, ComputerLink.PAIRING_SECONDS),
        )
    }

    override fun cancelComputerPairing() {
        ComputerLink.cancelPairing()
        refresh()
    }

    override fun openBluetoothSettings() = startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    private fun computerProfilesChanged() {
        PhoneBand.applyMapping(this)
        refresh()
    }

    override fun selectComputerProfile(id: String) {
        PhoneBand.switchComputerProfile(this, id)
        refresh()
    }

    override fun addComputerProfile() {
        val count = ComputerProfiles.state(this).profiles.size + 1
        val id = ComputerProfiles.add(this, getString(R.string.profile_new_name, count), from = null)
        PhoneBand.switchComputerProfile(this, id)
        refresh()
    }

    override fun duplicateComputerProfile(id: String) {
        val from = ComputerProfiles.state(this).profiles.firstOrNull { it.id == id } ?: return
        ComputerProfiles.add(this, getString(R.string.profile_copy, from.name), from)
        refresh()
    }

    override fun removeComputerProfile(id: String) {
        ComputerProfiles.remove(this, id)
        computerProfilesChanged()
    }

    override fun renameComputerProfile(id: String, name: String) {
        ComputerProfiles.edit(this, id) { it.copy(name = name) }
        refresh()
    }

    override fun setComputerAction(id: String, gesture: String, action: String) {
        ComputerProfiles.edit(this, id) { it.copy(actions = it.actions + (gesture to action)) }
        computerProfilesChanged()
    }

    override fun setComputerDial(id: String, dial: String) {
        ComputerProfiles.edit(this, id) { it.copy(dial = dial) }
        refresh()
    }

    override fun setComputerLayout(layout: ComputerKeys.Layout) {
        ComputerProfiles.setLayout(this, layout)
        refresh()
    }

    override fun setComputerInvertScroll(on: Boolean) {
        ComputerProfiles.setInvertScroll(this, on)
        refresh()
    }

    override fun setComputerPointerSpeed(speed: Int) {
        ComputerProfiles.setPointerSpeed(this, speed)
        ComputerPointer.retune(this)
        refresh()
    }

    override fun setComputerPointerSteadiness(steadiness: Float) {
        // The slider's steps are tenths: keep them clean.
        ComputerProfiles.setPointerSteadiness(this, Math.round(steadiness * 10) / 10f)
        ComputerPointer.retune(this)
        refresh()
    }

    override fun setComputerPointerBoost(boost: Float) {
        ComputerProfiles.setPointerBoost(this, Math.round(boost * 10) / 10f)
        ComputerPointer.retune(this)
        refresh()
    }

    override fun setComputerScrollSteps(steps: Int) {
        ComputerProfiles.setScrollSteps(this, steps)
        refresh()
    }

    /** The key file exported by the original phone app or the Linux app (air-gestures-band.json). */
    private val keyPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val contents = uris.mapNotNull { uri -> runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
        val result = Identity.import(this, contents)
        if (Identity.present(this) && CompanionPrefs.bandOnPhone(this)) PhoneBand.start(this)
        say(result)
    }

    override fun importBandKey() = keyPicker.launch(arrayOf("*/*"))

    /** The key held here as an air-gestures-band.json file, for a computer or another phone. */
    private val keyExporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val bundle = Identity.exportBundle(this)
        val written = bundle != null && runCatching { contentResolver.openOutputStream(uri, "wt")?.use { it.write(bundle) } != null }.getOrDefault(false)
        say(getString(if (written) R.string.band_key_exported else R.string.band_key_export_failed))
    }

    override fun exportBandKey() = keyExporter.launch("air-gestures-band.json")

    /** The app a picked package updates (its grid id), or empty for a new one. */
    private var packageTarget = ""

    // Any type: a .mrbd.zip is often typed application/octet-stream; the glasses check the package.
    private val packagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) PackageShare.send(this, uri, packageTarget)
    }

    override fun pickPackageFile() {
        packageTarget = ""
        packagePicker.launch(arrayOf("*/*"))
    }

    override fun replacePackage(id: String) {
        packageTarget = id
        packagePicker.launch(arrayOf("*/*"))
    }

    override fun dismissPackageTransfer() = PackageShare.dismiss()

    override fun setWirelessDebug(on: Boolean) {
        if (!GlassesDebug.request(on)) say(getString(R.string.band_waiting))
        refresh()
    }

    override fun copyAdbCommand() = GlassesDebug.copy(this, BandStore.debug.command)

    override fun shareAdbCommand() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, BandStore.debug.command), null))
    }

    override fun setPhoneSetting(key: String, value: String) {
        if (PhoneSettings.set(this, key, value)) PhoneBand.applyMapping(this)
        refresh()
    }

    /** The launchable apps, read once (the list for "open an app"). */
    private val phoneApps by lazy { PhoneSettings.launchableApps(this) }

    private fun profilesChanged() {
        PhoneBand.applyMapping(this)
        PhoneBand.applyLock(this)
        refresh()
    }

    override fun selectProfile(id: String) {
        PhoneBand.switchProfile(this, id)
        refresh()
    }

    override fun addProfile() {
        val count = PhoneProfiles.state(this).profiles.size + 1
        val id = PhoneProfiles.add(this, getString(R.string.profile_new_name, count), from = null)
        PhoneBand.switchProfile(this, id)
        refresh()
    }

    override fun duplicateProfile(id: String) {
        val from = PhoneProfiles.state(this).profiles.firstOrNull { it.id == id } ?: return
        PhoneProfiles.add(this, getString(R.string.profile_copy, from.name), from)
        refresh()
    }

    override fun removeProfile(id: String) {
        PhoneProfiles.remove(this, id)
        profilesChanged()
    }

    override fun renameProfile(id: String, name: String) {
        PhoneProfiles.edit(this, id) { it.copy(name = name) }
        refresh()
    }

    override fun setProfileAction(id: String, gesture: String, action: String, app: String) {
        PhoneProfiles.edit(this, id) { profile ->
            profile.copy(
                actions = profile.actions + (gesture to action),
                apps = if (action == PhoneSettings.OPEN_APP) profile.apps + (gesture to app) else profile.apps - gesture,
            )
        }
        profilesChanged()
    }

    override fun setProfileDial(id: String, dial: String) {
        PhoneProfiles.edit(this, id) { it.copy(dial = dial) }
        refresh()
    }

    override fun setProfileWhenLocked(id: String, on: Boolean) {
        PhoneProfiles.edit(this, id) { it.copy(whenLocked = on) }
        profilesChanged()
    }

    override fun setSwitchGesture(gesture: String) {
        PhoneProfiles.update(this) { it.copy(switchGesture = gesture) }
        profilesChanged()
    }

    override fun openTouchSettings() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    override fun openKeyboardSettings() = startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))

    override fun chooseKeyboard() {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showInputMethodPicker()
    }

    override fun allowWriteSettings() =
        startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName")))

    override fun allowBluetooth() {
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), REQUEST_PERMISSIONS)
    }

    override fun setSnooze(on: Boolean) {
        if (!CompanionService.requestSnooze(on)) say(getString(R.string.band_waiting))
    }

    override fun setFocusBanner(on: Boolean) {
        CompanionPrefs.setFocusBanner(this, on)
        refresh()
    }

    override fun setHideText(on: Boolean) {
        CompanionPrefs.setHideContent(this, on)
        NotificationForwarder.requestSync()
        refresh()
    }

    /** The apps seen notifying, ticked when their notifications stay on the phone. */
    override fun chooseBlockedApps() {
        val packages = CompanionPrefs.seenApps(this).sortedBy { NotificationForwarder.appLabel(this, it).lowercase() }
        if (packages.isEmpty()) {
            say(getString(R.string.notifications_none_seen))
            return
        }
        val blocked = CompanionPrefs.blockedApps(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.notifications_blocked_dialog)
            .setMultiChoiceItems(
                packages.map { NotificationForwarder.appLabel(this, it) }.toTypedArray(),
                packages.map { it in blocked }.toBooleanArray(),
            ) { _, which, checked -> CompanionPrefs.setBlocked(this, packages[which], checked) }
            .setPositiveButton(R.string.ok) { _, _ ->
                NotificationForwarder.requestSync()
                refresh()
            }
            .show()
    }

    /** A notification of this app's own, which the forwarder lets through (test category). */
    override fun sendTestNotification() {
        TestReplyReceiver.post(this)
        say(getString(if (hasNotificationAccess()) R.string.notifications_test_sent else R.string.notifications_test_needs_access))
    }

    override fun refreshBand() {
        CompanionService.requestSettings(SettingsOps.describe())
    }

    override fun setBandSetting(key: String, value: String) {
        CompanionService.requestSettings(SettingsOps.set(key, value))
    }

    override fun bandAction(name: String) {
        CompanionService.requestSettings(SettingsOps.action(name))
    }

    override fun installGlasses() = dev.lumen.companion.update.UpdateManager.installGlassesFirstTime(this)

    override fun openSetupOnGlasses() = GlassesSetup.openOnGlasses()

    override fun checkSetup() = GlassesSetup.check()

    override fun prepareGlasses() = GlassesSetup.prepareGlasses()

    override fun claimWithMeta(dryRun: Boolean) {
        if (PhoneBand.problem(this) == PhoneBand.Problem.NO_BLUETOOTH) {
            allowBluetooth()
            return
        }
        dev.lumen.companion.meta.MetaLoginActivity.onSession = { session -> BandClaim.start(this, session, dryRun) }
        startActivity(Intent(this, dev.lumen.companion.meta.MetaLoginActivity::class.java))
    }

    override fun cancelClaim() = BandClaim.cancel(this)

    override fun openHiRokid() {
        val launch = listOf("com.rokid.sprite.global.aiapp", "com.rokid.sprite.aiapp")
            .firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) say(getString(R.string.auth_hi_rokid_missing)) else startActivity(launch)
    }

    override fun sendBandKey() {
        if (!GlassesSetup.sendKey(this)) say(getString(R.string.setup_key_not_sent))
    }

    override fun keyboardOpen() = KeyboardLink.open()

    override fun keyboardClose() = KeyboardLink.close()

    override fun keyboardText(text: String) = KeyboardLink.text(text)

    override fun keyboardEnter() = KeyboardLink.enter()

    override fun refreshGrid() {
        CompanionService.requestGrid(GridOps.describe())
    }

    override fun reorderGrid(order: List<String>) {
        CompanionService.requestGrid(GridOps.set(order, GridCache.hiddenFor(order)))
    }

    override fun hideGridItem(id: String) {
        val order = GridCache.items.map { it.id } - id
        CompanionService.requestGrid(GridOps.set(order, GridCache.hiddenFor(order)))
    }

    override fun addGridItem(id: String) {
        val order = GridCache.added(id)
        CompanionService.requestGrid(GridOps.set(order, GridCache.hiddenFor(order)))
    }

    override fun addWebApp(url: String, name: String) {
        CompanionService.requestGrid(GridOps.addWeb(url, name))
    }

    override fun addPackage(url: String) {
        CompanionService.requestGrid(GridOps.addPackage(url))
        say(getString(R.string.apps_package_sent))
    }

    override fun setGridEngine(id: String, engine: String) {
        CompanionService.requestGrid(GridOps.engine(id, engine))
    }

    override fun setGridConfig(id: String, key: String, value: String) {
        CompanionService.requestGrid(GridOps.config(id, key, value))
    }

    override fun setSpeechEngine(engine: SpeechEngine) {
        SpeechSettings.setEngine(this, engine)
        refresh()
        if (SpeechSettings.missing(this, engine) == SpeechSettings.Missing.MICROPHONE) allowMicrophone()
    }

    override fun setSpeechLanguage(language: SpeechLanguage) {
        SpeechSettings.setLanguage(this, language)
        refresh()
    }

    override fun setSpeechPatience(patience: SpeechPatience) {
        SpeechSettings.setPatience(this, patience)
        refresh()
    }

    override fun saveSpeechKey(provider: SpeechProvider, key: String) {
        val saved = SpeechSecrets.setKey(this, provider, key)
        refresh(message = if (saved) null else getString(R.string.speech_key_save_failed))
    }

    override fun removeSpeechKey(provider: SpeechProvider) {
        SpeechSecrets.removeKey(this, provider)
        refresh()
    }

    override fun saveAzureRegion(region: String) {
        val saved = SpeechSecrets.setAzureRegion(this, region)
        refresh(message = if (saved) null else getString(R.string.speech_azure_region_invalid))
    }

    override fun allowMicrophone() = requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)

    override fun deleteWebApp(id: String) {
        CompanionService.requestGrid(GridOps.remove(id))
    }

    override fun renameWebApp(id: String, name: String) {
        CompanionService.requestGrid(GridOps.rename(id, name))
    }

    override fun copyWebApp(id: String, name: String) {
        CompanionService.requestGrid(GridOps.copy(id, name))
    }

    private fun hasNotificationAccess(): Boolean =
        getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, NotificationForwarder::class.java))

    private fun askRuntimePermissions() {
        val missing = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    companion object {
        /** Opens a page over the tabs ([dev.lumen.companion.ui.PAGE_UPDATES]): the update notification. */
        const val EXTRA_PAGE = "page"
        const val PAGE_UPDATES = dev.lumen.companion.ui.PAGE_UPDATES

        private const val REQUEST_AUTH = 7
        private const val REQUEST_MICROPHONE = 8
        private const val REQUEST_PERMISSIONS = 8
        private const val REQUEST_ADVERTISE = 12
        const val TEST_CHANNEL = "test"
        const val TEST_ID = 42
    }
}

# Getting started

This guide takes you from two APKs to a band that drives the glasses. It takes about fifteen
minutes, most of it spent on the band's key and the self-arm.

## Requirements

- **Rokid RG glasses** (YodaOS-Sprite, Android 12, a 480x640 monochrome green HUD), set up with
  the Hi Rokid app. The glasses app needs Android 10 (API 29) or later, and the self-arm needs
  Android 11 (API 30) or later; the RG glasses' Android 12 meets both.
- **A Meta Neural Band**, claimed with [air-gestures](https://gitlab.com/896kb/air-gestures)
  (or another tool that exports the same `air-gestures-band.json` file). Lumen does not claim a
  band itself: Meta's sign-in page doesn't fit the HUD. Claiming the band unlinks it from Meta's
  glasses and app, and the link is unofficial: a band firmware update could break it.
- **An Android phone with Android 12 (API 31) or later**, with the Hi Rokid app installed,
  signed in and connected to the glasses. The companion's Rokid SDK (CXR-L) needs API 31.
- **A computer with adb**, for the first install and to hand the band's key to the glasses.
  Turn on USB debugging for the glasses in the Hi Rokid app's developer settings.
- **A Wi-Fi network the glasses can join**, for the self-arm. Android's Wireless debugging
  only works on Wi-Fi.

## 1. Install both apps

Download the APKs from the [GitHub Releases](https://github.com/beyondlevi/rokid-lumen/releases)
page and check them against `SHA256SUMS.txt`:

```sh
sha256sum -c SHA256SUMS.txt
adb -s <glasses> install rokid-lumen-glasses-<version>.apk
adb -s <phone> install rokid-lumen-companion-<version>.apk
```

To update later, `adb install -r` the new APK over the old one: it keeps the band, the web
apps and the settings.

Never force-stop the glasses app (`am force-stop`, `am start -S`, or *Force stop* in the app's
settings): the firmware then drops its accessibility service and the band with it.

## 2. Authorize the companion in Hi Rokid

Open **Rokid Lumen Companion** on the phone. It asks for its permissions on first start:
allow them. *Nearby devices* covers Bluetooth (the band, if you use it on the phone) and the
local-only hotspot that gives the glasses internet; notifications are for its foreground
service, "Link to the glasses".

1. On the Home tab, tap **Authorize in Hi Rokid** and accept in Hi Rokid. The companion asks
   Hi Rokid for the link to the glasses and for the glasses' microphone (for dictation). If
   it says the authorization wasn't completed, sign in to Hi Rokid and try again.
2. On the Notifications tab, allow **Notification access** if you want the phone's
   notifications on the glasses. **Send a test notification** checks the whole path.
3. On the Settings tab, under Dictation, pick an engine. The default, the Android recognizer,
   needs the phone's microphone permission (it is fed the glasses' audio). The cloud engines
   need an API key; Vosk downloads its offline model on first use.

The link comes back by itself after the phone reboots.

<!-- media: companion-home -->
![The companion's Home tab, authorized and connected to the glasses](media/companion-home.png)

## 3. Give the glasses the band's key

1. Claim the band with air-gestures (`air-gestures pair`) and export its key
   (`air-gestures export`). You get `air-gestures-band.json`. Anyone holding that file controls
   the band: keep it private.
2. Free the band: it talks to one device at a time. Run `air-gestures disconnect` on the
   computer.
3. Copy the file to the glasses:

   ```sh
   scripts/push-band-file.sh air-gestures-band.json
   ```

   It lands in `/sdcard/Android/data/dev.lumen.glasses/files/`.
4. On the glasses, open Rokid Lumen from the Rokid launcher. The apps grid opens; allow the
   Bluetooth permission when asked. Go to the grid's last item, **Settings**, then **Band >
   Import band key**. The app copies the key into its private storage and deletes the file
   from that folder.

The companion can import the same file (Band tab, **Import the key**) if you also want to use
the band on the phone.

## 4. Enable the accessibility service and pair the band

Until the band is connected, move through the glasses' screens with the touchpad.

1. In Settings, open **System > Accessibility** and enable **Rokid Lumen**. The band link
   lives in this service: it starts as soon as the service runs.
2. Put the band in pairing mode (hold its button for 3 seconds) and select **Pair /
   Reconnect** on the Settings home screen. The status line shows *Waiting for the band*,
   *Connecting to the band*, then *Band connected*. Once bonded, the band reconnects on its
   own, without pairing mode.
3. **Band > Gesture guide** lists what each gesture does. The rest of the band's settings
   (the index double tap, pinch and turn, the wrist, power saving) are in the companion's Band
   tab.

## 5. Run the self-arm

The Rokid firmware force-stops the app in front and strips its accessibility service when a
temple is folded or the glasses sleep, and the band goes with it. The self-arm gives the app
the shell privileges it needs to put itself back, and to join the phone's hotspot later.

Before you start: the accessibility service must be on, and Rokid Nexus must not be installed
(it manages ADB on the glasses itself; the self-arm stops and says *Self-arm off: Nexus
manages ADB here*).

1. In Settings, select **Self-arm (no phone)**. From here the app works on its own, through
   the accessibility service. Don't touch the glasses until it ends (up to 75 seconds).
2. It turns Wi-Fi on (*Self-arm: enabling Wi-Fi*) and opens the system Settings.
3. If Developer options are off, it opens the device information screen and taps the build
   number to turn them on (*Self-arm: tapping build number*).
4. It opens Developer options, then **Wireless debugging**, switches it on and confirms
   (*Self-arm: turning Wireless Debugging on*).
5. It opens **Pair device with pairing code**, reads the code and the port from the screen
   (*Self-arm: pairing code ready*), and pairs with the glasses' own adbd over 127.0.0.1
   (*Self-arm: pairing local ADB*).
6. Over that ADB session it installs and starts the two shell helpers, grants itself
   `WRITE_SECURE_SETTINGS`, turns USB debugging on, restarts adbd on TCP port 5555, and turns
   Wi-Fi off again about 12 seconds later.
7. The status line reads **Self-arm complete**.

<!-- media: settings-self-arm -->
![The glasses' Lumen settings, with Self-arm (no phone) and its status line](media/settings-self-arm.png)

If it stops with a message, these are the common ones:

| Status line | What to do |
| --- | --- |
| *Enable USB debugging in Hi Rokid settings* | Turn on USB debugging for the glasses in Hi Rokid's developer settings, then select Self-arm again. |
| *Self-arm needs developer options* | Turn on Developer options by hand (tap the build number seven times), then run it again. |
| *Self-arm needs a Settings tap* | Settings showed something the app didn't recognise. Open Wireless debugging by hand and run it again. |
| *Self-arm failed: Wi-Fi did not turn on* | Turn Wi-Fi on and make sure a saved network is in range. |
| *Self-arm failed: pairing code expired* or *setup timed out* | Run it again. |
| *Service STUCK* | Settings shows the service on while it isn't running. The watchdog and the app repair it on their own. |

## What the self-arm leaves enabled

After the self-arm, and across reboots:

- **ADB over TCP on port 5555.** `persist.adb.tcp.port` is set to 5555 and USB debugging is
  on, so adbd starts at every boot and listens on port 5555 on every network interface, not
  only on 127.0.0.1. It still requires an authorized ADB key, but it is an exposed service on
  any Wi-Fi the glasses join. See [security.md](security.md).
- **The paired ADB key never expires.** `adb_allowed_connection_time` is set to 0 (Android
  revokes Wireless debugging keys after 7 days by default). The key is in the app's private
  storage.
- **`WRITE_SECURE_SETTINGS`** is granted to Rokid Lumen, which uses it to keep its
  accessibility service enabled and USB debugging on.
- **Two shell helpers** run as the `shell` user, started again by the app after each boot
  (from the boot receiver, the accessibility service and every app launch) over
  127.0.0.1:5555:
  - an **accessibility watchdog** (`/data/local/tmp/lumen-a11y-watchdog.sh`), which checks every
    second that the service is enabled and the app is running, and otherwise re-enables the
    service and relaunches Rokid Lumen, then returns to the Rokid launcher;
  - a **shortcut bridge** (`/data/local/tmp/lumen-shortcut-bridge.sh`), which takes requests
    from a file under `/sdcard/Android/data/dev.lumen.glasses/files/shortcut_bridge/`: press the
    touchpad's real Hi Rokid shortcut key, or turn Wi-Fi on or off.
- **Always-on Wi-Fi scanning is off** (`wifi_scan_always_enabled` set to 0).
- **The shell route for Wi-Fi.** With the self-arm, the app can run shell commands over
  loopback. It uses them to turn Wi-Fi on, join the phone's hotspot and forget it afterwards.

## Undo the self-arm

There is no switch in the app to undo it yet. While Rokid Lumen is installed and armed it
restarts the helpers on its next launch, so undoing it means removing the app (or clearing its
data, which also deletes the band's key and the web apps). With adb connected to the glasses:

```sh
# Stop the helpers and remove their files.
adb shell sh /data/local/tmp/lumen-a11y-watchdog.sh stop
adb shell sh /data/local/tmp/lumen-shortcut-bridge.sh stop
adb shell 'rm -f /data/local/tmp/lumen-*'

# Remove the app: its key, its WRITE_SECURE_SETTINGS grant and its files go with it.
adb uninstall dev.lumen.glasses

# Put ADB back as it was.
adb shell "setprop persist.adb.tcp.port ''"
adb shell settings delete global adb_allowed_connection_time
adb shell settings put global adb_wifi_enabled 0
adb shell settings put global wifi_scan_always_enabled 1   # if you had it on before
```

Then, on the glasses, open Developer options and **Revoke USB debugging authorizations**, and
reboot so adbd stops listening on port 5555. Turn USB debugging off in Hi Rokid if you don't
need it.

## Next

- [features.md](features.md): everything the band, the grid and the companion do.
- [building-apps.md](building-apps.md): write your own web app for the glasses.

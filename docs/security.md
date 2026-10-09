# Security

How Rokid Lumen protects the glasses, the phone and the web apps, and what is still open. To
report a vulnerability, see [SECURITY.md](../SECURITY.md).

Lumen asks for a lot of power on the glasses: an accessibility service that reads the screen
and performs gestures, shell privileges through ADB, and the phone's network. This page says
where each one goes.

## Known open issues

These are real gaps in this version. They are on the [roadmap](../ROADMAP.md).

1. **The self-arm leaves ADB over TCP listening on port 5555, on every interface.** The
   self-arm sets `persist.adb.tcp.port` to 5555 and turns USB debugging on, so adbd starts at
   every boot and listens on port 5555 on all of the glasses' network interfaces, not only on
   127.0.0.1, whenever the glasses are on a network. ADB keys are still required (a client must
   hold a key the glasses authorized), and the paired key never expires
   (`adb_allowed_connection_time` is 0). It is still an exposed service on every Wi-Fi the
   glasses join, the phone's hotspot included. The app itself only needs 127.0.0.1.
2. **The helpers' requests go through files on external storage.** The shortcut bridge, which
   runs as the `shell` user, reads its requests from
   `/sdcard/Android/data/dev.lumen.glasses/files/shortcut_bridge/request`, wakes on a FIFO next
   to it (`doorbell`, mode 666), and writes `response` and `heartbeat` there. Its commands are
   limited to pressing the Hi Rokid shortcut key and turning Wi-Fi on or off, and requests older
   than 30 seconds are ignored. Anything that can write to that folder (the shell user, or an
   app with broad storage access) can send those commands.
3. **The self-arm's ADB key can be imported from external storage.** If
   `/sdcard/Android/data/dev.lumen.glasses/files/self_arm/adbkey.pem` exists, the app copies it
   into its private storage and uses it for ADB over loopback. Whoever can place that file can
   choose the key the app authenticates with. (The band's key is also imported from the app's
   external folder, `air-gestures-band.json`, and deleted there after the import.)
4. **The phone's proxy has no per-session authentication yet.** Anything that joins the
   phone's local-only hotspot can use the proxy. What limits it today: the hotspot is Android's
   local-only hotspot, with a name and passphrase Android generates, joined as WPA2; the
   passphrase travels to the glasses over Rokid's link; the proxy listens only on the
   hotspot's address; it goes to public destinations only; it serves at most 64 connections;
   and it closes with the hotspot when the glasses stop renewing for 3 minutes.
5. **Web app secrets are not encrypted at rest on the glasses.** A `secret` setting is kept in
   the glasses app's private storage as plain preferences. It never goes back to the phone and
   never appears in a log.

## The self-arm

The self-arm uses the accessibility service to walk the glasses' own Settings: it turns Wi-Fi
on, turns Developer options on if needed (by tapping the build number), turns Wireless debugging
on, reads the pairing code from the screen, and pairs with the glasses' adbd over 127.0.0.1
(`kadb`). Over that session it:

- grants the app `WRITE_SECURE_SETTINGS`;
- sets `persist.adb.tcp.port` to 5555, `adb_wifi_enabled` to 1, `adb_allowed_connection_time`
  to 0 and `wifi_scan_always_enabled` to 0; then the app turns USB debugging on
  (`adb_enabled` 1) and restarts adbd in TCP mode;
- installs two helper scripts in `/data/local/tmp` (`lumen-a11y-watchdog.sh`,
  `lumen-shortcut-bridge.sh`, with their pid, log and heartbeat files) and starts them as the
  `shell` user;
- asks the bridge to turn Wi-Fi off again.

After every boot the app reconnects to 127.0.0.1:5555 with the paired key and restarts the
helpers. The watchdog checks every 3 seconds that the app's process is there, every 30 seconds
(or as soon as the process is gone) that the accessibility service is enabled and the app runs,
and otherwise re-enables the service and relaunches the app. With `WRITE_SECURE_SETTINGS`
the app also repairs its accessibility entry itself.

The same shell route is used for what a normal app can't do: turning Wi-Fi on, joining the
phone's hotspot (`cmd wifi connect-network`) and forgetting it afterwards.

The self-arm refuses to run where Rokid Nexus is installed or configured, since Nexus manages
ADB on those glasses. How to undo it: [getting-started.md](getting-started.md#undo-the-self-arm).

## The glasses app

- **Accessibility service.** It retrieves the content of interactive windows, to navigate apps
  and the Rokid launcher, and to read Settings during the self-arm, and it performs gestures
  (the launcher's swipes). It draws the notification banner as an accessibility overlay, so the
  app needs no overlay permission.
- **Internal commands.** `BridgeCommandActivity` is protected by a signature permission.
- **Installs from outside.** `InstallConfirmActivity` is exported for adb and other apps, but
  nothing is installed until the wearer selects Install on the glasses; Cancel has the focus
  first. It accepts HTTPS addresses only and is not `BROWSABLE`, so ordinary links never reach
  it. `WebAppActivity` is not exported.
- **Packages.** A package is unpacked with zip-slip protection, at most 200 MB and 5,000 files,
  into a staging folder that is deleted when the install is cancelled.
- **Backups** are off (`allowBackup="false"`).
- **Debug builds only:** the simulated band's broadcast receiver and GeckoView's remote
  debugging. Release builds have neither.

## Web app isolation

- **A context per app on GeckoView.** Each app's GeckoView session runs in its own context
  (its id): cookies, storage and cache apart from every other app's, deleted when the app is
  removed.
- **The system WebView shares cookies.** It keeps one cookie jar for every app on that engine
  and has no contexts to delete. Storage stays per origin. Keep apps that sign in to something
  on GeckoView.
- **Loopback ports are never reused.** Each offline app gets its own port, from 47100 up, never
  given out again, even after the app is removed, so a new app can't inherit an old one's
  origin and storage.
- **The offline server.** It listens on 127.0.0.1 only, answers `GET` and `HEAD`, requires a
  single `Host` header naming `127.0.0.1:<port>` or `localhost:<port>` (a DNS-rebound name is
  refused with 421), refuses paths outside the package, and sends
  `Cross-Origin-Resource-Policy: same-origin`, `X-Frame-Options: SAMEORIGIN` and
  `X-Content-Type-Options: nosniff`. A server runs only while a screen uses it, plus a minute.
- **The host bridge answers the app's own origin only.** On GeckoView the extension tags each
  page message with the tab and address of the page that sent it (the page can't forge either),
  answers that tab only, and the content script drops a message meant for another origin. On
  the WebView the main frame's address is checked, so a cross-origin iframe inside the app's
  own page still reaches the bridge.
- **Navigation.** An offline app can't leave its own origin. An online app may go anywhere over
  HTTPS, and other origins get nothing from the bridge but typing (GeckoView): the page the app
  shows can report a focused field and ask for the composer, and gets what the wearer types on
  the phone's keyboard or in the composer. That's what a sign-in page on another site needs. It
  gets no settings, no microphone, no speech, no installs, and can't hold Back.
- **Settings and secrets.** `window.lumen.config` answers only a page on the app's origin, with
  only the keys its manifest declares. An update downloaded on the glasses from another origin
  than the installed app's forgets its secrets, since any package can claim a manifest `id`.

## The phone companion

- **The proxy** (`WebProxy`) tunnels HTTPS with `CONNECT` (TLS stays end to end) and forwards
  plain HTTP with `Connection: close`. It resolves a destination once and refuses it if any
  address is private or special (0/8, 10/8, 100.64/10, 127/8, 169.254/16, 172.16/12,
  192.168/16, multicast and above, `::`, `::1`, `fc00::/7`, `fe80::/10`, `ff00::/8`, and
  IPv4-mapped, IPv4-compatible and NAT64 forms of those), belongs to one of the phone's own
  interfaces, or is in the hotspot's /24. It then connects to the address it checked, never to
  a second lookup (DNS rebinding). It answers 503 beyond 64 connections and closes a tunnel
  after 5 minutes without traffic.
- **The hotspot lease.** The glasses renew their request every minute; without a renewal for
  3 minutes the companion closes the proxy and the hotspot.
- **Dictation keys.** API keys for the cloud engines are encrypted with an AndroidKeyStore
  AES-GCM key and never leave the phone. Audio goes to the engine you chose: a cloud engine
  sends it to that provider; Vosk and, depending on the phone, the Android recognizer stay on
  the phone.
- **Notifications** live in memory only on both sides. Redacted, secret and (with *Hide the
  text*) all notification text stays on the phone. A dismiss from the glasses clears only
  notifications the phone sent them.
- **The touch service** (for the band on the phone) performs gestures only and reads nothing
  on the screen.
- **Debug builds only:** the hotspot's credentials are written to the companion's private
  storage, for joining by hand during development. Never in the log.

## The link between the glasses and the phone

All messages go over Rokid's CXR link, through the Hi Rokid app: the band's settings, the
grid, notifications, the dictation audio and text, web app settings (secrets included, from
the phone to the glasses) and the hotspot's credentials. Lumen adds no encryption of its own on
top of that link.

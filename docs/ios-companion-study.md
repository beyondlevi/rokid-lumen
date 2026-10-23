# Study: an iOS companion for Rokid Lumen

Status: **study, nothing built yet** (October 2026). Today the glasses app (`dev.lumen.glasses`)
only works with the Android companion (`dev.lumen.companion`). This document asks whether an
iPhone can play the companion's role, what it would keep, what it would lose, and how to find
out on real devices before writing the app.

## Verdict

**Feasible for the core, with real losses at the edges.** Rokid ships an official iOS build of
the same CXR-L link the Android companion uses, with the calls Lumen depends on (custom app
session bound to a package, custom commands, the glasses' microphone stream, installing an
APK on the glasses). So the glasses side can, in principle, stay as it is for dictation,
settings, the grid and setup.

What iOS does not allow, no matter how the app is written:

- reading other apps' notifications on the phone, and replying to them in text;
- opening a hotspot from an app. The phone's internet still reaches the glasses, by one of two
  routes; see [Internet through the iPhone](#internet-through-the-iphone), the main goal of an
  iOS companion;
- acting as a Bluetooth keyboard and mouse for a computer;
- driving the phone itself (system gestures, other apps' media, volume and brightness from the
  background).

Notifications are the biggest product gap, and the only path to them (ANCS, read by the
glasses) is a change on the glasses side, not in the iOS app.

Nothing below was run on an iPhone or on the glasses. The first step is a device spike (see
[Plan](#plan)), with a clear go/no-go.

## What was verified, and how

| Claim | Evidence |
| --- | --- |
| Rokid publishes CXR-L for iOS | CocoaPods pod `RGCxrClient`: 1.0.1 (2026-04-16), 1.0.4 (2026-06-27), 1.1.1 (2026-08-27), author "Rokid R&D", proprietary license ([trunk API](https://trunk.cocoapods.org/api/v1/pods/RGCxrClient)) |
| It is a binary `.framework`, device only | Podspec 1.1.1: `vendored_frameworks: RGCxrClient.framework`, `EXCLUDED_ARCHS[sdk=iphonesimulator*]: arm64`, depends on `RGCoreKit 0.0.2` |
| It can open a session with **our** glasses app | `RGCxrClient.swiftinterface`: `makeCustomAppSession(packageName:activityName:apkPath:aiInterceptMode:)`, init modes `customApp` and `customView` |
| Custom commands both ways | `sendCustomCmd(cmd:payload:callback:)`, `sendCustomCmdStream(cmd:payload:stream:callback:)` |
| The glasses' microphone | `startAudioStream(codec: .pcm / .oggOpus / .mp3, mode:)` |
| Install and open an app on the glasses | `installApp(_ path:callback:)`, `openApp(activityName:url:callback:)` |
| Authorization goes through Hi Rokid on iOS | `authenticate(scopes:bundleId:appName:completion:)`, `isRokidAppInstalled()`, URL callback handling; Hi Rokid for iOS is [id6749669942](https://apps.apple.com/us/app/hi-rokid-rokid-glasses/id6749669942) |
| A community iOS app already uses it | [Anezium/Rokid-Lyrics-iOS](https://github.com/Anezium/Rokid-Lyrics-iOS): `pod 'RGCxrClient', '1.0.4'`, iOS 16 |

The pod, the podspec and the Swift interface were downloaded and read for this study. No
public iOS documentation page was found on Rokid's developer site; the API above comes from
the framework's own interface file.

Known quirks reported by Rokid-Lyrics-iOS on 1.0.4 (unverified by us): callbacks report
`success=false` for commands the glasses did receive, and `setNotifyEventListenCmds` returns
`modeMismatch`. Lumen already tolerates a lossy link (grid requests are resent with the same
id, files are chunked with acks), which helps here.

## How it would fit together

```text
 Meta Neural Band --(BLE + L2CAP, unchanged)--> glasses: BandAccessibilityService
                                                   ^
                 Rokid CXR-L (RGCxrClient, via     |  nb.* messages, mic audio,
                 Hi Rokid for iOS)                 |  APK install
                                                   v
 iPhone: Lumen Companion for iOS (Swift, SwiftUI)
   - protocol/: the nb.* JSON messages, ported or shared
   - dictation: Apple Speech + the cloud engines
   - internet: the user's Personal Hotspot (no proxy)
```

The glasses keep talking CXR-S (`PhoneLink.kt`); they should not need to know which phone OS
is on the other end, as long as the iOS SDK's Caps envelope is the same as Android's. That is
the first thing the spike checks.

## Feature by feature

Legend: **Yes** works on iOS much as on Android; **Partial** works with real limits;
**No** iOS does not allow it; **Glasses** needs a change on the glasses side.

| Feature (Android today) | Android API it rests on | On iOS | Verdict |
| --- | --- | --- | --- |
| Link to the glasses | CXR-L `client-l:1.1.2`, `CXRSession(CUSTOMAPP, "dev.lumen.glasses")` | `RGCxrClient` `makeCustomAppSession(packageName: "dev.lumen.glasses")` | **Yes** (to confirm on device) |
| Hi Rokid authorization | `AuthorizationHelper` + reflection on obfuscated fields | `authenticate(scopes:)` + URL callback | **Yes** |
| Settings, grid, app settings, keyboard text from the phone | `protocol/` over `sendCustomCmd` | same commands | **Yes** |
| Dictation: cloud engines (OpenAI, ElevenLabs, Azure) | OkHttp REST/WebSocket, AndroidKeyStore | `URLSession`, Keychain | **Yes** |
| Dictation: on-device | `SpeechRecognizer` fed from a pipe | `SpeechAnalyzer` (iOS 26) or `SFSpeechRecognizer` on a buffer; Vosk on iOS not checked | **Yes** (engine changes) |
| Dictation while the phone is locked | foreground service (microphone, connected device) | depends on whether the CXR link survives in the background (`bluetooth-central` mode) | **Partial**, unknown until measured |
| Web-app audio (`window.lumen.audio`, voice notes) | `MediaCodec` Opus | the SDK streams `.oggOpus`; `AVAudioConverter` / libopus | **Yes** |
| Install the glasses app, updates | `appUploadAndInstall` | `installApp(path)` | **Yes** |
| Self-arm, band key import | `nb.settings`, `nb.band.key` | same commands | **Yes** |
| Band key with the Meta account | `WebView` + `CookieManager`, ported from kinesis `MetaAuth.swift` | the original Swift from kinesis, `WKWebView` | **Yes** |
| Install a `.mrbd.zip` | the glasses GET it from the phone's `WebProxy` over the hotspot | serve it while the app is open, or send it over `sendCustomCmdStream` with the existing chunking | **Partial** (protocol change) |
| Internet for the glasses | `startLocalOnlyHotspot` + local proxy | no API to open a hotspot. Either the iPhone's Personal Hotspot, or the glasses' own network with the companion as the proxy, as Meta's app does; see [below](#internet-through-the-iphone) | **Partial** (glasses change) |
| Notifications: forward, inbox, banner | `NotificationListenerService` | none for apps. Only path: the **glasses** read the iPhone's ANCS over BLE | **Glasses** |
| Notifications: reply in text, reactions | `RemoteInput` on the notification's action | ANCS has only a positive and a negative action, no text | **No** |
| Notifications: dismiss | `cancelNotification` | ANCS negative action (usually "clear") | **Glasses**, partial |
| Notification pictures | `EXTRA_PICTURE` | not in ANCS | **No** |
| The band on the phone (profiles, media, volume, brightness, torch, screen gestures) | `AccessibilityService.dispatchGesture`, `dispatchMediaKeyEvent`, `WRITE_SETTINGS` | CoreBluetooth L2CAP works and `band-core` ports; but no system gestures, no control of other apps' media, volume only through `MPVolumeView`, brightness and torch only in the foreground | **No** for most of it |
| Handwriting keyboard in any app | `InputMethodService` | keyboard extension, but it cannot hold the band's Bluetooth link | **No** in practice |
| The band on a computer (phone as HID) | `BluetoothHidDevice` | CoreBluetooth refuses the HID service UUID | **No** |
| Glasses mirror, adb pairing | kadb | no adb client for iOS in use; developer feature | **No** (skip) |
| Companion self-update | `PackageInstaller` from GitHub releases | App Store, TestFlight or sideloading | **Partial** |

## Internet through the iPhone

The reason for an iOS companion: web apps (online apps, video included) on the glasses, through
the iPhone's connection. On Android the companion opens a local-only hotspot, the glasses join
it with `cmd wifi connect-network` (`PhoneInternet.kt:303`), and the web engines go through the
companion's HTTP proxy (`SystemWebEngine.kt:273-290`), ~2.9 MB/s measured
(`PhoneInternet.kt:26`). iOS has no API to open a hotspot
([NEHotspotConfiguration](https://developer.apple.com/documentation/networkextension/nehotspotconfiguration)
only joins one), so the network has to come from somewhere else.

### How Meta's app does it

Meta does not document the data path to Meta Ray-Ban Display. What its own notes show:

- The glasses publish a Wi-Fi network and the iPhone joins it: the Meta AI app shows
  "Meta AI wants to Join Wi-Fi network..." on iPhone
  ([release notes, v26, 2026-06-29](https://www.meta.com/help/ai-glasses/1809764829519902/)),
  and Meta's developer docs say "On iOS, a Wi-Fi prompt should appear"
  ([display overview](https://wearables.developer.meta.com/docs/develop/dat/display-overview/)).
- Since v26 (Early Access) that prompt is gone through **Wi-Fi Aware**, iOS 26's peer-to-peer
  Wi-Fi, for imports and updates.
- Reels play on the display since 2026-03-04; how their video reaches the glasses is not
  documented.

This is the pattern Apple calls a *stand-alone accessory*: the accessory publishes a network
with no internet, the iPhone joins it, and iOS "will typically leave the default route" on
cellular, so the app reaches the internet over cellular while talking to the accessory over
Wi-Fi ([Working with a Wi-Fi Accessory](https://developer.apple.com/forums/thread/734344),
[Apple DTS](https://developer.apple.com/forums/thread/789103)). Rokid's own iOS SDK for its
media sync does the same (CXR-M iOS: `connectToHotspot`, `RGEasyHotspotInfo`,
`NEHotspotConfiguration`).

### Route A: the iPhone's Personal Hotspot

The iPhone shares its connection itself; iOS does the routing, no proxy.

- **Companion:** a screen to enter the hotspot's name and password once (iOS gives an app no
  way to read them), sent to the glasses in `NetEvent.Ready` with no proxy.
- **Glasses:** keep that network saved instead of forgetting it after use
  (`PhoneInternet.kt:370`), go direct (proxy null), and say on the HUD when the hotspot is not in
  range ("Open Personal Hotspot on the iPhone").
- **For:** works with the iPhone locked and the companion closed, full speed, video included;
  the smallest change on both sides.
- **Against:** the user turns the hotspot on by hand. Apple: third-party devices are dropped
  after 90 s without traffic
  ([Apple platform support](https://support.apple.com/guide/platform-support/sup025284ac0)), and
  the iPhone stops broadcasting shortly after leaving its Personal Hotspot screen if nobody
  joins (an older Apple forum answer, to measure on current iOS). It needs a plan that allows
  tethering, and it uses cellular even when the iPhone is on Wi-Fi.

### Route B: the glasses' network, the companion as the proxy (Meta's pattern)

The glasses publish a Wi-Fi network; the iPhone joins it; the companion runs the same HTTP proxy
the Android one does, and sends what it relays out over cellular.

- **Glasses:** start a soft AP with a fixed name and password (through the self-arm's shell,
  `cmd wifi start-softap`, to confirm on the firmware; or `startLocalOnlyHotspot`, whose random
  credentials would bring the iOS prompt back each time), and send them to the phone, the
  reverse of today's `NetEvent.Ready`.
- **Companion:** `NEHotspotConfiguration(ssid:passphrase:isWEP:)` with `joinOnce = false`
  (one system prompt the first time), the proxy ported from `WebProxy.kt` (379 lines) to
  `Network.framework` (`NWListener`), outgoing connections on the default route (cellular) or
  forced with `requiredInterfaceType = .cellular`; the Local Network permission
  ([TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)).
  `.mrbd.zip` packages keep coming from the proxy, as today.
- **For:** no step in Settings; the companion turns it on and off on its own, as on Android.
- **Against:**
  - **The companion has to keep running.** iOS suspends a backgrounded app and its listening
    socket with it. The background modes that exist (`bluetooth-central`, `audio`) are tied to
    real Bluetooth or audio work and do not promise a server
    ([Core Bluetooth background](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html)).
    Whether the CXR link keeps the app awake enough is the first thing to measure.
  - **The iPhone leaves its own Wi-Fi** while joined to the glasses (one infrastructure network
    at a time), so its internet is cellular only.
  - A network that is not the default route is not auto-joined again after the phone locks
    (Apple DTS, same post).

### Route C: Wi-Fi Aware (what Meta moved to)

Peer-to-peer Wi-Fi with no prompt, the iPhone staying on its own Wi-Fi
([WiFiAware](https://developer.apple.com/documentation/wifiaware/connecting-paired-devices.md),
iOS 26, iPhone 12 and later). **Not open to these glasses today:** iOS requires Wi-Fi Aware 4.0
pairing with encrypted data paths, and on Android that is `AwarePairingConfig` and
`Characteristics.isAwarePairingSupported()`, API 34
([Android reference](https://developer.android.com/reference/kotlin/android/net/wifi/aware/Characteristics)).
The Rokid RG glasses run Android 12. Developers also report iOS and Android Wi-Fi Aware failing
to interoperate even on Android phones that support it
([Apple forums](https://developer.apple.com/forums/thread/790195)). It comes back only if Rokid
ships an Android 14 firmware with Aware pairing.

### Route D: over Bluetooth

The CXR link or a BLE L2CAP channel can carry small API calls, not pages or video. A fallback
for text-only apps at most.

### Recommendation

Build **Route A first**: it is small on both sides, survives the iPhone being locked, and video
works. Prototype **Route B** in the spike, because the background behaviour decides it and
nothing short of a device answers that. If B holds with the iPhone locked, it becomes the
default and A the fallback, as Wi-Fi then needs no step from the user.

## Code that carries over

| Part | Size | On iOS |
| --- | --- | --- |
| `protocol/` | ~1,500 lines, plain Kotlin + `org.json` | port to Swift `Codable` checked against shared JSON fixtures, or move to Kotlin Multiplatform with kotlinx.serialization and export an XCFramework |
| `rust/band-core`, `rust/writing` | ~8,500 lines, no JNI | reusable as is |
| `rust/bridge` | ~3,200 lines, only `jni_api.rs` (~500) is JNI | add a UniFFI or C ABI next to the JNI one, build an XCFramework |
| `phone/` | ~17,600 lines of Kotlin/Compose | rewritten in Swift/SwiftUI; roughly half of it (computer link, phone control, IME, adb mirror, Android updates) has no iOS counterpart and is dropped |
| `app/` (glasses) | ~24,600 lines | unchanged for the core; changes for internet (hotspot handoff), package delivery and ANCS notifications |

The band only matters on iOS if the band is used on the iPhone itself; the glasses keep their
own link to it. Since most of what the band does on the phone is not possible on iOS, the band
on iOS is the last phase and may not be worth it.

## Risks and unknowns

1. **Caps compatibility.** The glasses parse field 0 of a Rokid `Caps` as JSON and field 1 as
   bytes (`PhoneLink.kt`). The iOS SDK takes a `Data` payload; whether it arrives as the same
   Caps layout is the spike's first question. Rokid-Lyrics-iOS wraps JSON in "Caps v5" on its
   own BLE path, a hint that the format is known.
2. **The link in the background.** The Android companion lives in a foreground service. iOS
   suspends apps; whether `RGCxrClient` keeps the link and the mic stream alive with the screen
   locked decides whether dictation is usable day to day.
3. **ANCS on the glasses.** Rokid's own firmware likely already consumes ANCS (the SDK has a
   `Sys_SyncAncsApps` command, and Hi Rokid for iOS announces notifications). Two paths, both
   to test: Lumen's glasses app subscribes to ANCS itself (only one consumer may be allowed),
   or it reads what Rokid's firmware posts on the glasses.
4. **SDK maturity.** Closed binary, three releases in six months, no public docs, the Android
   companion already needs reflection to work around the Android SDK.
5. **Distribution.** The app installs APKs on another device and reaches the band through a
   reverse-engineered protocol; App Store review may refuse it. TestFlight external testing is
   reviewed too. Sideloading (an unsigned IPA from CI, AltStore) or EU alternative marketplaces
   are the fallback. An Apple Developer account is needed in any case.
6. **Build.** Needs macOS and Xcode: a GitHub Actions macOS runner for CI, a Mac and an iPhone
   for the spike.

## Plan

### Phase 0: device spike (go/no-go)

A throwaway iOS app (SwiftUI + `pod 'RGCxrClient', '1.1.1'`) and the current glasses app,
unchanged:

1. Authorize through Hi Rokid for iOS and get a token.
2. `makeCustomAppSession(packageName: "dev.lumen.glasses")`, connect.
3. Send `nb.settings` and `nb.grid` requests as the Android companion builds them; the glasses
   must answer (`nb.*.event`).
4. Start the mic stream (PCM 16 kHz), check the format, transcribe one sentence.
5. Lock the phone for five minutes; check the link and the stream.
6. `installApp` with a debug APK.
7. Internet, Route A: Personal Hotspot on, glasses joined, an online app and a video playing;
   time how long it lasts with the iPhone locked and the app idle.
8. Internet, Route B: a soft AP on the glasses (`cmd wifi start-softap` through the self-arm),
   the iPhone joins with `NEHotspotConfiguration`, a minimal `NWListener` CONNECT proxy;
   measure throughput, then lock the iPhone and time how long the proxy keeps answering.
9. On the glasses: `pm list features | grep -i aware` and `dumpsys wifiaware`, to know whether
   Wi-Fi Aware exists in the hardware at all.
10. ANCS probe on the glasses: is the iPhone's ANCS visible to a third-party BLE central while
   Hi Rokid is connected, and do Rokid's own notifications show up in the glasses' notification
   system?

**Go** if 1 to 4 pass and 7 or 8 does. 5 and 8 decide whether the companion can live in the
background; 10 decides the notifications phase.

### Phase 1: MVP companion, internet first

Hi Rokid authorization, **internet through Route A** (Personal Hotspot handoff, the glasses keep
the network and go direct), the setup page (install and open the glasses app, self-arm, band key
import), the Apps tab (grid and app settings). `protocol/` shared through fixtures or KMP.

### Phase 2: Route B, packages, dictation

Route B (the glasses' network and the companion's proxy) if the spike showed it holds in the
background. `.mrbd.zip` packages: from the proxy under Route B, over the CXR link under
Route A. Dictation (Apple Speech and the cloud engines) and the keyboard from the phone.

### Phase 3: notifications

Depending on the spike: ANCS on the glasses (inbox, banner, clear, accept/decline), with the
loss of text replies, reactions and pictures stated in the iOS docs.

### Phase 4 (optional): the band on the iPhone

UniFFI build of `bridge`, CoreBluetooth L2CAP, Meta key claim on the phone. Only if there is a
use for it on iOS beyond what Phases 1 to 3 give.

## Open questions

- Is the target day-to-day use or a demo? Day-to-day use makes Phase 0 step 5 (background) and
  the notifications phase mandatory.
- App Store, TestFlight, or sideloading only?
- Is a Mac, an iPhone on iOS 16+ and an Apple Developer account available for the spike?
- Share `protocol/` through Kotlin Multiplatform, or keep two implementations checked against
  the same fixtures?

## Sources

- [RGCxrClient on CocoaPods](https://trunk.cocoapods.org/api/v1/pods/RGCxrClient) and its 1.1.1
  framework (Swift interface read for this study)
- [Anezium/Rokid-Lyrics-iOS](https://github.com/Anezium/Rokid-Lyrics-iOS), an iOS app on CXR-L
- [mayacenote/RokidKeyboard](https://github.com/mayacenote/RokidKeyboard), an iPhone keyboard
  for the glasses over its own BLE link
- [Anezium/awesome-rokid](https://github.com/Anezium/awesome-rokid)
- [Hi Rokid for iOS](https://apps.apple.com/us/app/hi-rokid-rokid-glasses/id6749669942)
- [Apple Notification Center Service](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html)
- [NEHotspotConfiguration](https://developer.apple.com/documentation/networkextension/nehotspotconfiguration)
- [CBL2CAPChannel](https://developer.apple.com/documentation/corebluetooth/cbl2capchannel)
- [Custom keyboards](https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/CustomKeyboard.html)
- [SpeechAnalyzer](https://developer.apple.com/documentation/speech/speechanalyzer)
- [HID service refused by CoreBluetooth (Apple forums)](https://developer.apple.com/forums/thread/725238)
- [Meta Wearables Device Access Toolkit for iOS](https://github.com/facebook/meta-wearables-dat-ios):
  for Meta's glasses only, no Neural Band access for other devices

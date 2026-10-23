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
- opening a hotspot from an app (the user turns Personal Hotspot on by hand);
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
| Internet for the glasses | `startLocalOnlyHotspot` + local proxy | no API to open a hotspot. The user turns on Personal Hotspot; the companion sends its name and password (typed once) in `NetEvent.Ready`; no proxy needed, it is real internet | **Partial** (one manual step, glasses change) |
| Notifications: forward, inbox, banner | `NotificationListenerService` | none for apps. Only path: the **glasses** read the iPhone's ANCS over BLE | **Glasses** |
| Notifications: reply in text, reactions | `RemoteInput` on the notification's action | ANCS has only a positive and a negative action, no text | **No** |
| Notifications: dismiss | `cancelNotification` | ANCS negative action (usually "clear") | **Glasses**, partial |
| Notification pictures | `EXTRA_PICTURE` | not in ANCS | **No** |
| The band on the phone (profiles, media, volume, brightness, torch, screen gestures) | `AccessibilityService.dispatchGesture`, `dispatchMediaKeyEvent`, `WRITE_SETTINGS` | CoreBluetooth L2CAP works and `band-core` ports; but no system gestures, no control of other apps' media, volume only through `MPVolumeView`, brightness and torch only in the foreground | **No** for most of it |
| Handwriting keyboard in any app | `InputMethodService` | keyboard extension, but it cannot hold the band's Bluetooth link | **No** in practice |
| The band on a computer (phone as HID) | `BluetoothHidDevice` | CoreBluetooth refuses the HID service UUID | **No** |
| Glasses mirror, adb pairing | kadb | no adb client for iOS in use; developer feature | **No** (skip) |
| Companion self-update | `PackageInstaller` from GitHub releases | App Store, TestFlight or sideloading | **Partial** |

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
7. ANCS probe on the glasses: is the iPhone's ANCS visible to a third-party BLE central while
   Hi Rokid is connected, and do Rokid's own notifications show up in the glasses' notification
   system?

**Go** if 1 to 4 pass. 5 decides how much dictation works locked; 7 decides the notifications
phase.

### Phase 1: MVP companion

Hi Rokid authorization, the setup page (install and open the glasses app, self-arm, band key
import), the Apps tab (grid and app settings), dictation (Apple Speech and the cloud engines),
the keyboard from the phone. `protocol/` shared through fixtures or KMP.

### Phase 2: internet and packages

Personal Hotspot handoff (the glasses accept a fixed SSID they keep, instead of a new one each
time), `.mrbd.zip` delivery over the CXR link, so it works without the companion in the
foreground.

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

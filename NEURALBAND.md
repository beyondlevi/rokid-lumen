# The Meta Neural Band in this fork

## Is there an official Android API for the band's gestures?

No, as of September 2026. What Meta publishes:

- The **Wearables Device Access Toolkit** (`facebook/meta-wearables-dat-android`, Kotlin).
  It reaches the *glasses*: camera, microphone, audio, and since v0.7 (May 2026) drawing
  on the Ray-Ban Display. Its FAQ lists camera, microphone, audio, motion and IMU; the
  Neural Band isn't among them. At the preview's launch (September 2025), UploadVR reported
  it wouldn't give access to the band's gestures, though Meta was "starting to think about
  how this could eventually work". A developer asked about tap and zoom
  gestures on v0.7's release thread (June 2026), and Meta hadn't answered there.
- **Web Apps** on the Ray-Ban Display can read the band's wrist motion and input, inside
  the glasses. That's not an Android API, and it needs the glasses.

Nothing documents the band pairing straight to a phone, or an app receiving its swipes and
taps. Sources:

- <https://developers.meta.com/wearables/faq/>
- <https://developers.meta.com/blog/build-for-display-glasses/>
- <https://github.com/facebook/meta-wearables-dat-android>
- <https://github.com/facebook/meta-wearables-dat-android/discussions/95>
- <https://www.uploadvr.com/meta-wearables-device-access-toolkit-announced-smart-glasses-sdk/>

## So how does this app talk to the band?

As upstream does: the Rust `band-core` (a port of
[callbacked/kinesis](https://github.com/callbacked/kinesis)) speaks the band's own
Bluetooth protocol, worked out by reverse engineering. After the phone claims the band (the
companion does it with a Meta account sign-in, as kinesis does; this unlinks it from Meta's
glasses and app), the band connects over L2CAP and the core
recognises swipes, index and middle taps, the middle hold, and pinch and turn. That's
unofficial: a band firmware update could change it, and Meta doesn't support it.

## The seam: `GestureDevice`

`BandRuntime` (run by the accessibility service on the glasses) doesn't talk to the band
directly. It talks to a `GestureDevice`: start, stop, pause, and a mapping string
(`gesture=action;…`, see `BandMapping.build`). The device reports the action names that its
gestures resolve to (`nav.forward`, `nav.activate`, `glasses.ai_assist`, `app:<package>`, …),
and the service runs them.

| Implementation | What it is |
| --- | --- |
| `BandLink` | The real band, over the reverse-engineered protocol (the Rust bridge). |
| `SimulatedBand` | No band: in a debug build, Band → Simulated band, then `adb shell am broadcast -a dev.airgestures.rokid.SIMULATE --es gesture <key>`. It resolves the same mapping string, so the navigation and the mapped actions run exactly as they would from the band. It doesn't reproduce the bridge's timing, such as a single tap waiting out its double. |

If Meta publishes a real API, it would be a third implementation. It would have to turn
that API's events into the gesture keys (`swipe_up`, `index_tap`, …, `dial_up`/`dial_down`)
and resolve them through the mapping string. Everything above it stays as it is.

Simulator keys: `swipe_up`, `swipe_down`, `swipe_left`, `swipe_right`, `index_tap`,
`index_double`, `middle_tap`, `middle_double`, `dial_up`, `dial_down`, `middle_hold`.

## Needs checking with the hardware

- The band's L2CAP channel from the glasses, while they're also linked to the phone.
- Launcher and in-app navigation from band swipes on real Rokid apps (the navigation code
  is R08 Access Bridge's, tested there with the ring's key events).
- Select on an index tap is immediate only while the index double tap is unset. If it's
  mapped, the bridge holds the single tap to rule out a double. The same goes for Back on
  the middle tap and the middle double tap.

# Roadmap

What is planned after 0.1.0, roughly in order. Nothing here exists yet unless it says so. Ideas
and feedback are welcome in the issues.

## An app store in the companion

Browse and install Lumen apps from the companion, instead of typing a package's address.

- A **signed catalog**: the companion downloads a list of apps and checks its signature
  before showing anything.
- Each entry names its package's **SHA-256**; the glasses refuse a package that doesn't match.
- **Updates by version**: the companion compares the catalog's version with the installed
  app's manifest `version` and offers the update.
- Installing from the catalog keeps today's rules: same manifest `id` updates the app and keeps
  its data and settings.

## More Rokid screens in the Controls tab

The Controls tab opens the Rokid's camera, gallery, music and settings. The others still work
with the Rokid launcher as the home (Lumen isn't, see docs/features.md) and could join it:
translation (`com.rokid.os.sprite.launcher/.page.translate.TranslatePageActivity`), navigation
(`.page.navigation.NavigationOverseaPageActivity`), Rokid AI chat (`.page.chat.ChatPageActivity`)
and the teleprompter (`.page.wordtips.WordTipsPageActivity`, which doesn't close with Back).

## Phone notifications for web apps

`lumen_notifications` lets an offline app open its phone notifications (0.2.0-beta.6). Next:

- the same for online apps (reading their web manifest when they're added);
- **`window.lumen.notifications`**: the web app reads and follows those notifications,
  filtered by its origin, so an app sees only what it declared and nothing meant for another;
- quick replies chosen by the app or the phone's smart replies, instead of a fixed list.

## The full Meta visual identity

The inbox, the banner and the install screen already follow the Meta Ray-Ban Display UI
Toolkit's tokens. The grid, the settings screens and the companion are next, so the whole
platform looks like one system.

## Compatibility modes, back

The navigator has built-in handling for a few native apps that don't work well with the generic
accessibility navigation (ReadEra, ArBook and the Rokid build of NewPipe). The plan is to bring
back compatibility modes as choices, per app, for native apps on the grid.

## Security

The open items in [docs/security.md](docs/security.md#known-open-issues):

- **ADB over TCP only on loopback**, or only while it's needed, instead of port 5555 left
  listening on every interface after the self-arm.
- **Helper IPC off external storage**: the shortcut bridge's request, response, heartbeat and
  doorbell files move out of `/sdcard/Android/data/...`.
- **No ADB key import from external storage**: the self-arm's key only from the app's private
  storage.
- **Per-session authentication on the phone's proxy**, so only the glasses that asked can use
  it.
- **Secrets encrypted at rest on the glasses**, as the companion's API keys already are on the
  phone.
- **A way to undo the self-arm from the app**, instead of the adb steps in
  [docs/getting-started.md](docs/getting-started.md#undo-the-self-arm).

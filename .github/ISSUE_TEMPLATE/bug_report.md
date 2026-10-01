---
name: Bug report
about: Something in Rokid Lumen or the companion doesn't work as described
labels: bug
---

<!-- A security issue? Don't open an issue: report it privately (see SECURITY.md). -->
<!-- Leave out personal data: no serial numbers, network names, phone numbers or keys. -->

## What happened

<!-- What you did, what you expected, and what happened instead. -->

## Steps to reproduce

1.
2.
3.

## Versions

- Rokid Lumen (glasses):
- Rokid Lumen Companion (phone):
- Glasses model and firmware:
- Phone model and Android version:
- Hi Rokid version:
- Engine, if it's about a web app (GeckoView or System WebView):

## Self-arm

<!-- Done or not, and the status line on the glasses' Settings screen. -->

## Logs

<!--
Glasses:
  adb logcat -v time -s BandService:D BandRuntime:D BandSelfArm:D BandWebApp:D BandGecko:D BandInternet:D *:S
Phone:
  adb logcat -v time -s NbCompanion:D NbNotify:D NbNetwork:D NbProxy:D NbDictation:D *:S
Check the logs for anything private before pasting them.
-->

```text

```

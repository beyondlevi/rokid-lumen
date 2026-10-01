#!/usr/bin/env bash
# Share the desktop's band owner key with the (debug) app on the Rokid glasses:
# ~/.local/state/air-gestures/{owner.key,band.json} → the app's private files.
# Only one device can talk to the band at a time: run `air-gestures disconnect`
# on the desktop before connecting from the glasses.
set -euo pipefail
STATE="${XDG_STATE_HOME:-$HOME/.local/state}/air-gestures"
APP=dev.lumen.glasses
TMP=/data/local/tmp/air-gestures-identity
[ -f "$STATE/owner.key" ] || { echo "no $STATE/owner.key (claim the band with air-gestures pair first)" >&2; exit 1; }
adb shell "rm -rf $TMP && mkdir -p $TMP"
trap 'adb shell "rm -rf $TMP"' EXIT
adb push -q "$STATE/owner.key" "$STATE/band.json" "$TMP/"
adb shell "chmod 644 $TMP/*"
# run-as works for debuggable builds only; the files end up app-private (0600).
adb shell "run-as $APP sh -c 'mkdir -p files && cp $TMP/owner.key $TMP/band.json files/ && chmod 600 files/owner.key files/band.json'"
echo "copied owner.key and band.json to $APP"

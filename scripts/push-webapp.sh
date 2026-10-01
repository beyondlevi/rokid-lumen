#!/usr/bin/env bash
# Install an offline MRBD web app on the Rokid glasses: copies the .mrbd.zip (a Vite dist/
# with index.html and manifest.webmanifest at its root) into the app's drop folder and opens
# the apps grid, which installs every package it finds there and deletes it.
#   scripts/push-webapp.sh my-app.mrbd.zip
# An HTTPS package can also be downloaded by the glasses, which show what it is and install it
# once Install is chosen with the band (or the touchpad):
#   adb shell am start -n dev.lumen.glasses/.InstallConfirmActivity \
#     -a dev.lumen.glasses.INSTALL_PACKAGE -d https://example.com/my-app.mrbd.zip
set -euo pipefail
FILE="${1:-}"
APP=dev.lumen.glasses
DROP="/sdcard/Android/data/$APP/files/webapps"
[ -f "$FILE" ] || { echo "usage: $0 <app>.mrbd.zip" >&2; exit 1; }
case "$FILE" in *.mrbd.zip) ;; *) echo "the file must end in .mrbd.zip" >&2; exit 1 ;; esac
NAME=$(basename "$FILE")
TMP="/data/local/tmp/$NAME"
adb push -q "$FILE" "$TMP"
trap 'adb shell "rm -f $TMP"' EXIT
adb shell "chmod 644 $TMP"
# As push-band-file.sh: run-as writes the file as the app, so the app can delete it after.
if ! adb shell "run-as $APP sh -c 'mkdir -p $DROP && cp $TMP $DROP/$NAME'" 2>/dev/null; then
    adb shell "mkdir -p $DROP && cp $TMP $DROP/$NAME && chmod 644 $DROP/$NAME"
fi
adb shell am start -n "$APP/dev.lumen.glasses.LauncherActivity" >/dev/null
echo "copied to $DROP; the apps grid installs it now"

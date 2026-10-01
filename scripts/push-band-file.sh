#!/usr/bin/env bash
# Hand the band file exported by the phone app (Setup → Export the key) or the Linux
# app (`air-gestures export`) to the app on the Rokid glasses, then import it from the
# glasses: Band → Import band key. The app deletes the file from its folder once imported.
# Only one device can talk to the band at a time: stop the phone app (or run
# `air-gestures disconnect`) before connecting from the glasses.
set -euo pipefail
FILE="${1:-air-gestures-band.json}"
APP=dev.lumen.glasses
DROP="/sdcard/Android/data/$APP/files"
TMP=/data/local/tmp/air-gestures-band.json
[ -f "$FILE" ] || { echo "usage: $0 [air-gestures-band.json]" >&2; exit 1; }
adb push -q "$FILE" "$TMP"
trap 'adb shell "rm -f $TMP"' EXIT
adb shell "chmod 644 $TMP"
# run-as (debug builds) writes the file as the app itself, so it can always read it back;
# a plain adb push into Android/data can leave a shell-owned file the app can't open.
if ! adb shell "run-as $APP sh -c 'mkdir -p $DROP && cp $TMP $DROP/air-gestures-band.json'" 2>/dev/null; then
    adb shell "mkdir -p $DROP && cp $TMP $DROP/air-gestures-band.json && chmod 644 $DROP/air-gestures-band.json"
fi
echo "copied to $DROP; on the glasses: Band → Import band key"

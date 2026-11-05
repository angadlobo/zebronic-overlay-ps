#!/bin/sh
# Start (or stop) the virtual PS controller on the connected phone.
# Run once after each phone restart. It keeps running after you unplug the cable.
#   scripts/pad-bridge.sh         start
#   scripts/pad-bridge.sh stop    stop, and bring back Remote Play's on-screen buttons
set -e
ADB=${ADB:-adb}
command -v "$ADB" >/dev/null 2>&1 || ADB="$HOME/Library/Android/sdk/platform-tools/adb"

"$ADB" shell "pkill -f com.padoverlay.Bridge" || true
[ "$1" = stop ] && { echo "Stopped."; exit 0; }

APK=$("$ADB" shell pm path com.padoverlay | cut -d: -f2 | tr -d '\r')
[ -n "$APK" ] || { echo "Pad Overlay isn't installed on this phone."; exit 1; }

"$ADB" shell "CLASSPATH=$APK setsid nohup app_process / com.padoverlay.Bridge $* </dev/null >/data/local/tmp/pad-bridge.log 2>&1 &"
sleep 2
"$ADB" shell cat /data/local/tmp/pad-bridge.log

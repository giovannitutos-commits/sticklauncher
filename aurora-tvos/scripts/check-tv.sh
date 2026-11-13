#!/usr/bin/env bash
set -euo pipefail
mkdir -p preview
adb install -r apk/app-debug.apk
adb logcat -c
adb shell am start -W -n br.com.stocco.auroratv.glass/.MainActivity
sleep 4
adb shell pidof br.com.stocco.auroratv.glass
adb exec-out screencap -p > preview/01-inicio.png
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_RIGHT
sleep 1
adb shell pidof br.com.stocco.auroratv.glass
adb exec-out screencap -p > preview/02-dock.png
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER
sleep 1
adb exec-out screencap -p > preview/03-menu.png
adb shell uiautomator dump /sdcard/aurora-window.xml
adb pull /sdcard/aurora-window.xml preview/menu.xml
grep -Eq 'Mover para a esquerda|Adicionar ao dock' preview/menu.xml
adb logcat -d > preview/logcat.txt
if grep -q 'FATAL EXCEPTION' preview/logcat.txt; then
  echo "Falha detectada no logcat"; exit 1
fi

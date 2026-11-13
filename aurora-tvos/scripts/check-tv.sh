#!/usr/bin/env bash
set -euo pipefail
mkdir -p preview
adb install -r apk/app-debug.apk
adb logcat -c
adb shell am start -W -n br.com.stocco.auroratv.glass/.MainActivity
sleep 4
adb shell pidof br.com.stocco.auroratv.glass
adb exec-out screencap -p > preview/01-inicio.png
focused=false
for attempt in 1 2 3 4 5; do
  adb shell uiautomator dump /sdcard/aurora-focus.xml >/dev/null
  adb pull /sdcard/aurora-focus.xml preview/focus.xml >/dev/null
  if python3 -c 'import xml.etree.ElementTree as E; r=E.parse("preview/focus.xml"); nodes=[n for n in r.iter("node") if n.get("focused")=="true"]; print([(n.get("content-desc"),n.get("text"),n.get("class")) for n in nodes]); raise SystemExit(0 if any("segure OK para organizar" in n.get("content-desc","") for n in nodes) else 1)'; then
    focused=true
    break
  fi
  adb shell input keyevent KEYCODE_DPAD_DOWN
  sleep 1
done
test "$focused" = true
adb exec-out screencap -p > preview/02-dock.png
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER
sleep 1
adb exec-out screencap -p > preview/03-menu.png
adb shell uiautomator dump /sdcard/aurora-window.xml
adb pull /sdcard/aurora-window.xml preview/menu.xml
cat preview/menu.xml
grep -Eq 'Mover para a esquerda|Adicionar ao dock' preview/menu.xml
adb logcat -d > preview/logcat.txt
if grep -q 'FATAL EXCEPTION' preview/logcat.txt; then
  echo "Falha detectada no logcat"; exit 1
fi

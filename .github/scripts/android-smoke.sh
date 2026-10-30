#!/bin/bash
# Smoke test on the Android emulator: install the APK, fake a GPS position, tap through the main
# flows by visible text (uiautomator), screenshot each step and fail on a crash.
set -u
APK=$1
OUT=${2:-smoke}
PKG=com.nxnson.vesnavi
mkdir -p "$OUT"
step=0

shot() {
  step=$((step + 1))
  adb exec-out screencap -p > "$OUT/$(printf %02d $step)-$1.png"
}

crashed() {
  adb logcat -d -s AndroidRuntime:E | grep -q "FATAL EXCEPTION" && return 0
  [ -z "$(adb shell pidof $PKG | tr -d '\r')" ]
}

check() {
  if crashed; then
    echo "::error::App crashed during: $1"
    adb logcat -d -s AndroidRuntime:E | tail -60
    shot "crash-$1"
    exit 1
  fi
}

# Tap the first on-screen element whose text or content description contains $1.
tap() {
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
    xy=$(python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[2]
for n in ET.parse(sys.argv[1]).iter('node'):
    if want in (n.get('text') or '') or want in (n.get('content-desc') or ''):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
    if [ -n "$xy" ]; then
      adb shell input tap $xy
      return 0
    fi
    sleep 2
  done
  echo "::error::Not found on screen: $1"
  echo "On screen:"
  python3 - "$OUT/ui.xml" <<'PY'
import xml.etree.ElementTree as ET, sys
for n in ET.parse(sys.argv[1]).iter('node'):
    t = n.get('text') or n.get('content-desc')
    if t: print('  ', repr(t), n.get('bounds'))
PY
  shot "missing"
  return 1
}

gps() { adb emu geo fix 106.7009 10.7769 >/dev/null; }

adb logcat -c
adb install -r "$APK" || exit 1
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS BLUETOOTH_SCAN BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE; do
  adb shell pm grant $PKG android.permission.$p 2>/dev/null
done
gps
adb shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 12; gps; sleep 3
check launch
shot safety-notice
tap "Tôi đã hiểu" || exit 1
sleep 8; gps
check map
shot map

tap "Đi đâu" || exit 1
sleep 2
adb shell input text "Cho%sBen%sThanh"
sleep 8
check search
shot search
tap "Chợ Bến Thành" || tap "Ben Thanh" || exit 1
sleep 12
check route
shot route-preview

tap "Chạy thử" || exit 1
sleep 15
check simulation
shot navigating
tap "Dừng dẫn đường" || exit 1
sleep 3

tap "Chưa kết nối xe" || exit 1
sleep 3
check scooter
shot scooter
tap "Kết nối" || exit 1
sleep 6
check connect
shot scanning
adb shell input swipe 500 1600 500 400 300
sleep 1
shot scooter-scrolled
tap "Bluetooth Lab" || exit 1
sleep 2
check lab
shot bluetooth-lab
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 2

tap "Cài đặt" || exit 1
sleep 2
tap "English" || exit 1
sleep 2
check settings
shot settings-english
adb shell input swipe 500 1800 500 300 300; sleep 1
adb shell input swipe 500 1800 500 300 300; sleep 1
tap "Report a bug on GitHub" || exit 1
sleep 2
adb shell input text "Smoke%stest%sreport"
sleep 1
check bug-report
shot bug-report
tap "Open GitHub to send" || exit 1
sleep 6
check open-github
shot github-issue
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 3
adb shell input keyevent KEYCODE_BACK; sleep 2
shot map-english
check final
echo "Smoke test passed: $step screenshots in $OUT"

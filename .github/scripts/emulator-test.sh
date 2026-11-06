#!/usr/bin/env bash
# Android 에뮬레이터에서 실제 다운로드 측정 (합법 공개 토렌트: Debian netinst)
set -x
OUT="$GITHUB_WORKSPACE/test-results"
mkdir -p "$OUT"
APK=$(ls "$GITHUB_WORKSPACE"/apk/*.apk | head -1)

adb install -r -g "$APK"
adb shell pm grant com.raontorrent android.permission.POST_NOTIFICATIONS || true
adb logcat -c

# 1) 트래커 없는 마그넷 → DHT + 자동 공개 트래커만으로 피어를 찾는지
MAGNET=$(python3 "$GITHUB_WORKSPACE/.github/scripts/magnet.py" /tmp/test.torrent notrackers)
echo "$MAGNET" > "$OUT/magnet.txt"
adb shell am start -W -a android.intent.action.VIEW -d "'$MAGNET'" com.raontorrent
sleep 25
adb exec-out screencap -p > "$OUT/screen-25s.png"
sleep 95
adb exec-out screencap -p > "$OUT/screen-120s.png"

adb logcat -d -s RaonTorrentStats:I RaonTorrent:* ReactNativeJS:* AndroidRuntime:E > "$OUT/logcat.txt" || true
adb logcat -d > "$OUT/logcat-full.txt" || true
grep RaonTorrentStats "$OUT/logcat.txt" | tail -30
exit 0

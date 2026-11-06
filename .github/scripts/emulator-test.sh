#!/usr/bin/env bash
# Android 에뮬레이터에서 실제 다운로드 측정 (합법 공개 토렌트: Debian netinst)
set -x
OUT="$GITHUB_WORKSPACE/test-results"
mkdir -p "$OUT"
APK=$(ls "$GITHUB_WORKSPACE"/apk/*.apk | head -1)

adb install -r -g "$APK"
adb shell pm grant com.raontorrent android.permission.POST_NOTIFICATIONS || true
# 설치 직후 시스템이 앱 저장소를 재마운트하며 프로세스를 정리하므로 안정될 때까지 대기
sleep 30
adb logcat -G 16M || true
adb logcat -c

# 1) 트래커 없는 마그넷 → DHT + 자동 공개 트래커만으로 피어를 찾는지
MAGNET=$(python3 "$GITHUB_WORKSPACE/.github/scripts/magnet.py" /tmp/test.torrent notrackers)
echo "$MAGNET" > "$OUT/magnet.txt"
adb shell am start -W -a android.intent.action.VIEW -d "'$MAGNET'" com.raontorrent
sleep 30
adb exec-out screencap -p > "$OUT/screen-30s.png"
for i in $(seq 1 16); do
  sleep 25
  # 혹시 프로세스가 정리되면 다시 띄움 (목록은 자동 복원)
  adb shell pidof com.raontorrent >/dev/null || adb shell am start -W com.raontorrent/.MainActivity
done
adb exec-out screencap -p > "$OUT/screen-end.png"
# 완료 후 이동 확인
adb shell ls -la /storage/emulated/0/Android/data/com.raontorrent/files/Download/RaonTorrent/ > "$OUT/final-dir.txt" 2>&1 || true

adb logcat -d -s RaonTorrentStats:I RaonTorrent:* ReactNativeJS:* AndroidRuntime:E > "$OUT/logcat.txt" || true
adb logcat -d > "$OUT/logcat-full.txt" || true
grep RaonTorrentStats "$OUT/logcat-full.txt" | tail -40
grep -E "moving|moved|move failed|has died" "$OUT/logcat-full.txt" | grep -i raon > "$OUT/events.txt" || true
cat "$OUT/events.txt"
exit 0

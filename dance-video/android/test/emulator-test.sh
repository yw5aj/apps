#!/usr/bin/env bash
# Runs on the CI host while an emulator is attached. Drives the real app through its
# test hook, sends it to the background mid-conversion, and checks the saved MP4s.
set -uo pipefail
cd "$(dirname "$0")/.."

APK=app/build/outputs/apk/release/app-release.apk
PKG=io.github.yw5aj.dancevideo
DIR=/sdcard/Android/data/$PKG/files
T=$(mktemp -d)
FATAL=0
SUMMARY=""

shot() {
  adb exec-out screencap -p > "$T/$1.png" || return 0
  convert "$T/$1.png" -resize 360x -quality 55 "$T/$1.jpg" || return 0
  echo "SHOT_BEGIN $1"; base64 -w0 "$T/$1.jpg"; echo; echo "SHOT_END $1"
}

adb install -r -g "$APK" || exit 1
adb shell am start -W -n $PKG/.MainActivity
sleep 4
shot start
adb shell mkdir -p $DIR
adb push test-clips/clip422.mov $DIR/clip422.mov
adb push test-clips/clip420.mov $DIR/clip420.mov

# name file start(trial|full) encoder(mc|x264|"") preset background(0|1) expect_vcodec expect_width expect_dur fatal(0|1)
run() {
  local name=$1 file=$2 start=$3 enc=$4 preset=$5 bg=$6 vcodec=$7 width=$8 dur=$9 fatal=${10}
  echo "::group::$name"
  adb shell am force-stop $PKG 2>/dev/null
  adb logcat -c
  local extra=()
  [ -n "$enc" ] && extra=(--es test_encoder "$enc")
  local t0=$(date +%s)
  adb shell am start -n $PKG/.MainActivity --es test_input $DIR/$file --es test_start "$start" --es test_preset "$preset" "${extra[@]}"
  if [ "$bg" = 1 ]; then
    sleep 8
    shot "$name-running"
    adb shell input keyevent KEYCODE_HOME
    sleep 3
    echo "--- service while app is in background:"
    adb shell dumpsys activity services $PKG | grep -E "ServiceRecord|isForeground|foregroundServiceType" | head -5
  fi
  # Poll the log (a streaming logcat | grep -m1 would sit until timeout after the match).
  local line="" deadline=$(( $(date +%s) + 900 ))
  while [ -z "$line" ] && [ "$(date +%s)" -lt "$deadline" ]; do
    sleep 5
    line=$(adb logcat -d -v brief -s DanceVideo:V | grep -m1 -E "DVTEST (DONE|FAIL|CANCELED)" | tr -d '\r')
  done
  [ -z "$line" ] && line="TIMEOUT after 900s"
  local secs=$(( $(date +%s) - t0 ))
  echo "result after ${secs}s: $line"
  adb logcat -d -v brief -s DanceVideo:V | grep -v "^--------" | tail -45
  local ok=0 detail=""
  if [[ "$line" == *"DVTEST DONE"* ]]; then
    local path=${line##*path=}
    adb pull "/sdcard/$path" "$T/$name.mp4" >/dev/null
    local j
    j=$(ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,pix_fmt,channels -show_entries format=duration -of json "$T/$name.mp4")
    local gv gw ga gc gd
    gv=$(jq -r '[.streams[]|select(.codec_type=="video")][0].codec_name' <<<"$j")
    gw=$(jq -r '[.streams[]|select(.codec_type=="video")][0].width' <<<"$j")
    ga=$(jq -r '[.streams[]|select(.codec_type=="audio")][0].codec_name' <<<"$j")
    gc=$(jq -r '[.streams[]|select(.codec_type=="audio")][0].channels' <<<"$j")
    gd=$(jq -r '.format.duration' <<<"$j")
    detail="video=$gv width=$gw audio=$ga ch=$gc dur=$gd size=$(stat -c%s "$T/$name.mp4")"
    if [ "$gv" = "$vcodec" ] && [ "$gw" = "$width" ] && [ "$ga" = aac ] && [ "$gc" = 2 ] \
       && awk -v d="$gd" -v e="$dur" 'BEGIN{exit !(d>e-0.6 && d<e+0.6)}'; then ok=1; fi
    # The saved file must also decode cleanly end to end.
    if [ $ok = 1 ] && ! ffmpeg -v error -xerror -i "$T/$name.mp4" -f null - ; then ok=0; detail="$detail (decode errors)"; fi
  fi
  local enc_used=$(sed -n 's/.*encoder=\([^ ]*\).*/\1/p' <<<"$line")
  if [ $ok = 1 ]; then
    SUMMARY+="PASS $name (${secs}s, $enc_used) $detail"$'\n'
  else
    SUMMARY+="FAIL $name (${secs}s) $detail $line"$'\n'
    [ "$fatal" = 1 ] && FATAL=1
  fi
  echo "::endgroup::"
}

run trial-hw    clip422.mov trial mc   HIGH  0 h264 1920 5 0
run full-sw-bg  clip422.mov full  x264 SMALL 1 h264 1280 6 1
run full-auto   clip422.mov full  ""   STD   1 h264 1920 6 1
adb shell am start -n $PKG/.MainActivity
sleep 3
shot done
run copy-fast   clip420.mov full  ""   HIGH  0 h264 1920 6 1

echo "==== SUMMARY ===="
printf "%s" "$SUMMARY"
exit $FATAL

#!/usr/bin/env bash
#
# record-voice-gif.sh — record the voice assistant round-trip on an attached
# Android device and turn it into the README's optimized looping GIF.
#
# It launches the app on Koti, opens the voice overlay by tapping the mic FAB,
# records the screen while you drive one turn, then converts the clip to a GIF
# with an ffmpeg palette pass. Drive the turn either by speaking a command or —
# for a repeatable capture that needs no microphone — by tapping a quick-command
# chip (e.g. "Talon yhteenveto") once the overlay is open.
#
# Why it targets the *rightmost* bottom FAB: the shell now has two floating
# buttons — the lightbulb (lights sheet) and the mic — so we pick the one with
# the largest x. Compose exposes no content-desc, so the button is located
# geometrically from a uiautomator dump.
#
# Usage:
#   scripts/record-voice-gif.sh [-s <serial>] [-o <out.gif>] [--secs N]
#                               [--width N] [--fps N] [--keep-mp4]
#
# Examples:
#   scripts/record-voice-gif.sh                    # → docs/screenshots/voice-assistant.gif
#   scripts/record-voice-gif.sh --secs 18 --width 360
#   ANDROID_SERIAL=<serial> scripts/record-voice-gif.sh -s <serial>
#
# Requirements: adb; ffmpeg.

set -euo pipefail

APP="fi.marmorikatu.app"
ACT="${APP}/.MainActivity"
ADB="${ADB:-adb}"

OUT="docs/screenshots/voice-assistant.gif"
SECS=16
WIDTH=320
FPS=12
KEEP_MP4=0
DEV_ARG=""

usage() { sed -n '2,29p' "$0"; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    -s) DEV_ARG="$2"; shift 2 ;;
    -o) OUT="$2"; shift 2 ;;
    --secs) SECS="$2"; shift 2 ;;
    --width) WIDTH="$2"; shift 2 ;;
    --fps) FPS="$2"; shift 2 ;;
    --keep-mp4) KEEP_MP4=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "unknown arg: $1" >&2; usage 1 ;;
  esac
done

command -v ffmpeg >/dev/null || { echo "ffmpeg not found (brew install ffmpeg)" >&2; exit 1; }

# ── device selection (physical first, else emulator) ─────────────────────────
pick_device() {
  if [ -n "$DEV_ARG" ]; then echo "-s $DEV_ARG"; return; fi
  local serial
  serial=$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')
  [ -z "$serial" ] && serial=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
  [ -z "$serial" ] && { echo "no attached device" >&2; exit 1; }
  echo "-s $serial"
}
DEV="$(pick_device)"
adb() { "$ADB" $DEV "$@"; }
echo "device: $DEV"

SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
echo "screen: ${W}x${H}"
NAV_Y=$(( H * 94 / 100 ))

# The mic FAB: the *rightmost* clickable node sitting above the nav bar, below
# mid-screen (the lightbulb FAB is to its left, so max-x disambiguates).
find_mic() {
  adb shell uiautomator dump /sdcard/mk_ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/mk_ui.xml 2>/dev/null \
    | tr '>' '\n' \
    | grep 'clickable="true"' \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | sed -E 's/[^0-9]+/ /g' \
    | awk -v W="$W" -v H="$H" '{
        cx=($1+$3)/2; cy=($2+$4)/2;
        if (cx > W*0.6 && cy > H*0.7 && cy < H*0.92 && cx > best) { best=cx; besty=cy }
      } END { if (best) print best, besty }'
}

# ── launch on Koti ───────────────────────────────────────────────────────────
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell am start -n "$ACT" >/dev/null 2>&1
echo "waiting for app to load…"; sleep 12
adb shell input tap $(( W * 1 / 12 )) "$NAV_Y"; sleep 2   # Koti tab

MIC=$(find_mic)
[ -z "$MIC" ] && MIC="$(( W*88/100 )) $(( H*82/100 ))"
echo "mic FAB at: $MIC"

TMP="$(mktemp -d)"; MP4="$TMP/voice.mp4"
trap 'rm -rf "$TMP"' EXIT

cat <<EOF

▶ Recording ${SECS}s. When the avatar opens:
    • say a command (e.g. "sammuta kaikki valot"), OR
    • tap a quick-command chip for a repeatable, mic-free capture.
  Let the reply play so the avatar animates + the stream grows.

EOF

adb shell screenrecord --time-limit "$SECS" --bit-rate 8000000 /sdcard/mk_voice.mp4 &
sleep 2
adb shell input tap $MIC       # open the voice overlay / start listening
wait
adb pull /sdcard/mk_voice.mp4 "$MP4" >/dev/null 2>&1
adb shell rm -f /sdcard/mk_voice.mp4 /sdcard/mk_ui.xml >/dev/null 2>&1 || true

echo "converting → $OUT (${WIDTH}px, ${FPS}fps)…"
mkdir -p "$(dirname "$OUT")"
# Two-pass palette for clean colours; diff stats keep the file small.
ffmpeg -y -i "$MP4" \
  -vf "fps=${FPS},scale=${WIDTH}:-1:flags=lanczos,split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3" \
  -loop 0 "$OUT" >/dev/null 2>&1

if [ "$KEEP_MP4" = "1" ]; then cp "$MP4" "${OUT%.gif}.mp4"; echo "  kept ${OUT%.gif}.mp4"; fi
SZ=$(du -h "$OUT" | cut -f1)
echo "wrote $OUT ($SZ)"

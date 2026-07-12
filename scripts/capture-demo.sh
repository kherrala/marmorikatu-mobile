#!/usr/bin/env bash
#
# capture-demo.sh — capture marketing/README screenshots (and an optional
# voice-assistant GIF) of the Marmorikatu app from an attached Android device
# or emulator.
#
# It walks every bottom-nav tab, takes a shot at a few scroll positions, and can
# record the voice assistant round-trip and turn it into a GIF.
#
# Why the odd swipe coordinates: modern phones (e.g. the Galaxy S25) use gesture
# navigation, so a swipe that starts too low or reaches an edge is eaten by the
# system (back / home / recents) and bounces you out of the app. Every swipe
# here stays in the middle vertical band, and every tap is a real tap — never a
# swipe — so the app is never accidentally dismissed.
#
# Usage:
#   scripts/capture-demo.sh [-s <serial|transport-id>] [-o <outdir>] [--gif]
#                           [--scrolls N] [--voice-secs N]
#
# Examples:
#   scripts/capture-demo.sh                       # auto-pick a device, shots only
#   scripts/capture-demo.sh --gif                 # shots + voice GIF
#   scripts/capture-demo.sh -s emulator-5554 -o out
#
# Requirements: adb; ffmpeg (only for --gif); sips (macOS, for resizing — the
# script degrades gracefully without it).

set -euo pipefail

APP="fi.marmorikatu.app"
ACT="${APP}/.MainActivity"
ADB="${ADB:-adb}"

OUTDIR="./demo-shots"
DO_GIF=0
SCROLLS=2          # extra scroll positions captured per screen (after the top)
VOICE_SECS=16      # length of the voice recording
DEV_ARG=""

# The bottom-nav tabs, in order. Tapahtumat lives behind the header bell on the
# phone layout, so it is opened separately at the end.
TABS=("Koti" "Valot" "Ilmasto" "Energia" "Bussit" "Kalenteri")

usage() { sed -n '2,30p' "$0"; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    -s) DEV_ARG="$2"; shift 2 ;;
    -o) OUTDIR="$2"; shift 2 ;;
    --gif) DO_GIF=1; shift ;;
    --scrolls) SCROLLS="$2"; shift 2 ;;
    --voice-secs) VOICE_SECS="$2"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "unknown arg: $1" >&2; usage 1 ;;
  esac
done

# ── device selection ─────────────────────────────────────────────────────────
# Prefer an explicit -s; otherwise take the first physical device, else the first
# emulator, else whatever is attached.
pick_device() {
  if [ -n "$DEV_ARG" ]; then echo "-s $DEV_ARG"; return; fi
  local line serial
  # physical devices first (serials that are not "emulator-*")
  serial=$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')
  [ -z "$serial" ] && serial=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
  [ -z "$serial" ] && { echo "no attached device" >&2; exit 1; }
  echo "-s $serial"
}
DEV="$(pick_device)"
adb() { "$ADB" $DEV "$@"; }

echo "device: $DEV"
mkdir -p "$OUTDIR"

# ── geometry ─────────────────────────────────────────────────────────────────
SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
echo "screen: ${W}x${H}"

# Nav bar row and per-tab x centres (6 tabs sharing the width).
NAV_Y=$(( H * 94 / 100 ))
tab_x() { echo $(( W * (2*$1 + 1) / 12 )); }   # $1 = tab index 0..5

# Safe vertical scroll: middle band only, so no system gesture is triggered.
vswipe() { adb shell input swipe $(( W/2 )) $(( H*68/100 )) $(( W/2 )) $(( H*32/100 )) 400; }

scap() { adb exec-out screencap -p > "$OUTDIR/$1.png"; echo "  $1"; }

# The app is a horizontal pager across tabs, so navigate by tapping the nav bar
# (a tap, never a horizontal swipe, which would page between tabs).
open_tab() { adb shell input tap "$(tab_x "$1")" "$NAV_Y"; sleep 2; }

app_focused() { adb shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus.*${APP}"; }

# Find the floating voice button: the bottom-right clickable node that sits above
# the nav bar (Compose exposes no content-desc, so we locate it geometrically).
find_fab() {
  adb shell uiautomator dump /sdcard/mk_ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/mk_ui.xml 2>/dev/null \
    | tr '>' '\n' \
    | grep 'clickable="true"' \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | sed -E 's/[^0-9]+/ /g' \
    | awk -v W="$W" -v H="$H" '{
        cx=($1+$3)/2; cy=($2+$4)/2;
        # right side, above the nav row, below mid-screen
        if (cx > W*0.6 && cy > H*0.7 && cy < H*0.90) { print cx, cy; }
      }' | head -1
}

# ── launch ───────────────────────────────────────────────────────────────────
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell am start -n "$ACT" >/dev/null 2>&1
echo "waiting for app to load…"; sleep 14
app_focused || { echo "WARN: app not focused; is the screen unlocked?"; }

# ── capture every tab ────────────────────────────────────────────────────────
for i in "${!TABS[@]}"; do
  name=$(echo "${TABS[$i]}" | tr 'A-Z' 'a-z')
  echo "${TABS[$i]}"
  open_tab "$i"
  scap "${name}_1"
  prev=""
  for s in $(seq 1 "$SCROLLS"); do
    vswipe; sleep 1
    cur=$(adb exec-out screencap -p | md5 2>/dev/null || echo x)
    scap "${name}_$((s+1))"
    # stop early if the screen no longer scrolls (identical frame)
    [ "$cur" = "$prev" ] && { rm -f "$OUTDIR/${name}_$((s+1)).png"; break; }
    prev="$cur"
  done
  app_focused || { echo "  (app lost focus — relaunching)"; adb shell am start -n "$ACT" >/dev/null 2>&1; sleep 3; }
done

# Tapahtumat: opened via the header bell (top-right), not the bottom nav.
echo "Tapahtumat"
open_tab 0                       # back to Koti first
adb shell input tap $(( W*72/100 )) $(( H*55/1000 )); sleep 2   # bell icon
scap "tapahtumat_1"; vswipe; sleep 1; scap "tapahtumat_2"

# ── voice assistant GIF ──────────────────────────────────────────────────────
if [ "$DO_GIF" = "1" ]; then
  command -v ffmpeg >/dev/null || { echo "ffmpeg not found; skipping GIF"; exit 0; }
  open_tab 0; sleep 1
  FAB=$(find_fab)
  if [ -z "$FAB" ]; then FAB="$(( W*88/100 )) $(( H*82/100 ))"; fi
  echo "voice: recording ${VOICE_SECS}s — SPEAK A COMMAND when the mic lights up"
  echo "  (mic button at: $FAB)"
  adb shell screenrecord --time-limit "$VOICE_SECS" --bit-rate 8000000 /sdcard/mk_voice.mp4 &
  sleep 2
  adb shell input tap $FAB       # start listening
  wait
  adb pull /sdcard/mk_voice.mp4 "$OUTDIR/voice.mp4" >/dev/null 2>&1
  echo "converting to GIF…"
  ffmpeg -y -i "$OUTDIR/voice.mp4" \
    -vf "fps=12,scale=360:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse" \
    -loop 0 "$OUTDIR/voice.gif" >/dev/null 2>&1
  echo "  wrote $OUTDIR/voice.gif"
fi

echo "done → $OUTDIR"

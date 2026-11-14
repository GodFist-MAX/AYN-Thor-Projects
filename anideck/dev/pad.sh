#!/bin/bash
# Presses the Thor's own controller for tests. sendevent writes to the controller's input device,
# so the app gets exactly what a thumb press gives (same device, source and key codes).
#   dev/pad.sh a b select up down left right x y l1 r1 l2 r2 start ...   (pressed in order)
#   dev/pad.sh hold:<axis>:<value>:<ms>   axis x/y = left stick, z/rz = right stick, value -32767..32767
#     e.g. hold:rz:30000:800 scrolls down for 0.8 s, hold:x:32000:1200 holds the left stick right.
set -e
A=~/platform-tools/adb
export ANDROID_SERIAL=${ANDROID_SERIAL:-62dff1b0}
DEV=$($A shell 'for d in /sys/class/input/event*; do [ "$(cat $d/device/name 2>/dev/null)" = "Odin Controller" ] && echo /dev/input/${d##*/}; done' | tr -d '\r')
[ -n "$DEV" ] || { echo "Odin Controller not found" >&2; exit 1; }
cmds=""
for arg in "$@"; do
  case $arg in
    a) k=304 ;; b) k=305 ;; x) k=307 ;; y) k=308 ;;
    l1) k=310 ;; r1) k=311 ;; l2) k=312 ;; r2) k=313 ;;
    select) k=314 ;; start) k=315 ;;
    up) k=544 ;; down) k=545 ;; left) k=546 ;; right) k=547 ;;
    hold:*)
      IFS=: read -r _ axis val ms <<<"$arg"
      case $axis in x) c=0 ;; y) c=1 ;; z) c=2 ;; rz) c=5 ;; *) echo "unknown axis: $axis" >&2; exit 1 ;; esac
      secs=$(awk "BEGIN { print $ms / 1000 }")
      cmds+="sendevent $DEV 3 $c $val; sendevent $DEV 0 0 0; sleep $secs; sendevent $DEV 3 $c 0; sendevent $DEV 0 0 0; sleep 0.3; "
      continue ;;
    *) echo "unknown button: $arg" >&2; exit 1 ;;
  esac
  cmds+="sendevent $DEV 1 $k 1; sendevent $DEV 0 0 0; sendevent $DEV 1 $k 0; sendevent $DEV 0 0 0; sleep 0.35; "
done
$A shell "$cmds"

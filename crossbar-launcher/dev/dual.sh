#!/bin/bash
# Usage: dev/dual.sh <name> [KEY | tap:X,Y | sleep:N ...]
# Sends key events (to whichever display has focus, like a controller) or taps on the bottom
# screen (X,Y in its 1240x1080 pixels), then saves both screens stacked to /tmp/cb_<name>.jpg.
set -euo pipefail
export ANDROID_SERIAL=${ANDROID_SERIAL:-62dff1b0}
A=~/platform-tools/adb
NAME=$1; shift
for k in "$@"; do
  case $k in
    sleep:*) sleep "${k#sleep:}" ;;
    tap:*) xy=${k#tap:}; $A shell input -d 4 tap "${xy%,*}" "${xy#*,}"; sleep 0.6 ;;
    *) $A shell input keyevent "KEYCODE_$k"; sleep 0.45 ;;
  esac
done
sleep "${SHOT_DELAY:-1.2}"
$A exec-out screencap -p -d 4630946441858561667 > /tmp/cb_top.png
$A exec-out screencap -p -d 4630946482288158084 > /tmp/cb_bottom.png
python3 - "$NAME" <<'EOF'
import sys
from PIL import Image
top = Image.open('/tmp/cb_top.png').convert('RGB')
bot = Image.open('/tmp/cb_bottom.png').convert('RGB')
w = bot.width
top = top.resize((w, round(top.height * w / top.width)), Image.LANCZOS)
out = Image.new('RGB', (w, top.height + 24 + bot.height), (40, 40, 40))
out.paste(top, (0, 0))
out.paste(bot, (0, top.height + 24))
out = out.resize((round(out.width * .72), round(out.height * .72)), Image.LANCZOS)
out.save('/tmp/cb_%s.jpg' % sys.argv[1], quality=85)
EOF
echo /tmp/cb_$NAME.jpg

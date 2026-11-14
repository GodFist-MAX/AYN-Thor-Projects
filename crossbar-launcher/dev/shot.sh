#!/bin/bash
# Usage: dev/shot.sh <name> [key ...]
# Sends optional key events (e.g. DPAD_DOWN ENTER BACK BUTTON_Y), then saves the Thor's top screen to /tmp/cb_<name>.jpg.
set -euo pipefail
export ANDROID_SERIAL=${ANDROID_SERIAL:-62dff1b0}
A=~/platform-tools/adb
NAME=$1; shift
for k in "$@"; do
  if [[ $k == sleep:* ]]; then sleep "${k#sleep:}"; else $A shell input keyevent "KEYCODE_$k"; sleep 0.45; fi
done
sleep "${SHOT_DELAY:-1.2}"
$A exec-out screencap -p -d 4630946441858561667 > /tmp/cb_raw.png
python3 -c "from PIL import Image; Image.open('/tmp/cb_raw.png').convert('RGB').resize((1280, 720), Image.LANCZOS).save('/tmp/cb_$NAME.jpg', quality=85)"
echo /tmp/cb_$NAME.jpg

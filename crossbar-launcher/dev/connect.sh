#!/bin/bash
# Forwards the running CrossBar's local server to the Mac (tcp:8800) and saves its
# access token to dev/.token (private; never printed).
set -euo pipefail
export ANDROID_SERIAL=${ANDROID_SERIAL:-62dff1b0}
A=~/platform-tools/adb
DIR="$(cd "$(dirname "$0")" && pwd)"
PID=$($A shell pidof dev.crossbar.launcher | tr -d '\r')
[ -n "$PID" ] || { echo "CrossBar is not running"; exit 1; }
PORT=$($A logcat -d -s 'CrossBar:I' | grep "local server on port" | tail -1 | sed 's/.*port //' | tr -d '\r')
$A forward --remove tcp:9333 2>/dev/null || true
$A forward tcp:9333 "localabstract:webview_devtools_remote_$PID" >/dev/null
$A forward tcp:8800 "tcp:$PORT" >/dev/null
( umask 077
  curl -s http://127.0.0.1:9333/json | python3 -c "
import sys, json, urllib.parse as u
pages = json.load(sys.stdin)
url = [p['url'] for p in pages if '127.0.0.1' in p['url']][0]
print(u.parse_qs(u.urlparse(url).query)['t'][0])" > "$DIR/.token" )
echo "connected: device port $PORT -> 127.0.0.1:8800"

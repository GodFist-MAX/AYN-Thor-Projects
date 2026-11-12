#!/bin/bash
# Show the AYN Thor's internal storage in a Finder window over USB.
# rclone on the Thor serves the storage over WebDAV on an abstract socket that only adb can reach,
# adb forwards it to the Mac, thor_webdav_proxy.py keeps macOS "._" and .DS_Store files off the
# Thor, and mount_webdav mounts the result as the "AYN Thor" volume.
#   ./thor-finder.sh         mount if needed and open the Finder window
#   ./thor-finder.sh stop    unmount and stop the servers
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="$HOME/platform-tools/adb"
export ANDROID_SERIAL="${ANDROID_SERIAL:-62dff1b0}"
NAME="AYN Thor"
# Outside the home folder: mounts inside it get crawled file by file by background indexers.
MNT="/private/tmp/$NAME"
METADATA_DIR="/private/tmp/thor-mac-metadata"
TUNNEL_PORT=18765
PROXY_PORT=18766
PROXY_PID_FILE="$HERE/.proxy.pid"
SOCKET=thor-webdav

die() { echo "thor-finder: $*" >&2; exit 1; }
mounted() { mount | grep -qF " on $MNT (webdav"; }
proxy_running() { [ -f "$PROXY_PID_FILE" ] && kill -0 "$(cat "$PROXY_PID_FILE")" 2>/dev/null; }

stop() {
  if mounted && ! umount "$MNT" 2>/dev/null; then
    # Busy (e.g. a Finder window is open): cutting the proxy makes the -S mount drop itself.
    proxy_running && kill "$(cat "$PROXY_PID_FILE")"
    for _ in $(seq 1 20); do mounted || break; sleep 0.5; done
  fi
  mounted && die "couldn't unmount $MNT; close anything using it and try again"
  rmdir "$MNT" 2>/dev/null || true
  if proxy_running; then kill "$(cat "$PROXY_PID_FILE")"; fi
  rm -f "$PROXY_PID_FILE"
  "$ADB" forward --remove "tcp:$TUNNEL_PORT" 2>/dev/null || true
  "$ADB" shell 'P=$(pidof rclone) && kill $P' 2>/dev/null || true
  echo "Stopped."
}

start() {
  [ "$("$ADB" get-state 2>/dev/null)" = device ] || die "the Thor isn't connected over USB with USB debugging allowed"

  local_size=$(stat -f %z "$HERE/rclone-android")
  device_size=$("$ADB" shell 'stat -c %s /data/local/tmp/rclone 2>/dev/null' | tr -d '\r')
  if [ "$local_size" != "$device_size" ]; then
    "$ADB" push "$HERE/rclone-android" /data/local/tmp/rclone >/dev/null
    "$ADB" shell chmod 755 /data/local/tmp/rclone
  fi
  # Only the rclone command itself may go to the background: a backgrounded list would keep a
  # subshell holding adb's output open, and adb shell would never return.
  "$ADB" shell "cd /data/local/tmp; if ! pidof rclone >/dev/null; then setsid nohup ./rclone serve webdav \
    /storage/emulated/0 --addr unix://@$SOCKET --dir-cache-time 2s --log-level NOTICE \
    --log-file /data/local/tmp/thor-webdav.log </dev/null >/dev/null 2>&1 & fi"
  "$ADB" forward "tcp:$TUNNEL_PORT" "localabstract:$SOCKET" >/dev/null

  if ! proxy_running; then
    /usr/bin/python3 "$HERE/thor_webdav_proxy.py" --port "$PROXY_PORT" --backend-port "$TUNNEL_PORT" \
      --metadata-dir "$METADATA_DIR" --daemon "$PROXY_PID_FILE" --log "$HERE/proxy.log"
  fi

  ready=0
  for _ in $(seq 1 20); do
    if curl -fs -o /dev/null -X PROPFIND -H "Depth: 0" "http://127.0.0.1:$PROXY_PORT/"; then ready=1; break; fi
    sleep 0.5
  done
  [ "$ready" = 1 ] || die "the file server on the Thor didn't answer (log: adb shell cat /data/local/tmp/thor-webdav.log)"

  if ! mounted; then
    mkdir -p "$MNT"
    /sbin/mount_webdav -S -v "$NAME" "http://127.0.0.1:$PROXY_PORT/" "$MNT"
  fi
  open "$MNT"
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  *) die "usage: $0 [start|stop]" ;;
esac

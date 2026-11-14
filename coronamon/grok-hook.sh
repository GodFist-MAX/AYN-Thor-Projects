#!/bin/sh
# Grok Build hook for Coronamon (installed by `coro grok-hook install`): starts the Thor watcher
# when a session opens in a Thor folder, and hands new bugs from the Thor to the agent once.
# Tool calls with nothing new return here without starting Python.
STATE="$HOME/thor-coronamon/state"
if [ "$1" = "post-tool" ]; then
  cat >/dev/null
  [ -n "$GROK_SESSION_ID" ] && [ -f "$STATE/sessions/$GROK_SESSION_ID" ] || exit 0
  [ "$STATE/news.stamp" -nt "$STATE/told.stamp" ] || exit 0
  exec /usr/bin/python3 "$HOME/thor-coronamon/coro" grok-hook post-tool </dev/null
fi
exec /usr/bin/python3 "$HOME/thor-coronamon/coro" grok-hook "$@"

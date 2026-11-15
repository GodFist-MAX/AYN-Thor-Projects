#!/bin/sh
# Grok Build hook for Coronamon (installed by `coro grok-hook install`): starts the Thor watcher
# when a session opens in a Thor folder, hands new bugs from the Thor to the agent once, and keeps
# a busy marker while a Thor session is in the middle of a turn so automatic runs wait for it.
# Everything but session start returns here without starting Python unless there's news.
STATE="$HOME/thor-coronamon/state"
S="$STATE/sessions/$GROK_SESSION_ID"
case "$1" in
  prompt|post-tool|turn-end|session-end)
    cat >/dev/null
    [ -n "$GROK_SESSION_ID" ] && [ -f "$S" ] || exit 0
    case "$1" in
      prompt) : >"$S.busy"; : >"$S.turns"; exit 0 ;;
      turn-end) rm -f "$S.busy"; exit 0 ;;
      session-end) rm -f "$S" "$S.busy" "$S.turns"; exit 0 ;;
    esac
    : >"$S.busy"
    [ "$STATE/news.stamp" -nt "$STATE/told.stamp" ] || exit 0
    exec /usr/bin/python3 "$HOME/thor-coronamon/coro" grok-hook post-tool </dev/null ;;
esac
exec /usr/bin/python3 "$HOME/thor-coronamon/coro" grok-hook "$@"

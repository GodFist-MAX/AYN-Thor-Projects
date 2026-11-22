#!/usr/bin/env python3
"""Drives AniDeck's WebViews on the Thor over the Chrome DevTools protocol.

  dev/cdp.py pages                    list debuggable pages
  dev/cdp.py eval "<js>" [--page S]   evaluate JS (awaits promises) and print the JSON result
  dev/cdp.py nav <url> [--page S]     navigate a page
  dev/cdp.py shot <file> [--page S]   save a PNG screenshot of a page

--page picks the first page whose URL contains S (default: the first non-asset page, i.e. the top screen).
"""
import base64
import json
import os
import subprocess
import sys
import urllib.request

import websocket

ADB = os.path.expanduser("~/platform-tools/adb")
SERIAL = os.environ.get("ANDROID_SERIAL", "62dff1b0")
PKG = "dev.anideck.app"
PORT = 9334


def adb(*args):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True).stdout.strip()


def pages():
    pid = adb("shell", "pidof", PKG)
    if not pid:
        sys.exit("AniDeck is not running")
    subprocess.run([ADB, "-s", SERIAL, "forward", "tcp:%d" % PORT, "localabstract:webview_devtools_remote_" + pid.split()[0]],
                   capture_output=True)
    with urllib.request.urlopen("http://127.0.0.1:%d/json" % PORT, timeout=5) as r:
        return [p for p in json.load(r) if p.get("type") == "page"]


def pick(sub):
    ps = pages()
    for p in ps:
        if (sub and sub in p["url"]) or (not sub and not p["url"].startswith("file:")):
            return p
    sys.exit("no page matches %r: %s" % (sub, [p["url"] for p in ps]))


class Session:
    def __init__(self, page):
        self.ws = websocket.create_connection(page["webSocketDebuggerUrl"], suppress_origin=True, timeout=60)
        self.n = 0

    def call(self, method, **params):
        self.n += 1
        self.ws.send(json.dumps({"id": self.n, "method": method, "params": params}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == self.n:
                if "error" in msg:
                    raise RuntimeError(msg["error"])
                return msg["result"]


def main():
    args = sys.argv[1:]
    sub = None
    if "--page" in args:
        i = args.index("--page")
        sub = args[i + 1]
        del args[i:i + 2]
    if not args or args[0] == "pages":
        for p in pages():
            print(p["url"][:160], "|", p.get("title", "")[:60])
        return
    s = Session(pick(sub))
    if args[0] == "eval":
        r = s.call("Runtime.evaluate", expression=args[1], returnByValue=True, awaitPromise=True)
        if "exceptionDetails" in r:
            print("EXCEPTION:", json.dumps(r["exceptionDetails"].get("exception", r["exceptionDetails"]))[:2000])
        else:
            v = r["result"].get("value")
            print(v if isinstance(v, str) else json.dumps(v, indent=1, ensure_ascii=False))
    elif args[0] == "nav":
        s.call("Page.navigate", url=args[1])
        print("navigating")
    elif args[0] == "shot":
        r = s.call("Page.captureScreenshot", format="png")
        with open(args[1], "wb") as f:
            f.write(base64.b64decode(r["data"]))
        print(args[1])


if __name__ == "__main__":
    main()

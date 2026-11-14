#!/usr/bin/env python3
"""Drives CrossBar's WebViews on the Thor over the Chrome DevTools protocol.
Needs Theme Development Mode (devMode), which turns on WebView remote debugging.

  dev/cdp.py pages                          list debuggable pages
  dev/cdp.py eval "<js>" [--page bottom]    evaluate JS (awaits promises), print the JSON result
  dev/cdp.py trace NAME [--keys "down up"] [--gap 0.6]
                                            record a Chromium trace while pressing the controller,
                                            save /tmp/cb_trace_NAME.json and print a summary
  dev/cdp.py summary FILE                   summarize a saved trace

--page picks the first page whose URL contains the text (default: index.html, the top screen).
"""
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.request

import websocket

ADB = os.path.expanduser("~/platform-tools/adb")
SERIAL = os.environ.get("ANDROID_SERIAL", "62dff1b0")
PKG = "dev.crossbar.launcher"
PORT = 9335
CODES = {"a": 304, "b": 305, "x": 307, "y": 308, "l1": 310, "r1": 311,
         "up": 544, "down": 545, "left": 546, "right": 547}
CATS = ["cc", "viz", "gpu", "benchmark", "blink", "renderer.scheduler", "v8.execute", "input", "toplevel",
        "devtools.timeline", "disabled-by-default-devtools.timeline", "disabled-by-default-devtools.timeline.frame",
        "disabled-by-default-cc.debug.scheduler.frames", "android_webview"]


def adb(*args, timeout=120):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, timeout=timeout).stdout.strip()


def pages():
    pid = adb("shell", "pidof", PKG)
    if not pid:
        sys.exit("CrossBar is not running")
    subprocess.run([ADB, "-s", SERIAL, "forward", "tcp:%d" % PORT, "localabstract:webview_devtools_remote_" + pid.split()[0]],
                   capture_output=True)
    with urllib.request.urlopen("http://127.0.0.1:%d/json" % PORT, timeout=5) as r:
        return [p for p in json.load(r) if p.get("type") == "page"]


def pick(sub):
    ps = pages()
    for p in ps:
        if (sub or "index.html") in p["url"]:
            return p
    sys.exit("no page matches %r" % sub)


class Session:
    def __init__(self, page):
        self.ws = websocket.create_connection(page["webSocketDebuggerUrl"], suppress_origin=True, timeout=120)
        self.n = 0
        self.events = []

    def call(self, method, **params):
        self.n += 1
        self.ws.send(json.dumps({"id": self.n, "method": method, "params": params}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == self.n:
                if "error" in msg:
                    raise RuntimeError(msg["error"])
                return msg.get("result", {})
            self.events.append(msg)


def press_script(keys, gap):
    dev = adb("shell", 'for d in /sys/class/input/event*; do [ "$(cat $d/device/name 2>/dev/null)" = "Odin Controller" ] && echo /dev/input/${d##*/}; done')
    s = ""
    for k in keys:
        c = CODES[k]
        s += "sendevent %s 1 %d 1; sendevent %s 0 0 0; sendevent %s 1 %d 0; sendevent %s 0 0 0; sleep %s; " % (dev, c, dev, dev, c, dev, gap)
    return s


def trace(name, keys, gap):
    sess = Session(pick(None))
    sess.call("Tracing.start", traceConfig={"includedCategories": CATS, "recordMode": "recordAsMuchAsPossible"},
              transferMode="ReportEvents")
    time.sleep(0.6)
    adb("shell", press_script(keys, gap), timeout=600)
    time.sleep(1.2)
    sess.call("Tracing.end")
    data = []
    for m in sess.events:
        if m.get("method") == "Tracing.dataCollected":
            data += m["params"]["value"]
    while True:
        m = json.loads(sess.ws.recv())
        if m.get("method") == "Tracing.dataCollected":
            data += m["params"]["value"]
        elif m.get("method") == "Tracing.tracingComplete":
            break
    path = "/tmp/cb_trace_%s.json" % name
    with open(path, "w") as f:
        json.dump({"traceEvents": data}, f)
    print("saved", path, len(data), "events")
    summary(path)


def summary(path):
    ev = json.load(open(path))["traceEvents"]
    names = {}
    for e in ev:
        if e.get("ph") == "M" and e.get("name") == "thread_name":
            names[(e["pid"], e["tid"])] = e["args"]["name"]
    by_thread = {}
    for e in ev:
        if e.get("ph") == "X" and "dur" in e:
            by_thread.setdefault(names.get((e["pid"], e["tid"]), "?"), []).append(e)
    t0 = min((e["ts"] for e in ev if e.get("ph") == "X"), default=0)
    t1 = max((e["ts"] + e.get("dur", 0) for e in ev if e.get("ph") == "X"), default=0)
    span = (t1 - t0) / 1e6
    print("span %.1f s" % span)
    for th in sorted(by_thread, key=lambda t: -sum(e["dur"] for e in by_thread[t] if e.get("name") in ("ThreadControllerImpl::RunTask", "RunTask"))):
        tasks = [e for e in by_thread[th] if e["name"] in ("ThreadControllerImpl::RunTask", "RunTask")]
        if not tasks:
            continue
        busy = sum(e["dur"] for e in tasks) / 1e3
        long = sorted((e for e in tasks if e["dur"] > 16000), key=lambda e: -e["dur"])
        print("%-28s busy %7.0f ms (%4.1f%%)  tasks>16ms: %d  worst: %s" % (
            th[:28], busy, 100 * busy / 1e3 / span if span else 0, len(long), [round(e["dur"] / 1e3, 1) for e in long[:6]]))
    main = [k for k in by_thread if k == "CrRendererMain"]
    if main:
        agg = {}
        for e in by_thread["CrRendererMain"]:
            n = e["name"]
            if n in ("FunctionCall", "EvaluateScript", "v8.run", "UpdateLayoutTree", "Layout", "Paint", "PrePaint",
                     "UpdateLayer", "Layerize", "CompositeLayers", "HitTest", "ParseHTML", "Decode Image", "GPUTask",
                     "TimerFire", "FireAnimationFrame", "ProxyMain::BeginMainFrame", "Commit", "V8.GC_SCAVENGER", "MinorGC", "MajorGC",
                     "V8.GCFinalizeMC", "ScheduleStyleRecalculation", "RecalculateStyles", "UpdateLayerTree"):
                a = agg.setdefault(n, [0, 0, 0])
                a[0] += 1
                a[1] += e["dur"]
                a[2] = max(a[2], e["dur"])
        print("renderer main thread breakdown (count, total ms, max ms):")
        for n, (c, d, mx) in sorted(agg.items(), key=lambda x: -x[1][1])[:14]:
            print("   %-28s %5d %8.1f %7.1f" % (n, c, d / 1e3, mx / 1e3))
    # Frames the renderer compositor produced, from cc's pipeline reporter when present.
    states = {}
    for e in ev:
        if e.get("name") == "PipelineReporter" and e.get("ph") in ("b", "X"):
            st = (e.get("args", {}).get("chrome_frame_reporter", {}) or {}).get("state") or e.get("args", {}).get("state")
            if st:
                states[st] = states.get(st, 0) + 1
    if states:
        print("PipelineReporter states:", states)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd = sys.argv[1]
    opt = lambda name, d=None: sys.argv[sys.argv.index(name) + 1] if name in sys.argv else d
    if cmd == "pages":
        for p in pages():
            print(p["url"])
    elif cmd == "eval":
        sess = Session(pick(opt("--page")))
        r = sess.call("Runtime.evaluate", expression=sys.argv[2], awaitPromise=True, returnByValue=True)
        print(json.dumps(r.get("result", {}).get("value", r), indent=1))
    elif cmd == "trace":
        trace(sys.argv[2], (opt("--keys") or "down down down down up up up up right right left left").split(), float(opt("--gap", "0.6")))
    elif cmd == "summary":
        summary(sys.argv[2])


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Measures how smoothly CrossBar animates on the Thor.

Presses the Thor's own controller (sendevent, like a thumb press) on a schedule, collects the
present time of every frame both CrossBar windows showed (SurfaceFlinger --latency) and reports
dropped frames in the animation window after each press.

  python3 dev/perf.py NAME [--keys "down down up right left"] [--gap 0.6] [--window 0.45]

Keys: up down left right a b x y l1 r1. Results go to /tmp/cb_perf_NAME.json.
"""
import argparse
import json
import re
import subprocess
import threading
import time

ADB = [__import__("os").path.expanduser("~/platform-tools/adb"), "-s", "62dff1b0"]
CODES = {"a": 304, "b": 305, "x": 307, "y": 308, "l1": 310, "r1": 311,
         "up": 544, "down": 545, "left": 546, "right": 547}


def sh(cmd, timeout=60):
    return subprocess.run(ADB + ["shell", cmd], capture_output=True, text=True, timeout=timeout).stdout


def layers():
    out = {}
    for line in sh("dumpsys SurfaceFlinger --list").splitlines():
        m = re.match(r"^dev\.crossbar\.launcher/dev\.crossbar\.launcher\.(MainActivity|BottomActivity)#\d+$", line.strip())
        if m:
            out.setdefault(m.group(1), []).append(line.strip())
    return out


def latency(layer):
    lines = sh("dumpsys SurfaceFlinger --latency '%s'" % layer).split("\n")
    period = int(lines[0]) if lines and lines[0].strip().isdigit() else 16666666
    frames = []
    for line in lines[1:]:
        p = line.split()
        if len(p) == 3:
            actual = int(p[1])
            if 0 < actual < 1 << 62:
                frames.append(actual)
    return period, frames


def pick(names):
    best, n = None, -1
    for name in names:
        _, f = latency(name)
        if len(f) > n:
            best, n = name, len(f)
    return best


def analyze(frames, presses, period, window):
    frames = sorted(set(frames))
    res = {"frames": len(frames), "presses": []}
    total_exp = total_got = 0
    worst = 0
    gaps = {1: 0, 2: 0, 3: 0, "4+": 0}
    for t in presses:
        lo, hi = t, t + window * 1e9
        inwin = [f for f in frames if lo <= f <= hi]
        # A press only animates once the app reacts; count from the first frame after it.
        if not inwin:
            res["presses"].append({"got": 0})
            continue
        start = inwin[0]
        exp = int(round((hi - start) / period)) + 1
        got = len(inwin)
        total_exp += exp
        total_got += got
        prev = start
        mx = 0
        for f in inwin[1:]:
            d = round((f - prev) / period)
            mx = max(mx, d)
            key = d if d <= 3 else "4+"
            if d >= 1:
                gaps[key] += 1
            prev = f
        worst = max(worst, mx)
        res["presses"].append({"react_ms": round((start - t) / 1e6, 1), "got": got, "exp": exp, "longest_gap_vsyncs": mx})
    res["expected"] = total_exp
    res["presented"] = total_got
    res["dropped_pct"] = round(100 * (1 - total_got / total_exp), 1) if total_exp else None
    res["longest_gap_vsyncs"] = worst
    res["gaps"] = gaps
    react = [p["react_ms"] for p in res["presses"] if "react_ms" in p]
    res["react_ms_median"] = sorted(react)[len(react) // 2] if react else None
    return res


def gfx_summary():
    out = sh("dumpsys gfxinfo dev.crossbar.launcher")
    keep = ("Total frames rendered", "Janky frames", "50th percentile", "90th percentile", "95th percentile",
            "99th percentile", "Number Missed Vsync", "Number Slow UI thread", "Number Slow issue draw commands",
            "Number Frame deadline missed", "Number Slow bitmap uploads")
    lines = []
    for line in out.splitlines():
        s = line.strip()
        if s.startswith(keep) and s not in lines:
            lines.append(s)
    return lines[:len(keep)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("name")
    ap.add_argument("--keys", default="down down down down up up up up right right left left")
    ap.add_argument("--gap", type=float, default=0.6)
    ap.add_argument("--window", type=float, default=0.45)
    ap.add_argument("--settle", type=float, default=1.5)
    a = ap.parse_args()

    dev = sh('for d in /sys/class/input/event*; do [ "$(cat $d/device/name 2>/dev/null)" = "Odin Controller" ] && echo /dev/input/${d##*/}; done').strip()
    if not dev:
        raise SystemExit("Odin Controller not found")
    found = layers()
    top = pick(found.get("MainActivity", []))
    bot = pick(found.get("BottomActivity", []))
    sh("dumpsys gfxinfo dev.crossbar.launcher reset >/dev/null; logcat -c")
    sh("dumpsys SurfaceFlinger --latency-clear")

    keys = a.keys.split()
    script = ""
    for k in keys:
        c = CODES[k]
        script += "log -t CBPERF %s; sendevent %s 1 %d 1; sendevent %s 0 0 0; sendevent %s 1 %d 0; sendevent %s 0 0 0; sleep %s; " % (
            k, dev, c, dev, dev, c, dev, a.gap)
    collected = {"top": [], "bot": []}
    period = 16666666
    done = threading.Event()

    def poll():
        nonlocal period
        while not done.is_set():
            for name, lay in (("top", top), ("bot", bot)):
                if lay:
                    period, f = latency(lay)
                    collected[name] += f
            time.sleep(0.8)

    th = threading.Thread(target=poll)
    th.start()
    time.sleep(0.5)
    sh(script, timeout=600)
    time.sleep(a.settle)
    done.set()
    th.join()
    for name, lay in (("top", top), ("bot", bot)):
        if lay:
            collected[name] += latency(lay)[1]

    # Press times on the monotonic clock SurfaceFlinger uses.
    presses = []
    for line in subprocess.run(ADB + ["logcat", "-d", "-v", "monotonic", "-s", "CBPERF"], capture_output=True, text=True).stdout.splitlines():
        m = re.match(r"\s*(\d+)\.(\d+)", line)
        if m and "CBPERF" in line:
            presses.append(int(m.group(1)) * 10**9 + int(m.group(2).ljust(9, "0")[:9]))
    result = {"name": a.name, "keys": keys, "gap": a.gap, "period_ms": round(period / 1e6, 2),
              "top": analyze(collected["top"], presses, period, a.window),
              "bottom": analyze(collected["bot"], presses, period, a.window),
              "gfxinfo": gfx_summary()}
    with open("/tmp/cb_perf_%s.json" % a.name, "w") as f:
        json.dump(result, f, indent=1)
    for scr in ("top", "bottom"):
        r = result[scr]
        print("%-6s presented %4s/%-4s in windows  dropped %5s%%  longest gap %s vsyncs  react %s ms  gaps %s" % (
            scr, r["presented"], r["expected"], r["dropped_pct"], r["longest_gap_vsyncs"], r["react_ms_median"], r["gaps"]))
    for line in result["gfxinfo"]:
        print("  " + line)


if __name__ == "__main__":
    main()

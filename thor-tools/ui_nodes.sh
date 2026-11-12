#!/bin/bash
# Print text/desc/bounds of interesting nodes on the Thor's current UI.
A=~/platform-tools/adb
$A shell 'uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml' | python3 -c '
import re, sys
x = sys.stdin.read()
for n in re.findall(r"<node [^>]*>", x):
    g = lambda k: (re.search(k + r"=\"([^\"]*)\"", n) or [None, ""])[1]
    if g("text") or g("content-desc"):
        print(repr(g("text"))[:50], repr(g("content-desc"))[:40], g("class").split(".")[-1], g("bounds"), "click" if g("clickable") == "true" else "")
'

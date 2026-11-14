#!/usr/bin/env python3
"""Prints the top screen's back/forward history (current entry marked with >)."""
import cdp

h = cdp.Session(cdp.pick('miruro')).call('Page.getNavigationHistory')
for i, e in enumerate(h['entries']):
    print(('>' if i == h['currentIndex'] else ' '), i, e['url'][:90])

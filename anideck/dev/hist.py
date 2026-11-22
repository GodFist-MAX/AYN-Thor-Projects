#!/usr/bin/env python3
"""Prints the top screen's page stack, oldest first (current page marked with >)."""
import cdp

r = cdp.Session(cdp.pick(None)).call('Runtime.evaluate', expression='app.routes()', returnByValue=True)
routes = r['result'].get('value') or []
for i, route in enumerate(routes):
    print(('>' if i == len(routes) - 1 else ' '), i, route[:90])

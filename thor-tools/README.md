# Thor tools

Small helpers for scripting the AYN Thor from a Mac over adb.

## ui_nodes.sh

This script prints the text, content description, class and on-screen bounds of every labeled UI element on the Thor's current screen, using `uiautomator dump`. Use it to find where to tap with `adb shell input -d 0 tap X Y`.

Each output line looks like this (text, description, class, bounds, and whether it's clickable):

```text
'CHOOSE FOLDER' '' Button [301,706][501,786] click
```

It works on standard Android screens, such as the system file picker. Animated or custom-drawn screens make uiautomator fail with "could not get idle state". When that happens the script prints the previous dump, so check with a screenshot.

## History

- **2026-11-11:** `ui_nodes.sh`, written while setting up the Naruto port.

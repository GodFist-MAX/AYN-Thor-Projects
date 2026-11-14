# Thor Finder

Thor Finder shows the AYN Thor's internal storage as a normal "AYN Thor" volume in macOS Finder, over USB. macOS can't read MTP devices on its own.

```sh
./thor-finder.sh        # mount (if needed) and open a Finder window
./thor-finder.sh stop   # unmount and stop everything
```

It needs the Thor connected over USB with USB debugging on, and adb at `~/platform-tools/adb`. If the Thor disconnects, the volume unmounts itself; run the script again after reconnecting.

## How it works

1. [rclone](https://rclone.org) runs on the Thor (`/data/local/tmp/rclone`) and serves `/storage/emulated/0` over WebDAV on an abstract socket. Only adb can reach that socket: not the network, and not other apps.
2. `adb forward` brings it to the Mac.
3. [`thor_webdav_proxy.py`](thor_webdav_proxy.py), which uses only Python's standard library, keeps macOS `._*` and `.DS_Store` files on the Mac (in `/private/tmp/thor-mac-metadata`) so they never land on the Thor, and passes everything else through.
4. `mount_webdav` mounts it at `/private/tmp/AYN Thor`. Mounting outside the home folder keeps background indexers from crawling the whole Thor.

## Setup

Download rclone's official `linux-arm64` build (v1.76.0 was used), check its SHA-256, and save the binary as `rclone-android` next to the script. The script copies it to the Thor when needed.

## Notes

- rclone doesn't report free space over WebDAV, so the volume shows 0 bytes free. Copying works anyway.
- The USB link runs at 480 Mb/s.

## History

- **2026-11-11:** First version.

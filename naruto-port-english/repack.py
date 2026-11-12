#!/usr/bin/env python3
"""Copy an APK, replacing classes.dex, keeping every entry's order, name and compression method."""
import sys
import zipfile


def main(src, dex_path, out):
    dex = open(dex_path, "rb").read()
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(out, "w") as zout:
        for info in zin.infolist():
            data = dex if info.filename == "classes.dex" else zin.read(info)
            entry = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            entry.compress_type = info.compress_type
            entry.external_attr = info.external_attr
            entry.create_system = info.create_system
            level = 9 if info.compress_type == zipfile.ZIP_DEFLATED else None
            zout.writestr(entry, data, compress_type=info.compress_type, compresslevel=level)


if __name__ == "__main__":
    main(*sys.argv[1:4])

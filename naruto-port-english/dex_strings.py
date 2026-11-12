#!/usr/bin/env python3
"""Print every string in a DEX file (one per line, escaped) so UI text can be found."""
import struct
import sys


def uleb128(buf, off):
    result = shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, off
        shift += 7


def mutf8(buf, off):
    end = buf.index(b"\x00", off)
    raw = buf[off:end].replace(b"\xc0\x80", b"\x00")
    return raw.decode("utf-8", errors="surrogateescape")


def strings(path):
    buf = open(path, "rb").read()
    size, ids_off = struct.unpack_from("<II", buf, 0x38)
    for i in range(size):
        (data_off,) = struct.unpack_from("<I", buf, ids_off + 4 * i)
        _, off = uleb128(buf, data_off)
        yield mutf8(buf, off)


if __name__ == "__main__":
    for s in strings(sys.argv[1]):
        print(s.encode("unicode_escape").decode("ascii"))

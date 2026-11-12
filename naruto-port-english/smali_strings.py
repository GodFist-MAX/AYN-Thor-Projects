#!/usr/bin/env python3
"""Shared helpers for reading and rewriting string literals in baksmali output."""
import pathlib
import re

CONST_STRING = re.compile(r'^(\s*const-string(?:/jumbo)? [vp]\d+, )"((?:[^"\\]|\\.)*)"\s*$')
FIELD_STRING = re.compile(r'^(\.field .*:Ljava/lang/String; = )"((?:[^"\\]|\\.)*)"\s*$')
ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f", "'": "'", '"': '"', "\\": "\\"}


def unescape(lit):
    out, i = [], 0
    while i < len(lit):
        c = lit[i]
        if c != "\\":
            out.append(c)
            i += 1
        elif lit[i + 1] == "u":
            out.append(chr(int(lit[i + 2:i + 6], 16)))
            i += 6
        else:
            out.append(ESCAPES[lit[i + 1]])
            i += 2
    return "".join(out)


def escape(text):
    out = []
    for c in text:
        if c == "\\":
            out.append("\\\\")
        elif c == '"':
            out.append('\\"')
        elif c == "\n":
            out.append("\\n")
        elif c == "\t":
            out.append("\\t")
        elif c == "\r":
            out.append("\\r")
        elif 0x20 <= ord(c) < 0x7F:
            out.append(c)
        else:
            out.append("\\u%04x" % ord(c))
    return "".join(out)


def smali_files(root):
    return sorted(pathlib.Path(root).rglob("*.smali"))


def literals(root):
    """Yield (path, line_no, prefix, text) for every const-string and String field initialiser."""
    for path in smali_files(root):
        for no, line in enumerate(path.read_text(encoding="utf-8").split("\n"), 1):
            m = CONST_STRING.match(line) or FIELD_STRING.match(line)
            if m:
                yield path, no, m.group(1), unescape(m.group(2))

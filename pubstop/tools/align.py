#!/usr/bin/env python3
"""Équivalent minimal de zipalign : réécrit un APK en alignant les entrées non
compressées sur 4 octets (exigé par Android 11+ pour resources.arsc)."""
import struct
import sys
import zipfile

STORED_SUFFIXES = (".arsc", ".png", ".jpg", ".webp", ".so")


def align(src, dst, boundary=4):
    with zipfile.ZipFile(src) as zin, open(dst, "wb") as raw:
        with zipfile.ZipFile(raw, "w") as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                info = zipfile.ZipInfo(item.filename, date_time=(1981, 1, 1, 0, 0, 0))
                info.external_attr = item.external_attr
                stored = item.compress_type == zipfile.ZIP_STORED or item.filename.endswith(STORED_SUFFIXES)
                if stored:
                    info.compress_type = zipfile.ZIP_STORED
                    start = raw.tell() + 30 + len(info.filename.encode("utf-8"))
                    pad = (-start) % boundary
                    info.extra = b"\0" * pad
                else:
                    info.compress_type = zipfile.ZIP_DEFLATED
                zout.writestr(info, data, compresslevel=9)


def check(path, boundary=4):
    """Vérifie l'alignement réel des données non compressées."""
    ok = True
    with zipfile.ZipFile(path) as z, open(path, "rb") as raw:
        for item in z.infolist():
            if item.compress_type != zipfile.ZIP_STORED:
                continue
            raw.seek(item.header_offset + 26)
            name_len, extra_len = struct.unpack("<HH", raw.read(4))
            offset = item.header_offset + 30 + name_len + extra_len
            if offset % boundary:
                print(f"NON ALIGNÉ : {item.filename} @ {offset}")
                ok = False
    return ok


if __name__ == "__main__":
    if sys.argv[1] == "--check":
        sys.exit(0 if check(sys.argv[2]) else 1)
    align(sys.argv[1], sys.argv[2])

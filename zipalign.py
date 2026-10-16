"""Minimal zipalign: rewrites an APK so stored (uncompressed) entries start on 4-byte boundaries."""
import sys
import zipfile


def align(src, dst, alignment=4):
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            new = zipfile.ZipInfo(info.filename, date_time=(2008, 1, 1, 0, 0, 0))
            new.compress_type = info.compress_type
            new.external_attr = info.external_attr
            new.create_system = 0
            if info.compress_type == zipfile.ZIP_STORED:
                # local header = 30 bytes + name + extra; pad extra so data is aligned
                offset = zout.fp.tell() + 30 + len(info.filename.encode("utf-8"))
                pad = (-offset) % alignment
                new.extra = b"\x00" * pad
            zout.writestr(new, data)


if __name__ == "__main__":
    align(sys.argv[1], sys.argv[2])

#!/usr/bin/env python3
"""Check iOS catalog coverage and Foundation format placeholders."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1] / "iosApp/iosApp"
ENTRY = re.compile(r'^"([^"\n]+)"\s*=\s*"((?:\\.|[^"\\])*)";', re.MULTILINE)
FORMAT = re.compile(r'%(?:\d+\$)?[@sdf]')


def check():
    catalogs = {}
    errors = []
    for path in sorted(ROOT.glob("*.lproj/Localizable.strings")):
        entries = ENTRY.findall(path.read_text())
        catalogs[path.parent.name] = dict(entries)
        if len(entries) != len(dict(entries)):
            errors.append(f"{path.parent.name}: duplicate keys")
        for key, value in entries:
            if any(token.endswith('s') for token in FORMAT.findall(value)):
                errors.append(f"{path.parent.name}: {key} uses a C string placeholder; use %@ for Swift strings")
    base = catalogs["Base.lproj"]
    for locale, entries in catalogs.items():
        for key in sorted(base.keys() - entries.keys()):
            errors.append(f"{locale}: missing {key}")
        for key in base.keys() & entries.keys():
            if sorted(FORMAT.findall(base[key])) != sorted(FORMAT.findall(entries[key])):
                errors.append(f"{locale}: {key} has mismatched format placeholders")
    if errors:
        print("\n".join(errors))
        print(f"FAIL: {len(errors)} localization errors")
        return 1
    print(f"PASS: {len(base)} keys across {len(catalogs)} iOS catalogs; format placeholders match")
    return 0


if __name__ == "__main__":
    sys.exit(check())

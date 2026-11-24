#!/usr/bin/env python3
"""Check iOS catalogs against each other and against keys the SwiftUI app looks up."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
IOS = ROOT / "iosApp/iosApp"
ENTRY = re.compile(r'^"((?:\\.|[^"\\])*)"\s*=\s*"((?:\\.|[^"\\])*)";', re.MULTILINE)
FORMAT = re.compile(r'%(?:\d+\$)?[@sdf]')
KEY = re.compile(r'^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$')
COMMENT = re.compile(r'/\*.*?\*/|//.*?$', re.DOTALL | re.MULTILINE)
SYSTEM_IMAGE = re.compile(r'\b(?:systemName|systemImage):\s*"((?:\\.|[^"\\])*)"')
LOOKUP = re.compile(
    r'\b(?:NSLocalizedString|LocalizedStringKey|String\s*\(\s*localized:)\s*\(\s*"((?:\\.|[^"\\])*)"'
)
LOCALIZING_CALL = re.compile(
    r'\b(?:Text|Button|Section|Toggle|Picker|Label|SecureField|TextField|Link|ProgressView|'
    r'navigationTitle|confirmationDialog|alert|accessibilityLabel|accessibilityHint|accessibilityValue)\s*\('
)
STRING_LITERAL = re.compile(r'"((?:\\.|[^"\\])*)"')

# Keys built at runtime from a fixed set of suffixes.
DYNAMIC_KEYS = {
    "phrase.symbol.package.": ["all", "opensymbols", "mulberry", "arasaac"],
    "boardset.cell.word_type.": ["pronoun", "verb", "descriptor", "noun", "social", "other"],
    "phrase.symbol.error.": ["missing_proxy", "search_failed"],
}


def load_catalog(path: Path) -> dict:
    return dict(ENTRY.findall(path.read_text()))


def matching_paren(text: str, open_index: int) -> int:
    depth = 0
    i = open_index
    while i < len(text):
        char = text[i]
        if char == '"':
            i += 1
            while i < len(text) and text[i] != '"':
                if text[i] == '\\':
                    i += 1
                i += 1
        elif char == '(':
            depth += 1
        elif char == ')':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(text)


def referenced_keys(source: str) -> set:
    keys = {key for key in LOOKUP.findall(source) if "\\(" not in key}
    stripped = SYSTEM_IMAGE.sub('""', source)
    for match in LOCALIZING_CALL.finditer(stripped):
        open_paren = match.end() - 1
        close = matching_paren(stripped, open_paren)
        argument = stripped[open_paren + 1:close]
        # Only the title/key argument. Image names after systemImage: are not catalog keys.
        head = argument.split("{", 1)[0]
        head = re.split(r"\bsystemImage:|\bsystemName:", head)[0]
        for literal in STRING_LITERAL.findall(head):
            if "\\(" in literal:
                continue
            if KEY.match(literal):
                keys.add(literal)
    for prefix, suffixes in DYNAMIC_KEYS.items():
        if f'"{prefix}\\(' in source or f'"{prefix}$(' in source:
            keys.update(prefix + suffix for suffix in suffixes)
    return keys


def unwrapped_conditionals(source: str, path: Path) -> list:
    errors = []
    for match in LOCALIZING_CALL.finditer(source):
        open_paren = match.end() - 1
        close = matching_paren(source, open_paren)
        argument = source[open_paren + 1:close]
        head = argument.split("{", 1)[0]
        head = re.split(r"\bsystemImage:|\bsystemName:", head)[0]
        literals = [
            literal for literal in STRING_LITERAL.findall(head)
            if "\\(" not in literal and KEY.match(literal)
        ]
        if "?" not in head or not literals:
            continue
        if "LocalizedStringKey" in head or "NSLocalizedString" in head or "String(localized:" in head:
            continue
        line = source[:match.start()].count("\n") + 1
        errors.append(
            f"{path.relative_to(ROOT)}:{line}: conditional localization key is passed as a plain string "
            f"({', '.join(literals)}); wrap it in LocalizedStringKey or NSLocalizedString"
        )
    return errors


def check():
    errors = []
    catalogs = {}
    for path in sorted(IOS.glob("*.lproj/Localizable.strings")):
        entries = ENTRY.findall(path.read_text())
        catalogs[path.parent.name] = dict(entries)
        if len(entries) != len(dict(entries)):
            errors.append(f"{path.parent.name}: duplicate Localizable keys")
        for key, value in entries:
            if value.strip() == "":
                errors.append(f"{path.parent.name}: empty {key}")
            if any(token.endswith("s") for token in FORMAT.findall(value)):
                errors.append(f"{path.parent.name}: {key} uses a C string placeholder; use %@ for Swift strings")

    if "Base.lproj" not in catalogs:
        errors.append("Base.lproj/Localizable.strings is missing")
        print("\n".join(errors))
        return 1

    base = catalogs["Base.lproj"]
    for locale, entries in catalogs.items():
        for key in sorted(base.keys() - entries.keys()):
            errors.append(f"{locale}: missing {key}")
        for key in sorted(entries.keys() - base.keys()):
            errors.append(f"{locale}: extra {key} is not in Base")
        for key in base.keys() & entries.keys():
            if sorted(FORMAT.findall(base[key])) != sorted(FORMAT.findall(entries[key])):
                errors.append(f"{locale}: {key} has mismatched format placeholders")

    info_catalogs = {}
    for path in sorted(IOS.glob("*.lproj/InfoPlist.strings")):
        entries = ENTRY.findall(path.read_text())
        info_catalogs[path.parent.name] = dict(entries)
        if len(entries) != len(dict(entries)):
            errors.append(f"{path.parent.name}: duplicate InfoPlist keys")
        for key, value in entries:
            if value.strip() == "":
                errors.append(f"{path.parent.name}: empty InfoPlist {key}")
    info_base = info_catalogs.get("Base.lproj", {})
    if not info_base:
        errors.append("Base.lproj/InfoPlist.strings is missing")
    for locale, entries in info_catalogs.items():
        for key in sorted(info_base.keys() - entries.keys()):
            errors.append(f"{locale}: missing InfoPlist {key}")
        for key in sorted(entries.keys() - info_base.keys()):
            errors.append(f"{locale}: extra InfoPlist {key} is not in Base")

    used = set()
    for path in sorted(IOS.rglob("*.swift")):
        if "iosAppTests" in path.parts:
            continue
        source = COMMENT.sub("", path.read_text())
        used |= referenced_keys(source)
        errors.extend(unwrapped_conditionals(source, path))

    for key in sorted(used):
        for locale, entries in catalogs.items():
            value = entries.get(key)
            if value is None:
                errors.append(f"{locale}: referenced key missing: {key}")
            elif value.strip() == "":
                errors.append(f"{locale}: referenced key empty: {key}")

    project = (ROOT / "iosApp/iosApp.xcodeproj/project.pbxproj").read_text()
    if not re.search(r"developmentRegion = en;", project):
        errors.append("developmentRegion must be en so Base.lproj English is the fallback language")
    if re.search(r"^\s*dk,", project, re.MULTILINE):
        errors.append("knownRegions includes dk, which is not an iOS language code; Danish is da")

    if errors:
        print("\n".join(errors))
        print(f"FAIL: {len(errors)} localization errors")
        return 1
    print(
        f"PASS: {len(base)} Localizable keys and {len(info_base)} InfoPlist keys "
        f"across {len(catalogs)} locales; {len(used)} referenced keys are present"
    )
    return 0


if __name__ == "__main__":
    sys.exit(check())

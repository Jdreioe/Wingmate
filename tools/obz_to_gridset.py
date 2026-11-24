#!/usr/bin/env python3
"""Convert a Wingmate Open Board archive to a Smartbox Grid 3 grid set."""

import argparse
import base64
import binascii
from collections import Counter, defaultdict
import json
from pathlib import Path
import re
import sys
import unicodedata
from uuid import uuid4
from xml.etree import ElementTree as ET
from zipfile import BadZipFile, ZIP_DEFLATED, ZipFile


FORMAT = "open-board-0.1"
SETTINGS_PATH = "Settings0/settings.xml"
SCREEN_SETTINGS = "ext_wingmate_screen_settings"
PAGE_SETTINGS = "ext_wingmate_page_settings"
XSI = {"xmlns:xsi": "http://www.w3.org/2001/XMLSchema-instance"}
XML_SPACE = "{http://www.w3.org/XML/1998/namespace}space"
MAX_JSON = 5 * 1024 * 1024
MAX_MEDIA = 25 * 1024 * 1024
MAX_ARCHIVE = 512 * 1024 * 1024

IMAGE_TYPES = {
    "image/png": "png",
    "image/jpeg": "jpeg",
    "image/jpg": "jpeg",
    "image/gif": "gif",
    "image/bmp": "bmp",
    "image/webp": "webp",
}
SOUND_TYPES = {
    "audio/mpeg": "mp3",
    "audio/mp3": "mp3",
    "audio/wav": "wav",
    "audio/x-wav": "wav",
    "audio/wave": "wav",
    "audio/mp4": "m4a",
    "audio/m4a": "m4a",
    "audio/x-m4a": "m4a",
    "audio/x-ms-wma": "wma",
}
COMMANDS = {
    ":space": "Action.Space",
    ":clear": "Action.Clear",
    ":speak": "Action.Speak",
    ":home": "Jump.Home",
    ":back": "Jump.Back",
    ":stop": "Speech.Stop",
}


class ConversionError(ValueError):
    pass


def original_id(value):
    # OBF IDs are often strings, but some exporters use integers.
    if type(value) not in (str, int) or value == "":
        raise ConversionError("An OBZ object has a missing or invalid ID.")
    return str(value)


def read_entry(archive, path, limit):
    try:
        entry = archive.getinfo(path)
    except KeyError as error:
        raise ConversionError("A file referenced in the OBZ is missing.") from error
    if entry.is_dir() or entry.file_size > limit:
        raise ConversionError("A referenced OBZ file is a directory or exceeds the size limit.")
    with archive.open(entry) as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ConversionError("A referenced OBZ file exceeds the size limit.")
    return data


def read_object(archive, path):
    data = json.loads(read_entry(archive, path, MAX_JSON).decode("utf-8-sig"))
    if not isinstance(data, dict) or data.get("format") != FORMAT:
        raise ConversionError("The OBZ manifest and boards must use open-board-0.1.")
    return data


def extension(obj, key, default=None):
    return obj.get(key, (obj.get("extensions") or {}).get(key, default))


def localized_text(board, value):
    if not value:
        return ""
    locale = (board.get("locale") or "").lower()
    strings = {key.lower(): values for key, values in (board.get("strings") or {}).items()}
    exact = strings.get(locale, {})
    language = locale.split("-", 1)[0]
    general = strings.get(language, {})
    return exact.get(value, general.get(value, value))


def grid_names(boards):
    """Grid 3 uses Windows folder names as navigation targets."""
    names = {}
    used = set()
    for index, board in enumerate(boards, 1):
        name = unicodedata.normalize("NFC", localized_text(board, board.get("name")))
        name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")[:100].rstrip(" .")
        if not name:
            name = f"Page {index}"
        if re.fullmatch(r"CON|PRN|AUX|NUL|(?:COM|LPT)[1-9¹²³]", name.split(".")[0].strip(), re.I):
            name = f"_{name}"
        candidate = name
        suffix = 2
        while candidate.casefold() in used:
            candidate = f"{name} ({suffix})"
            suffix += 1
        names[original_id(board.get("id"))] = candidate
        used.add(candidate.casefold())
    return names


def grid_fields(board):
    grid = board.get("grid")
    if not isinstance(grid, dict):
        raise ConversionError("A Page requires an OBF grid layout.")
    rows, columns = grid.get("rows"), grid.get("columns")
    if type(rows) is not int or type(columns) is not int or min(rows, columns) < 1 or rows * columns > 10_000:
        raise ConversionError("A Page has invalid grid dimensions.")
    order = grid.get("order")
    if not isinstance(order, list) or len(order) != rows or any(
        not isinstance(line, list) or len(line) != columns for line in order
    ):
        raise ConversionError("A Page's grid order does not match its dimensions.")

    positions = defaultdict(list)
    for y, line in enumerate(order):
        for x, button_id in enumerate(line):
            if button_id is not None:
                positions[original_id(button_id)].append((x, y))

    fields = []
    for button_id, points in positions.items():
        x, y = points[0]
        width = max(point[0] for point in points) - min(point[0] for point in points) + 1
        height = points[-1][1] - y + 1
        if len(points) == width * height:
            fields.append((y, x, height, width, button_id))
        else:
            # Wingmate treats nonrectangular repeats as individual buttons.
            fields.extend((py, px, 1, 1, button_id) for px, py in points)
    return rows, columns, sorted(fields)


def xml_text(parent, tag, value, attributes=None):
    node = ET.SubElement(parent, tag, attributes or {})
    node.text = value
    return node


def write_xml(archive, path, root):
    # Pretty-printing would add whitespace to Grid's rich text runs.
    data = ET.tostring(root, encoding="utf-8", xml_declaration=True)
    ET.fromstring(data)  # Reject text that cannot be represented in XML 1.0.
    archive.writestr(path, data)


def color(value):
    if not isinstance(value, str):
        return None
    value = value.strip()
    if re.fullmatch(r"#(?:[0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})", value, re.I):
        digits = value[1:]
        if len(digits) in (3, 4):
            digits = "".join(digit * 2 for digit in digits)
        return "#" + (digits + "FF" if len(digits) == 6 else digits).upper()
    match = re.fullmatch(
        r"rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*(0?\.\d+|[01]))?\s*\)",
        value,
        re.I,
    )
    if match:
        rgb = [int(part) for part in match.groups()[:3]]
        if max(rgb) <= 255:
            alpha = round(255 * float(match[4] or "1"))
            return "#" + "".join(f"{part:02X}" for part in [*rgb, alpha])
    return None


def media(archive, button, board, paths, kind, types, warnings):
    media_id = button.get(f"{kind}_id")
    if media_id is None:
        return None
    media_id = original_id(media_id)
    item = next(
        (item for item in (board.get(f"{kind}s") or []) if str(item.get("id")) == media_id),
        {},
    )
    candidates = ((paths.get(f"{kind}s") or {}).get(media_id), item.get("path"))
    path = next((value for value in candidates if value and value in archive.NameToInfo), None)
    mime = item.get("content_type")
    try:
        if path:
            data = read_entry(archive, path, MAX_MEDIA)
        else:
            inline = next(
                (item[key] for key in ("data", "data_url", "url") if item.get(key)
                 and (key == "data" or item[key].startswith("data:"))),
                None,
            )
            if inline is None:
                warnings[f"unavailable {kind}"] += 1
                return None
            if inline.startswith("data:"):
                header, separator, inline = inline.partition(",")
                if not separator or not header.endswith(";base64"):
                    raise ConversionError("Invalid media data URL.")
                mime = header[5:].split(";", 1)[0] or mime
            data = base64.b64decode("".join(inline.split()), validate=True)

        suffix = Path(path).suffix.lower()[1:] if path else ""
        encoding = types.get((mime or "").lower())
        if suffix == "jpg":
            suffix = "jpeg"
        if encoding is None and suffix in types.values():
            encoding = suffix
        if encoding and data:
            return encoding, data
    except (ConversionError, binascii.Error):
        pass
    warnings[f"unsupported or unreadable {kind}"] += 1
    return None


def insert_text(commands, text, spelling):
    command = ET.SubElement(commands, "Command", ID="Action.InsertText")
    paragraph = ET.SubElement(ET.SubElement(command, "Parameter", Key="text"), "p")
    for run in (text, " ") if not spelling and not text[-1].isspace() else (text,):
        xml_text(ET.SubElement(paragraph, "s"), "r", run, {XML_SPACE: "preserve"})


def button_commands(button, board, settings, target, sound, commands, warnings):
    spelling = extension(board, "ext_wingmate_spelling_mode", False)
    actions = button.get("actions") or ([button["action"]] if button.get("action") else [])
    if actions:
        for action in actions:
            key = action.strip().lower()
            if key in COMMANDS:
                ET.SubElement(commands, "Command", ID=COMMANDS[key])
            elif key == ":backspace":
                command = "Action.DeleteLetter" if spelling else "Action.DeleteWord"
                ET.SubElement(commands, "Command", ID=command)
            elif action.startswith("+") and len(action) > 1:
                insert_text(commands, action[1:], spelling)
            else:
                warnings["unsupported button action"] += 1
        return False
    if target:
        command = ET.SubElement(commands, "Command", ID="Jump.To")
        xml_text(command, "Parameter", target, {"Key": "grid"})
        return False
    if button.get("load_board"):
        warnings["unresolved page link"] += 1
        return False
    if extension(button, "ext_wingmate_button_type") == "ngram_prediction":
        warnings["Wingmate prediction button"] += 1
        return False

    raw = button.get("vocalization")
    text = localized_text(board, button.get("label") if raw is None else raw)
    behavior = settings.get("activationBehavior") or "speak_and_add"
    speak = behavior != "add_only" and settings.get("speechPolicy") != "sentence_only"
    add = behavior != "speak_only"
    play_recording = speak and sound is not None
    if play_recording:
        # Grid resolves a relative filedata reference by cell and command index.
        command = ET.SubElement(commands, "Command", ID="SpeechPlaySound")
        xml_text(ET.SubElement(command, "Parameter", Key="filedata"), "data", f".{sound[0]}")
    elif speak and text:
        command = ET.SubElement(commands, "Command", ID="Speech.SpeakNow")
        xml_text(command, "Parameter", text, {"Key": "text", XML_SPACE: "preserve"})
    if text and add:
        insert_text(commands, text, spelling)
    if sound and not play_recording:
        warnings["recording skipped by the Page's speech settings"] += 1
    if (text or sound) and settings.get("returnBehavior") in ("previous", "start_page"):
        command = "Jump.Back" if settings["returnBehavior"] == "previous" else "Jump.Home"
        ET.SubElement(commands, "Command", ID=command)
    return play_recording


def render_grid(source, output, board, name, settings, paths, link_target, warnings):
    rows, columns, fields = grid_fields(board)
    buttons = {}
    for button in board.get("buttons") or []:
        button_id = original_id(button.get("id"))
        if button_id in buttons:
            raise ConversionError("A Page contains duplicate button IDs.")
        buttons[button_id] = button

    grid = ET.Element("Grid", XSI)
    if background := color(board.get("background_color")):
        xml_text(grid, "BackgroundColour", background)
    elif board.get("background_color"):
        warnings["unsupported Page color"] += 1
    xml_text(grid, "GridGuid", str(uuid4()))
    column_defs = ET.SubElement(grid, "ColumnDefinitions")
    for _ in range(columns):
        ET.SubElement(column_defs, "ColumnDefinition")
    chat = settings.get("showMessageBar") is not False
    row_defs = ET.SubElement(grid, "RowDefinitions")
    for _ in range(rows + chat):
        ET.SubElement(row_defs, "RowDefinition")
    cells = ET.SubElement(grid, "Cells")
    if chat:
        cell = ET.SubElement(cells, "Cell", ColumnSpan=str(columns))
        content = ET.SubElement(cell, "Content")
        xml_text(content, "ContentType", "Workspace")
        xml_text(content, "ContentSubType", "Chat")
        xml_text(ET.SubElement(content, "Style"), "BasedOnStyle", "Workspace")

    folder = f"Grids/{name}"
    assets = []
    for y, x, height, width, button_id in fields:
        if button_id not in buttons:
            raise ConversionError("A grid references a missing button.")
        button = buttons[button_id]
        if button.get("hidden"):
            continue
        y += chat
        cell = ET.SubElement(cells, "Cell", X=str(x), Y=str(y), RowSpan=str(height), ColumnSpan=str(width))
        content = ET.SubElement(cell, "Content")
        commands = ET.Element("Commands")
        target = link_target(button.get("load_board") or {})
        sound = None
        if not (button.get("load_board") or button.get("action") or button.get("actions")):
            sound = media(source, button, board, paths, "sound", SOUND_TYPES, warnings)
        if button_commands(button, board, settings, target, sound, commands, warnings):
            asset = f"{folder}/{x}-{y}-0-filedata.{sound[0]}"
            output.writestr(asset, sound[1])
            assets.append(asset)
        if len(commands):
            content.append(commands)
        caption = ET.SubElement(content, "CaptionAndImage")
        label = localized_text(board, button.get("label"))
        image = None
        if settings.get("showSymbols") is not False:
            image = media(source, button, board, paths, "image", IMAGE_TYPES, warnings)
        if settings.get("showLabels") is not False or not image:
            fallback = target or localized_text(board, button.get("vocalization"))
            xml_text(caption, "Caption", label or (fallback if not image else ""))
        if image:
            # Grid prefixes a caption image extension with the cell's X-Y coordinates.
            xml_text(caption, "Image", f".{image[0]}")
            asset = f"{folder}/{x}-{y}.{image[0]}"
            output.writestr(asset, image[1])
            assets.append(asset)

        style = ET.SubElement(content, "Style")
        xml_text(style, "BasedOnStyle", "Default")
        for source_key, target_key in (("background_color", "BackColour"), ("border_color", "BorderColour")):
            value = color(button.get(source_key))
            if value:
                xml_text(style, target_key, value)
            elif button.get(source_key):
                warnings["unsupported button color"] += 1

    if any(extension(board, key) for key in (
        "ext_wingmate_page_elements", "ext_wingmate_background_image",
    )):
        warnings["Page with Wingmate-specific layout elements or background image"] += 1
    path = f"{folder}/grid.xml"
    write_xml(output, path, grid)
    return path, assets


def convert_archive(source, output):
    """Convert an open OBZ to an open output ZipFile and return warning counts."""
    entries = source.infolist()
    if len(entries) > 5_000 or sum(item.file_size for item in entries) > MAX_ARCHIVE:
        raise ConversionError("The OBZ exceeds the archive size limits.")
    if len({item.filename for item in entries}) != len(entries):
        raise ConversionError("The OBZ contains duplicate archive entries.")

    manifest = read_object(source, "manifest.json")
    paths = manifest.get("paths") or {}
    indexed = {str(key): value for key, value in (paths.get("boards") or {}).items()}
    root_path = manifest.get("root")
    if not isinstance(root_path, str) or not root_path:
        raise ConversionError("The OBZ manifest does not specify a starting board.")
    if root_path not in source.NameToInfo:
        root_path = indexed.get(root_path, root_path)
    boards_by_path = {path: read_object(source, path) for path in dict.fromkeys([root_path, *indexed.values()])}
    boards = list(boards_by_path.values())
    names = grid_names(boards)
    if len(names) != len(boards):
        raise ConversionError("The OBZ contains duplicate board IDs.")

    def link_target(link):
        link_id = str(link.get("id"))
        linked_board = boards_by_path.get(link.get("path")) or boards_by_path.get(indexed.get(link_id))
        if link_id in names:
            return names[link_id]
        if linked_board:
            return names[original_id(linked_board.get("id"))]
        return None

    settings = ET.Element("GridSetSettings", XSI)
    root = boards_by_path[root_path]
    if locale := root.get("locale"):
        xml_text(settings, "PrimaryLanguage", locale)
    xml_text(settings, "StartGrid", names[original_id(root.get("id"))])
    xml_text(settings, "GridSetFileFormatVersion", "1")
    write_xml(output, SETTINGS_PATH, settings)

    file_map = ET.Element("FileMap", XSI)
    map_entries = ET.SubElement(file_map, "Entries")
    warnings = Counter()
    screen_settings = extension(manifest, SCREEN_SETTINGS) or {}
    for board in boards:
        board_settings = screen_settings | {
            key: value for key, value in (extension(board, PAGE_SETTINGS) or {}).items() if value is not None
        }
        name = names[original_id(board.get("id"))]
        path, assets = render_grid(source, output, board, name, board_settings, paths, link_target, warnings)
        if assets:
            entry = ET.SubElement(map_entries, "Entry", StaticFile=path.replace("/", "\\"))
            dynamic = ET.SubElement(entry, "DynamicFiles")
            for asset in assets:
                xml_text(dynamic, "File", asset.replace("/", "\\"))
    write_xml(output, "FileMap.xml", file_map)
    return len(boards), warnings


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Wingmate .obz export")
    parser.add_argument("output", nargs="?", type=Path, help="output .gridset (defaults to the source basename)")
    args = parser.parse_args(argv)
    output = args.output or args.source.with_suffix(".gridset")
    if args.source.suffix.lower() != ".obz" or output.suffix.lower() != ".gridset":
        parser.error("expected a .obz input and .gridset output")

    created = False
    try:
        with ZipFile(args.source) as source, output.open("xb") as stream:
            created = True
            with ZipFile(stream, "w", compression=ZIP_DEFLATED) as destination:
                count, warnings = convert_archive(source, destination)
    except FileExistsError:
        parser.exit(1, "Conversion failed: the output file already exists. Choose a new path.\n")
    except (OSError, BadZipFile, RuntimeError, NotImplementedError, ValueError, TypeError,
            AttributeError, ET.ParseError, RecursionError, OverflowError) as error:
        if created:
            output.unlink(missing_ok=True)
        reason = str(error) if isinstance(error, ConversionError) else "could not read the OBZ or write the Grid Set"
        parser.exit(1, f"Conversion failed: {reason.rstrip('.')}.\n")

    print(f"Created {output.name} with {count} page(s).")
    for warning, instances in sorted(warnings.items()):
        print(f"Warning: {warning} ({instances}).", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

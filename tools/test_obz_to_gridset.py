import base64
from io import BytesIO
import json
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
import wave
from xml.etree import ElementTree as ET
from zipfile import ZipFile

from obz_to_gridset import FORMAT, PAGE_SETTINGS, SCREEN_SETTINGS, convert_archive


PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVQI12NguPABAAKUAcEDPWywAAAAAElFTkSuQmCC"
)
SAMPLES = Path(__file__).resolve().parents[1] / "samples"


def read_xml(archive, path):
    return ET.fromstring(archive.read(path))


def write_fixture(path):
    audio = BytesIO()
    with wave.open(audio, "wb") as wav:
        wav.setparams((1, 2, 8000, 0, "NONE", "not compressed"))
        wav.writeframes(b"\0\0" * 800)
    manifest = {
        "format": FORMAT,
        "root": "boards/home.obf",
        "paths": {
            "boards": {"home": "boards/home.obf", "more": "boards/more.obf"},
            "sounds": {"voice": "sounds/voice.wav"},
        },
        SCREEN_SETTINGS: {"returnBehavior": "stay", "showMessageBar": True},
    }
    root = {
        "format": FORMAT,
        "id": "home",
        "name": "Mål & ord",
        "locale": "da-DK",
        "strings": {"da": {"label-key": "Hej & velkommen"}},
        "grid": {"rows": 2, "columns": 3, "order": [[1, 1, 2], [3, None, 4]]},
        "buttons": [
            {"id": 1, "label": "label-key", "vocalization": "Goddag",
             "image_id": "picture", "sound_id": "voice", "background_color": "#6de"},
            {"id": 2, "label": "Flere ord", "load_board": {"path": "boards/more.obf"}},
            {"id": 3, "label": "Hidden", "hidden": True},
            {"id": 4, "label": "Ryd", "actions": [":clear", ":speak"]},
        ],
        "images": [
            {"id": "picture", "data_url": "data:image/png;base64," + base64.b64encode(PNG).decode()},
        ],
        "sounds": [{"id": "voice", "content_type": "audio/wav"}],
    }
    more = {
        "format": FORMAT,
        "id": "more",
        "name": "mål & ord",
        PAGE_SETTINGS: {"showMessageBar": False, "activationBehavior": "speak_only"},
        "grid": {"rows": 1, "columns": 3, "order": [["home", "missing", "speak"]]},
        "buttons": [
            {"id": "home", "label": "Hjem", "action": ":home"},
            {"id": "missing", "label": "Missing", "image_id": "remote"},
            {"id": "speak", "label": "Say this", "vocalization": "Whole phrase"},
        ],
        "images": [{"id": "remote", "url": "https://invalid.example/image.png"}],
    }
    with ZipFile(path, "w") as archive:
        archive.writestr("manifest.json", json.dumps(manifest))
        archive.writestr("boards/home.obf", json.dumps(root))
        archive.writestr("boards/more.obf", json.dumps(more))
        archive.writestr("sounds/voice.wav", audio.getvalue())
    return audio.getvalue()


class ObzToGridsetTest(unittest.TestCase):
    def test_grid_layout_commands_and_media_references(self):
        source_bytes = BytesIO()
        expected_audio = write_fixture(source_bytes)
        output_bytes = BytesIO()
        with ZipFile(source_bytes) as source, ZipFile(output_bytes, "w") as output:
            pages, warnings = convert_archive(source, output)
        self.assertEqual(pages, 2)
        self.assertEqual(warnings, {"unavailable image": 1})
        with ZipFile(output_bytes) as output:
            settings = read_xml(output, "Settings0/settings.xml")
            self.assertEqual(settings.findtext("StartGrid"), "Mål & ord")
            root = read_xml(output, "Grids/Mål & ord/grid.xml")
            cells = root.findall("./Cells/Cell")
            self.assertEqual(len(root.findall("./RowDefinitions/RowDefinition")), 3)
            self.assertEqual(len(cells), 4)
            chat, word, jump, clear = cells
            self.assertEqual(chat.findtext("./Content/ContentSubType"), "Chat")
            self.assertEqual((word.get("X"), word.get("Y"), word.get("ColumnSpan")), ("0", "1", "2"))
            self.assertEqual(word.findtext(".//Caption"), "Hej & velkommen")
            self.assertEqual(word.findtext(".//BackColour"), "#66DDEEFF")
            self.assertEqual([c.get("ID") for c in word.findall(".//Command")],
                             ["SpeechPlaySound", "Action.InsertText"])
            self.assertEqual([run.text for run in word.findall(".//r")], ["Goddag", " "])
            self.assertEqual(word.findtext(".//Image"), ".png")
            self.assertEqual(word.findtext(".//Parameter[@Key='filedata']/data"), ".wav")
            self.assertEqual(output.read("Grids/Mål & ord/0-1.png"), PNG)
            self.assertEqual(output.read("Grids/Mål & ord/0-1-0-filedata.wav"), expected_audio)
            self.assertEqual(jump.findtext(".//Parameter[@Key='grid']"), "mål & ord (2)")
            self.assertEqual([c.get("ID") for c in clear.findall(".//Command")],
                             ["Action.Clear", "Action.Speak"])
            more = read_xml(output, "Grids/mål & ord (2)/grid.xml")
            self.assertEqual(len(more.findall("./RowDefinitions/RowDefinition")), 1)
            self.assertEqual(more.find(".//Command").get("ID"), "Jump.Home")
            self.assertEqual(more.findall(".//Command")[-1].findtext("./Parameter"), "Whole phrase")

            file_map = read_xml(output, "FileMap.xml")
            entry, = file_map.findall("./Entries/Entry")
            mapped = [path.text.replace("\\", "/") for path in entry.findall(".//File")]
            self.assertEqual(set(mapped), {"Grids/Mål & ord/0-1.png", "Grids/Mål & ord/0-1-0-filedata.wav"})
            self.assertTrue(all(path in output.namelist() for path in mapped))

    def test_cli_converts_wingmate_samples_and_preserves_existing_output(self):
        script = Path(__file__).with_name("obz_to_gridset.py")
        with TemporaryDirectory() as directory:
            for filename, root, page_count in (
                ("board-set-starter.obz", "Hello", 2),
                ("kommunikationstavler.obz", "Hilsner", 4),
            ):
                output = Path(directory) / (Path(filename).stem + ".gridset")
                command = [sys.executable, str(script), str(SAMPLES / filename), str(output)]
                result = subprocess.run(command, capture_output=True, check=True)
                self.assertEqual(result.stderr, b"")
                with ZipFile(output) as archive:
                    self.assertIsNone(archive.testzip())
                    self.assertEqual(read_xml(archive, "Settings0/settings.xml").findtext("StartGrid"), root)
                    self.assertEqual(sum(name.endswith("/grid.xml") for name in archive.namelist()), page_count)
                before = output.read_bytes()
                self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
                self.assertEqual(output.read_bytes(), before)

            broken = Path(directory) / "broken.obz"
            with ZipFile(broken, "w") as archive:
                archive.writestr("manifest.json", json.dumps({"format": FORMAT, "root": "missing.obf"}))
            self.assertNotEqual(
                subprocess.run([sys.executable, str(script), str(broken)], capture_output=True).returncode, 0,
            )
            self.assertFalse(broken.with_suffix(".gridset").exists())


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Regenerate Wingmate's pinned Piper voice catalog.

Usage: python3 -I scripts/piper/generate_catalog.py

Reads Piper's voices.json (rhasspy/piper-voices) for language and quality
metadata, then pins each voice to sherpa-onnx's converted model on Hugging Face
(csukuangfj/vits-piper-<key>), which carries the metadata sherpa-onnx needs.
Each voice also links Piper's own speaker-0 sample clip, played by "Test voice".
Every file is pinned by size and SHA-256 at a fixed revision, so the app can
verify downloads and a changed upstream file is rejected instead of trusted.

Voices whose model card names no dataset licence, or "Unknown", are left out.
"""

import hashlib
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

PIPER_API = "https://huggingface.co/api/models/rhasspy/piper-voices"
PIPER_FILE = "https://huggingface.co/rhasspy/piper-voices/resolve/{revision}/{path}"
HF_API = "https://huggingface.co/api/models/csukuangfj/vits-piper-{key}"
HF_FILE = "https://huggingface.co/csukuangfj/vits-piper-{key}/resolve/{revision}/{path}"
ESPEAK_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.zip"
OUTPUT = (
    pathlib.Path(__file__).resolve().parents[2]
    / "core/data/src/commonMain/kotlin/io/github/jdreioe/wingmate/infrastructure/PiperVoiceCatalog.kt"
)


def quoted(url: str) -> str:
    return urllib.parse.quote(url, safe=":/?=&")


def fetch(url: str) -> bytes:
    """GET [url], retrying network errors and server throttling; 404 and other client errors raise."""
    request = urllib.request.Request(quoted(url), headers={"User-Agent": "wingmate-catalog-generator"})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code < 500 and error.code != 429 or attempt == 5:
                raise
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == 5:
                raise
        time.sleep(2 ** attempt)
    raise AssertionError("unreachable")


def sha256_of_url(url: str) -> tuple[int, str]:
    body = fetch(url)
    return len(body), hashlib.sha256(body).hexdigest()


def licence_from(model_card: str) -> str | None:
    """The dataset licence, or its URL when the card only points elsewhere; None when unknown."""
    licence = re.search(r"^\*\s*License:\s*(.+)$", model_card, re.MULTILINE)
    url = re.search(r"^\*\s*URL:\s*(\S+)", model_card, re.MULTILINE)
    value = licence.group(1).strip() if licence else ""
    if value.lower() in ("", "unknown"):
        return None
    if value.lower().startswith("see ") and url:
        return url.group(1).strip()
    return value


def kotlin_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def voice_entry(key: str, voice: dict, piper_revision: str) -> str | None:
    try:
        info = json.loads(fetch(HF_API.format(key=key) + "?blobs=true"))
    except urllib.error.HTTPError:
        print(f"skip {key}: no sherpa-onnx mirror", file=sys.stderr)
        return None
    revision = info["sha"]
    siblings = {s["rfilename"]: s for s in info.get("siblings", [])}
    model = siblings.get(f"{key}.onnx")
    if model is None or "lfs" not in model or "tokens.txt" not in siblings:
        print(f"skip {key}: mirror lacks model or tokens", file=sys.stderr)
        return None
    card = fetch(HF_FILE.format(key=key, revision=revision, path="MODEL_CARD")).decode("utf-8", "replace") \
        if "MODEL_CARD" in siblings else ""
    licence = licence_from(card)
    if not licence:
        print(f"skip {key}: model card names no licence", file=sys.stderr)
        return None
    tokens_size, tokens_sha = sha256_of_url(HF_FILE.format(key=key, revision=revision, path="tokens.txt"))
    language = voice["language"]
    voice_dir = next(path for path in voice["files"] if path.endswith(".onnx")).rsplit("/", 1)[0]
    sample_url = PIPER_FILE.format(revision=piper_revision, path=f"{voice_dir}/samples/speaker_0.mp3")
    files = [
        (f"{key}.onnx", HF_FILE.format(key=key, revision=revision, path=f"{key}.onnx"),
         model["lfs"]["size"], model["lfs"]["sha256"]),
        ("tokens.txt", HF_FILE.format(key=key, revision=revision, path="tokens.txt"), tokens_size, tokens_sha),
    ]
    file_lines = ",\n".join(
        f"            PiperFile({kotlin_string(name)}, {kotlin_string(quoted(url))}, {size}L, {kotlin_string(sha)})"
        for name, url, size, sha in files
    )
    return (
        "    PiperVoicePack(\n"
        f"        key = {kotlin_string(key)},\n"
        f"        languageTag = {kotlin_string(language['code'].replace('_', '-'))},\n"
        f"        speaker = {kotlin_string(voice['name'])},\n"
        f"        quality = {kotlin_string(voice['quality'])},\n"
        f"        speakerCount = {voice['num_speakers']},\n"
        f"        licence = {kotlin_string(licence)},\n"
        f"        sampleUrl = {kotlin_string(quoted(sample_url))},\n"
        "        files = listOf(\n"
        f"{file_lines},\n"
        "        ),\n"
        "    )"
    )


def main() -> None:
    piper_revision = json.loads(fetch(PIPER_API))["sha"]
    voices = json.loads(fetch(PIPER_FILE.format(revision=piper_revision, path="voices.json")))
    entries = [entry for key in sorted(voices) if (entry := voice_entry(key, voices[key], piper_revision))]
    espeak_size, espeak_sha = sha256_of_url(ESPEAK_URL)
    OUTPUT.write_text(
        "// Generated by scripts/piper/generate_catalog.py. Do not edit by hand.\n"
        "package io.github.jdreioe.wingmate.infrastructure\n\n"
        "import io.github.jdreioe.wingmate.domain.PiperFile\n"
        "import io.github.jdreioe.wingmate.domain.PiperVoicePack\n\n"
        "/** espeak-ng phonemizer data shared by every Piper voice. */\n"
        "internal val PiperPhonemizerData = PiperFile(\n"
        f"    \"espeak-ng-data.zip\",\n    {kotlin_string(ESPEAK_URL)},\n    {espeak_size}L,\n    {kotlin_string(espeak_sha)},\n"
        ")\n\n"
        "/** Piper voices Wingmate can download, pinned by size and SHA-256. */\n"
        "internal val PiperVoiceCatalog: List<PiperVoicePack> = listOf(\n"
        + ",\n".join(entries)
        + ",\n)\n",
        encoding="utf-8",
    )
    print(f"wrote {len(entries)} of {len(voices)} voices to {OUTPUT}", file=sys.stderr)


if __name__ == "__main__":
    main()

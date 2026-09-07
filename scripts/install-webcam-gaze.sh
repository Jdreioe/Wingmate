#!/usr/bin/env bash
# Optional local runtime. Never starts a camera or installs into system Python.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ $(uname -s) != Linux || $(uname -m) != x86_64 ]]; then
    echo 'Webcam gaze currently requires Linux x86_64.' >&2
    exit 1
fi
runtime="${XDG_DATA_HOME:-$HOME/.local/share}/wingmate/webcam"
python="${WINGMATE_WEBCAM_PYTHON:-python3.12}"
if ! command -v "$python" >/dev/null; then
    echo 'Install Python 3.10–3.12 with venv support, or set WINGMATE_WEBCAM_PYTHON to that interpreter.' >&2
    exit 1
fi
"$python" -c 'import sys; assert (3,10) <= sys.version_info[:2] <= (3,12), "Python 3.10–3.12 required"'
mkdir -p "$runtime"
"$python" -m venv "$runtime/venv"
"$runtime/venv/bin/python" -m pip install -r "$root/desktopApp/webcam/requirements.txt"
"$runtime/venv/bin/python" - "$runtime" <<'PY'
import hashlib
from pathlib import Path
import sys
import urllib.request
runtime = Path(sys.argv[1])
data = urllib.request.urlopen(
    "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task",
    timeout=60,
).read()
if hashlib.sha256(data).hexdigest() != "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff":
    raise SystemExit("Face Landmarker checksum mismatch; model was not installed")
(runtime / "face_landmarker.task").write_bytes(data)
# Verify imports and the model without opening a camera or downloading anything.
from eyetrax import GazeEstimator
estimator = GazeEstimator(face_landmarker_model=runtime / "face_landmarker.task")
estimator.close()
PY
echo 'Webcam runtime installed. In Wingmate, open a Screen, then Settings > Access, select your camera and enable gaze.'

"""Private Wingmate child protocol. Frames, features and models stay in memory."""
import json
import math
import os
import sys
import queue
import threading
import time

CALIBRATION = [(x, y) for y in (0.1, 0.5, 0.9) for x in (0.1, 0.5, 0.9)]
VALIDATION = [(0.25, 0.25), (0.75, 0.75), (0.75, 0.25), (0.25, 0.75)]


def emit(kind, **values):
    print(json.dumps(dict(kind=kind, **values), allow_nan=False), flush=True)


def valid_point(point):
    return len(point) == 2 and all(math.isfinite(v) and 0 <= v <= 1 for v in point)


def validation_passes(points, target):
    # Provisional large-target gate, not a claim of AAC selection accuracy.
    return len(points) >= 15 and sum(
        valid_point(p) and max(abs(p[i] - target[i]) for i in (0, 1)) <= 0.12
        for p in points
    ) / len(points) >= 0.9


def run(camera, model):
    # Passing an existing model explicitly prevents EyeTrax's automatic download.
    if not os.path.isfile(model):
        emit("runtime_missing")
        return
    try:
        import cv2
        import numpy as np
        from eyetrax import GazeEstimator
        estimator = GazeEstimator(face_landmarker_model=model)
    except Exception:
        emit("runtime_missing")
        return
    cap = cv2.VideoCapture(camera, cv2.CAP_V4L2)
    try:
        if not cap.isOpened():
            emit("camera_unavailable")
            return
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

        frames = queue.Queue(maxsize=1)
        stopped = threading.Event()

        def capture():
            try:
                while not stopped.is_set():
                    ok, frame = cap.read()
                    captured = time.monotonic()
                    try:
                        frames.get_nowait()
                    except queue.Empty:
                        pass
                    frames.put((frame if ok else None, captured))
                    if not ok:
                        return
            finally:
                cap.release()

        # Drain the camera continuously, including while waiting at the result
        # screen. Only the newest frame is retained; no video is recorded.
        capture_thread = threading.Thread(target=capture, daemon=True)
        capture_thread.start()

        def feature():
            try:
                frame, captured = frames.get(timeout=2)
            except queue.Empty:
                raise OSError("camera unavailable") from None
            if frame is None:
                raise OSError("camera unavailable")
            if time.monotonic() - captured > 0.1:
                return None, captured
            values, blink = estimator.extract_features(frame)
            if blink or values is None or not np.isfinite(values).all():
                return None, captured
            return values, captured

        training, labels = [], []
        for index, target in enumerate(CALIBRATION + VALIDATION):
            validation = index >= len(CALIBRATION)
            emit("target", index=index + 1, x=target[0], y=target[1], validation=validation)
            # The UI acknowledges constructing this target before capture begins.
            if sys.stdin.readline().strip() != "ready":
                return
            start = time.monotonic()
            collected = []
            usable_seconds = 0.0
            previous_usable = None
            # Blinks pause collection. Bound the wait so prolonged tracking loss
            # still fails instead of leaving the Communicator at a stuck target.
            while time.monotonic() - start < 12:
                values, captured = feature()
                if captured - start < 2:
                    continue  # Give the Communicator time to find the new target.
                if values is None or time.monotonic() - captured > 0.1:
                    previous_usable = None
                    continue
                if previous_usable is not None:
                    usable_seconds += min(captured - previous_usable, 0.1)
                previous_usable = captured
                collected.append(values)
                if len(collected) >= 15 and usable_seconds >= 2:
                    break
            else:
                emit("calibration_failed", reason="tracking")
                return
            if validation:
                points = estimator.predict(np.array(collected)).tolist()
                if not validation_passes(points, target):
                    emit("calibration_failed", reason="accuracy")
                    return
            else:
                training.extend(collected)
                labels.extend([target] * len(collected))
                if index == len(CALIBRATION) - 1:
                    estimator.train(np.array(training), np.array(labels))
                    training.clear()
                    labels.clear()
        emit("validated")
        # Let the user explicitly start selection after inspecting the result.
        if sys.stdin.readline().strip() != "ready":
            return
        while True:
            values, captured = feature()
            point = estimator.predict([values])[0].tolist() if values is not None else None
            age_ms = (time.monotonic() - captured) * 1000
            if point is not None and (not valid_point(point) or age_ms > 100):
                point = None
            emit("sample", point=point, age_ms=age_ms)
            # Backpressure prevents queued estimates from becoming fresh input.
            if sys.stdin.readline().strip() != "next":
                return
    except OSError:
        emit("camera_unavailable")
    except Exception:
        emit("calibration_failed", reason="estimator")
    finally:
        if "capture_thread" in locals():
            stopped.set()
            capture_thread.join(timeout=2)
        else:
            cap.release()
        estimator.close()


if __name__ == "__main__":
    # No traceback, library diagnostics, or camera data is written to logs.
    run(sys.argv[1], sys.argv[2])

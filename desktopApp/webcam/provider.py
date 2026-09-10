"""Private Wingmate child protocol. Frames, features and models stay in memory."""
from copy import deepcopy
import json
import math
import os
import sys
import queue
import threading
import time
import select

CALIBRATION = [(x, y) for y in (0.1, 0.5, 0.9) for x in (0.1, 0.5, 0.9)]
VALIDATION = [(0.25, 0.25), (0.75, 0.75), (0.75, 0.25), (0.25, 0.75)]


def emit(kind, **values):
    print(json.dumps(dict(kind=kind, **values), allow_nan=False), flush=True)


def valid_point(point):
    return len(point) == 2 and all(math.isfinite(v) and 0 <= v <= 1 for v in point)


def matches_target(point, target):
    return valid_point(point) and max(abs(point[i] - target[i]) for i in (0, 1)) <= 0.12


def validation_passes(points, target):
    # Provisional large-target gate, not a claim of AAC selection accuracy.
    return len(points) >= 15 and sum(
        matches_target(p, target) for p in points
    ) / len(points) >= 0.9


def validation_summary(points, target, index):
    """A current on-screen result only; never record calibration samples."""
    finite = [p for p in points if len(p) == 2 and all(math.isfinite(v) for v in p)]
    mean = [sum(p[i] for p in finite) / len(finite) for i in (0, 1)] if finite else None
    return dict(
        target=index,
        matched=sum(matches_target(p, target) for p in points),
        total=len(points),
        offset=[mean[i] - target[i] for i in (0, 1)] if mean else None,
        spread=[math.sqrt(sum((p[i] - mean[i]) ** 2 for p in finite) / len(finite)) for i in (0, 1)] if mean else None,
    )


def retry_improves(candidate, previous):
    """Compare models on identical fresh holdout samples, never stored scores."""
    gained = False
    candidate_error = previous_error = 0.0
    for new, old, target in zip(candidate, previous, VALIDATION):
        new_hits = sum(matches_target(p, target) for p in new)
        old_hits = sum(matches_target(p, target) for p in old)
        if new_hits < old_hits:
            return False
        gained |= new_hits > old_hits
        for points, is_new in [(new, True), (old, False)]:
            error = sum(
                sum((p[i] - target[i]) ** 2 for i in (0, 1))
                if len(p) == 2 and all(math.isfinite(v) for v in p) else math.inf
                for p in points
            ) / len(points)
            if is_new:
                candidate_error += error
            else:
                previous_error += error
    return gained or candidate_error < previous_error


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

        # Let the Communicator establish tracking before timed targets begin.
        while True:
            values, captured = feature()
            visible = values is not None and time.monotonic() - captured <= 0.1
            emit("positioning", feedback="eyes_visible" if visible else "eyes_missing")
            if select.select([sys.stdin], [], [], 0.1)[0]:
                command = sys.stdin.readline().strip().split()
                if len(command) != 2 or command[0] != "start" or command[1] not in ("1000", "2000", "4000"):
                    return
                settle_seconds = int(command[1]) / 1000
                break

        # Keep one batch per learning point, replacing only the selected area's
        # corners. This stays bounded at nine batches even after repeated retries.
        samples = {}
        learning_targets = CALIBRATION
        previous_model = previous_samples = None
        while True:
            validation_results = []
            candidate_checks, previous_checks = [], []
            # Learning IDs end at nine; validation always uses IDs 10–13.
            for index, target in enumerate(learning_targets + VALIDATION, start=10 - len(learning_targets)):
                validation = index >= 10
                emit("target", index=index, x=target[0], y=target[1], validation=validation)
                # The UI acknowledges constructing this target before capture begins.
                if sys.stdin.readline().strip() != "ready":
                    return
                start = time.monotonic()
                collected = []
                usable_seconds = 0.0
                previous_usable = None
                feedback_at = -math.inf
                # Blinks pause collection. Bound the wait so prolonged tracking loss
                # still fails instead of leaving the Communicator at a stuck target.
                while time.monotonic() - start < settle_seconds + 10:
                    values, captured = feature()
                    if time.monotonic() - feedback_at >= 0.1:
                        feedback = "eyes_missing"
                        if values is not None and time.monotonic() - captured <= 0.1:
                            feedback = "eyes_visible"
                            if validation:
                                prediction = estimator.predict(np.array([values])).tolist()[0]
                                feedback = "on_target" if matches_target(prediction, target) else "off_target"
                            if time.monotonic() - captured > 0.1:
                                feedback = "eyes_missing"
                        emit("target_feedback", index=index, feedback=feedback)
                        feedback_at = time.monotonic()
                    if captured - start < settle_seconds:
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
                    candidate_checks.append(points)
                    if previous_model is not None:
                        previous_checks.append(previous_model.predict(np.array(collected)).tolist())
                    validation_results.append(validation_summary(points, target, index))
                else:
                    samples[target] = collected
                    if index == 9:
                        training = [value for point in CALIBRATION for value in samples[point]]
                        labels = [point for point in CALIBRATION for _ in samples[point]]
                        estimator.train(np.array(training), np.array(labels))
                        del training, labels
            kept_previous = previous_model is not None and not retry_improves(candidate_checks, previous_checks)
            if kept_previous:
                estimator.model = previous_model
                samples = previous_samples
                validation_results = [validation_summary(points, target, index)
                    for index, (points, target) in enumerate(zip(previous_checks, VALIDATION), start=10)]
            previous_model = previous_samples = None
            candidate_checks.clear()
            previous_checks.clear()
            emit("calibration_complete", validation=validation_results, kept_previous=kept_previous)
            # Keep the process and learned samples alive on the result screen,
            # including failed checks. No gaze samples are emitted here.
            command = sys.stdin.readline().strip().split()
            if command == ["ready"]:
                if all(r['total'] >= 15 and r['matched'] / r['total'] >= 0.9 for r in validation_results):
                    break
                return
            if len(command) != 2 or command[0] != "improve" or command[1] not in ("10", "11", "12", "13"):
                return
            previous_model = deepcopy(estimator.model)
            previous_samples = samples.copy()
            x, y = VALIDATION[int(command[1]) - 10]
            xs = (0.1, 0.5) if x < 0.5 else (0.5, 0.9)
            ys = (0.1, 0.5) if y < 0.5 else (0.5, 0.9)
            learning_targets = [(px, py) for py in ys for px in xs]
        samples.clear()
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

# Webcam gaze on Linux desktop

Issue #276 adds an experimental camera source beside Tobii in the existing
native gaze runner. Linux x86_64 is the first implementation platform. Android,
iOS, macOS and Windows retain their existing OS input paths.

## Setup and use

Install Python 3.10–3.12 with venv support and Git, then run from the repository:

```sh
bash scripts/install-webcam-gaze.sh
# If Python 3.12 is not the installed compatible interpreter:
WINGMATE_WEBCAM_PYTHON=python3.11 bash scripts/install-webcam-gaze.sh
```

The installer downloads the pinned EyeTrax runtime and a checksum-verified
Face Landmarker model into `$XDG_DATA_HOME/wingmate/webcam`, or
`~/.local/share/wingmate/webcam`. It does not open a camera. The runtime is
optional and is not bundled in the AppImage. Once installed, camera tracking
needs no network connection. Setup requires several hundred MB of disk space.

1. Connect the webcam and use a single display. Sit in an evenly lit position
   where both eyes are visible. Keep the camera and display fixed.
2. Open a Screen with a small grid of large targets. In Settings > Access, set
   a dwell duration, enable **Use webcam eye tracking**, and choose the camera.
3. Enable gaze. Wingmate enters fullscreen, then starts camera capture.
4. On the positioning screen, wait for **Eyes detected**. Adjust the target
   size and time to find each target (Slow: four seconds, Normal: two, Fast:
   one). Enable **Step through** if a Supporter should press Enter or choose
   **Collect this point** once the Communicator is looking at each target.
   Choose **Start calibration** or press Enter when tracking is available.
5. Look at the center of each circle. Nine learning targets are followed by
   four independent accuracy checks. Collection requires two seconds of usable
   tracking and at least 15 samples. Blink normally; collection pauses during
   blinks, with up to ten seconds of collection time per target. Automatic
   progression is the default and requires no clicks on targets.
6. Inspect the four-point result map. Each point has a percentage and a text
   label as well as a color. Select a point for its average offset and spread.
   All four checks finish even if one has low accuracy.
7. If all checks pass, press Enter or choose **Start camera gaze**. Selection
   uses the existing target highlight, dwell feedback, Rest mode and shared
   communication actions. Esc or **Stop gaze** stops capture.

A failed accuracy check keeps gaze selection off. Select a weak area on the
results map and choose **Improve this area (4 points)**. Wingmate recollects the
four learning points surrounding that area, keeping the other five points.
For example, the top-left area uses the square with corners at 10% and 50% of
screen width and height. Each new batch replaces the old samples at that corner;
repeated improvements do not accumulate training batches. The pace, target size,
and step-through settings still apply.

After those four points, the estimator retrains and all four independent
accuracy checks run again. Both the previous and candidate models predict the
same fresh check samples. The candidate is accepted only if no area's matched
sample count decreases and either a count increases or the total mean squared
error falls. Otherwise Wingmate restores both the previous model and its
learning samples, shows **Previous calibration kept**, and displays that model's
results on the fresh checks. It does not reuse old passing scores. This prevents
accepting a worse retry on those observations; it does not guarantee that later
tracking will remain stable.

Selection stays off until every check passes and
**Start camera gaze** is chosen. You can improve another area from the new
results. Keep your position and camera fixed: if either moves, use **Retry full
calibration** to return to positioning and repeat all nine learning targets and
four checks. Pace, size, and step-through choices survive a full retry;
previous results and training data do not. Learning samples are held in memory
only during calibration and discarded when gaze selection starts or the owned
camera process closes. After a tracking or estimator failure, choose **Retry
camera calibration**. **Cancel** or Esc leaves calibration.

The positioning, pacing, step-through, and result-map interaction draws on the
[Tobii Dynavox calibration guide](https://www.brianpen.com/Journal/docs/TobiiDynavox_EyeGazeCalibration_I-16.pdf)
and [Grid 3 tutorial](https://setbccdnstorage.blob.core.windows.net/data/Grid_3/Grid3_EyeGazeCalibration_Tutorial_2024.pdf).
The webcam currently reports eye detection, not measured eye position or distance.

Missing runtime, blocked camera
access, a busy/disconnected camera and failed calibration have separate status
messages. Calibration failures distinguish insufficient usable tracking,
validation accuracy rejection, and estimator errors. No exception details or
eye features are shown. Linux V4L2 access is subject to the session's device permissions;
Wingmate does not change permissions or request elevated privileges. Some
cameras expose additional metadata nodes in the selector; if one cannot open,
select the other node for that camera. The list refreshes without opening devices.

Camera selection and the webcam toggle last for the current app session only.
Leaving communication, opening settings, losing focus, or closing Wingmate
stops the owned camera process. Each new session recalibrates. A fullscreen size
change also stops tracking. Moving a camera or changing displays without a size
change cannot be detected reliably; stop and recalibrate manually. Multiple
simultaneous displays are outside this implementation's evaluated scope.

## Implementation choice

Reviewed on 2026-09-06:

| Candidate | Fit and terms | Decision |
| --- | --- | --- |
| [EyeTrax](https://github.com/ck-zhang/EyeTrax) | MIT; Python, OpenCV, MediaPipe, NumPy and scikit-learn; extracts eye features and trains a local screen-coordinate estimator | Use commit `84e13a16af168ac7c383f7d50ec901cd6c0ad61d`, with Wingmate's own calibration UI |
| [GazeTracking](https://github.com/antoinelame/GazeTracking) | MIT code; Python/dlib; pupil positions and directional ratios | Would require a separate screen-coordinate estimator and calibration integration; not selected |
| [MediaPipe Face Landmarker](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker) alone | On-device landmarks, not calibrated screen gaze | Used through EyeTrax rather than implementing a new gaze estimator |

EyeTrax is a small upstream project with 73 commits at inspection, not an AAC
support guarantee. Pin updates need an API, model and hardware recheck. Its
MIT license permits redistribution with the copyright and license notice.
MediaPipe is Apache-2.0; Google's published model cards identify Apache-2.0
terms for [FaceMesh V2](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Face%20Mesh%20V2.pdf),
[BlazeFace](https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20%28Short%20Range%29.pdf)
and [Blendshape V2](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Blendshape%20V2.pdf).
Retain the applicable notices when redistributing these dependencies or models.
This change downloads the model during explicit installation and does not add
model weights to the repository or AppImage.

The selected model is Google's `face_landmarker/float16/1` task, SHA-256
`64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`.
An existing explicit model path prevents EyeTrax's automatic model download.
Inference uses the CPU. Capture requests 640×480 from a generic V4L2 webcam;
the camera may negotiate a different resolution.

## Selection and privacy

The child retains only the latest captured frame. Calibration, learned model,
eye features and gaze samples stay in memory. Neither camera frames nor gaze
histories are saved, logged, backed up or transmitted. Child stderr is discarded.
A private stdin/stdout protocol carries setup state and normalized points to
Rust. Raw points never enter the shared Kotlin controller; the existing native
hit test sends semantic target transitions through the shared dwell policy.

Calibration cannot activate controls. A missing face, blink, invalid/off-display
estimate, or estimate older than 100 ms yields no target. Loss cancels pending
dwell, including a loss between UI polls. Reacquisition requires fresh dwell.
The provider does not smooth an old point through a loss. Rest mode keeps the
normal shared pause behavior; it does not stop capture while the Screen remains
active. Explicit stop or loss of focus closes capture and discards calibration.

## Validation status

Automated checks exercise coordinate bounds, stale estimates, calibration
coverage and rejection, camera-open failure, runtime failure, child cancellation,
focus/display changes, pointer fallback and the existing shared gaze selection
path. The pinned runtime was installed in an isolated temporary directory;
model loading, blank-image face loss, and estimator training/prediction were
checked without opening a camera.

The in-app validation gate requires at least 15 usable samples and two seconds
of usable tracking at every target. Blinks and temporarily lost eyes pause
collection without discarding valid samples. Tracking that cannot supply enough
usable input within ten seconds after settling still fails calibration. At each
validation target, at least 90% of predictions must be within 0.12 of each normalized display axis. These are provisional
large-target screening limits, not measured AAC selection accuracy. The model
does not expose a calibrated per-frame confidence score; passing calibration
does not establish reliability under subsequent head movement or lighting changes.

Real-device evaluation is **pending**. Jonas's Sandberg Webcam Pro, a generic
1080p webcam, is the first intended camera. No dense-grid or keyboard support
is claimed, and no supported AAC accuracy threshold has been established.

Before declaring support, evaluate a 2×2 communication grid using insertion,
speech and Page navigation, including Rest mode and recovery after face loss.
Record at least 40 prompted selections per lighting/position condition, with
repeated calibration and a drift check after 10 minutes. Keep only manually
recorded aggregate results, with no video or gaze histories:

| Measurement | Sandberg Webcam Pro result |
| --- | --- |
| Computer/CPU, display, OS, actual capture resolution/rate | Pending |
| Correct selection rate and accidental activations | Pending |
| Median and 95th-percentile selection latency | Pending |
| Calibration time and retry rate | Pending |
| Drift, representative lighting and comfortable head movement | Pending |

Use these results to set supported target sizes and acceptance thresholds.
The camera provider remains experimental until that evaluation is complete.

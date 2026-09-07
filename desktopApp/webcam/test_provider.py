import contextlib
import io
import json
import unittest
from unittest.mock import patch, Mock

import provider
from provider import run, valid_point, validation_passes


class CalibrationTests(unittest.TestCase):
    def test_validation_requires_coverage_and_rejects_wrong_target(self):
        target = (0.25, 0.25)
        self.assertFalse(validation_passes([target] * 14, target))
        self.assertTrue(validation_passes([target] * 18 + [(0.75, 0.75)] * 2, target))
        self.assertFalse(validation_passes([target] * 17 + [(0.75, 0.75)] * 3, target))
        self.assertFalse(validation_passes([(float('nan'), 0.25)] * 20, target))

    def calibration_targets(self, blinking, targets=1, accurate=True, estimator_error=False):
        clock = Mock()
        clock.now = 0.0
        clock.target = 0
        clock.target_started = 0.0
        clock.monotonic.side_effect = lambda: clock.now
        frames = Mock()

        def next_frame(**_):
            clock.now += 1 / 30
            return object(), clock.now

        frames.get.side_effect = next_frame
        cv2, estimator, numpy = Mock(), Mock(), Mock()
        cv2.VideoCapture.return_value.isOpened.return_value = True
        estimator.extract_features.side_effect = lambda _: (
            [0.1, 0.2], blinking(clock.target, clock.now - clock.target_started)
        )
        if estimator_error:
            estimator.train.side_effect = ValueError("private diagnostic detail")
        numpy.isfinite.return_value.all.return_value = True
        estimator.predict.side_effect = lambda _: Mock(
            tolist=lambda: [provider.VALIDATION[clock.target - 10] if accurate else (1.0, 1.0)] * 60
        )
        original_emit = provider.emit

        def emit(kind, **values):
            if kind == 'target':
                clock.target = values['index']
                clock.target_started = clock.now
            original_emit(kind, **values)

        eyetrax = Mock()
        eyetrax.GazeEstimator.return_value = estimator
        output = io.StringIO()
        with patch('os.path.isfile', return_value=True), patch.dict('sys.modules', {
            'cv2': cv2, 'numpy': numpy, 'eyetrax': eyetrax,
        }), patch('provider.time', clock), patch('provider.queue.Queue', return_value=frames), \
                patch('provider.threading.Thread'), patch('provider.emit', side_effect=emit), \
                patch('sys.stdin', io.StringIO('ready\n' * targets)), \
                contextlib.redirect_stdout(output):
            run('/dev/unused', '/model')
        events = [json.loads(line) for line in output.getvalue().splitlines()]
        return events, clock.now

    def test_blink_during_calibration_waits_for_usable_samples(self):
        # Two ordinary 250 ms blinks during the initial collection window.
        events, elapsed = self.calibration_targets(
            lambda _target, now: 2.4 <= now < 2.65 or 3.2 <= now < 3.45
        )
        self.assertNotIn('calibration_failed', [event['kind'] for event in events])
        self.assertEqual(events[-1]['index'], 2)
        self.assertGreater(elapsed, 4.4)

    def test_uninterrupted_calibration_advances_after_two_usable_seconds(self):
        events, elapsed = self.calibration_targets(lambda _target, _now: False)
        self.assertEqual(events[-1]['index'], 2)
        self.assertGreaterEqual(elapsed, 4)
        self.assertLess(elapsed, 4.2)

    def test_prolonged_eye_loss_still_times_out(self):
        events, elapsed = self.calibration_targets(lambda _target, _now: True)
        self.assertEqual(events[-1]['kind'], 'calibration_failed')
        self.assertEqual(events[-1].get('reason'), 'tracking')
        self.assertGreaterEqual(elapsed, 12)
        self.assertLess(elapsed, 12.1)

    def test_blinks_at_tenth_target_do_not_fail_validation(self):
        events, _ = self.calibration_targets(
            lambda target, now: target == 10 and (2.4 <= now < 2.65 or 3.2 <= now < 3.45),
            targets=10,
        )
        self.assertEqual(events[-1]['index'], 11)
        self.assertNotIn('calibration_failed', [event['kind'] for event in events])

    def test_inaccurate_validation_still_fails_after_blinks(self):
        events, _ = self.calibration_targets(
            lambda target, now: target == 10 and (2.4 <= now < 2.65 or 3.2 <= now < 3.45),
            targets=10, accurate=False,
        )
        self.assertEqual(events[-2]['index'], 10)
        self.assertEqual(events[-1]['kind'], 'calibration_failed')
        self.assertEqual(events[-1].get('reason'), 'accuracy')

    def test_estimator_exception_reports_reason_without_exception_details(self):
        events, _ = self.calibration_targets(
            lambda _target, _now: False, targets=10, estimator_error=True,
        )
        self.assertEqual(events[-1], {'kind': 'calibration_failed', 'reason': 'estimator'})
        self.assertNotIn('private diagnostic detail', json.dumps(events))

    def test_invalid_estimates_are_never_clamped_onto_controls(self):
        for point in [(float('inf'), 0.5), (float('nan'), 0), (-0.01, 0.5), (0.5, 1.01)]:
            self.assertFalse(valid_point(point))

    def test_missing_runtime_reports_no_paths_or_exception_content(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            run('/dev/private-camera', '/missing/private/model')
        self.assertEqual(output.getvalue(), '{"kind": "runtime_missing"}\n')

    def test_busy_camera_is_recoverable_and_resources_are_closed(self):
        cv2, estimator = Mock(), Mock()
        cv2.VideoCapture.return_value.isOpened.return_value = False
        eyetrax = Mock()
        eyetrax.GazeEstimator.return_value = estimator
        output = io.StringIO()
        with patch('os.path.isfile', return_value=True), patch.dict('sys.modules', {
            'cv2': cv2, 'numpy': Mock(), 'eyetrax': eyetrax,
        }), contextlib.redirect_stdout(output):
            run('/dev/video0', '/model')
        self.assertEqual(output.getvalue(), '{"kind": "camera_unavailable"}\n')
        cv2.VideoCapture.return_value.release.assert_called_once()
        estimator.close.assert_called_once()


if __name__ == '__main__':
    unittest.main()

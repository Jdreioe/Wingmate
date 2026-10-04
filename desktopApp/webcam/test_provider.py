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

    def calibration_targets(self, blinking, targets=1, accurate=True, estimator_error=False, commands=None, training_runs=None, offsets=None):
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
            [clock.target, clock.now], blinking(clock.target, clock.now - clock.target_started)
        )
        class PredictionModel:
            generation = 0

            def predict(self, _):
                target = provider.VALIDATION[clock.target - 10]
                offset = offsets[self.generation - 1] if offsets else (0 if accurate else 0.5 / self.generation)
                return Mock(tolist=lambda: [(target[0] + offset, target[1])] * 60)

        estimator.model = PredictionModel()
        generation = 0

        def train(values, labels):
            nonlocal generation
            generation += 1
            estimator.model.generation = generation
            if training_runs is not None:
                training_runs.append(list(zip(labels, values)))

        estimator.train.side_effect = ValueError("private diagnostic detail") if estimator_error else train
        numpy.array.side_effect = lambda values: values
        numpy.isfinite.return_value.all.return_value = True
        estimator.predict.side_effect = lambda values: estimator.model.predict(values)
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
                patch('sys.stdin', io.StringIO(commands if commands is not None else 'start 2000\n' + 'ready\n' * targets)), \
                patch('provider.select.select', return_value=([True], [], [])), \
                contextlib.redirect_stdout(output):
            run('/dev/unused', '/model')
        events = [json.loads(line) for line in output.getvalue().splitlines()]
        return events, clock.now

    def test_area_improvement_replaces_four_corners_and_rechecks_every_area(self):
        for area, corners in [(10, {(0.1, 0.1), (0.5, 0.1), (0.1, 0.5), (0.5, 0.5)}),
                              (11, {(0.5, 0.5), (0.9, 0.5), (0.5, 0.9), (0.9, 0.9)}),
                              (12, {(0.5, 0.1), (0.9, 0.1), (0.5, 0.5), (0.9, 0.5)}),
                              (13, {(0.1, 0.5), (0.5, 0.5), (0.1, 0.9), (0.5, 0.9)})]:
            with self.subTest(area=area):
                training = []
                # Retry the same area twice: old samples must be replaced, not accumulated.
                commands = 'start 2000\n' + 'ready\n' * 13 + (f'improve {area}\n' + 'ready\n' * 8) * 2
                events, _ = self.calibration_targets(lambda *_: False, accurate=False,
                    commands=commands, training_runs=training)
                targets = [e for e in events if e['kind'] == 'target']
                self.assertEqual(len(targets), 29)
                self.assertEqual({(e['x'], e['y']) for e in targets[13:17]}, corners)
                self.assertEqual([e['index'] for e in targets[17:21]], [10, 11, 12, 13])
                self.assertEqual(len([e for e in events if e['kind'] == 'calibration_complete']), 3)
                self.assertEqual(len(training), 3)
                for previous, updated in zip(training, training[1:]):
                    self.assertEqual({label for label, _ in updated}, set(provider.CALIBRATION))
                    for point in provider.CALIBRATION:
                        old = [v for label, v in previous if label == point]
                        new = [v for label, v in updated if label == point]
                        if point in corners:
                            self.assertGreater(min(v[1] for v in new), max(v[1] for v in old))
                        else:
                            self.assertEqual(old, new)
                self.assertNotIn('sample', [e['kind'] for e in events])

    def test_worse_retry_restores_samples_and_reports_fresh_previous_results(self):
        training = []
        commands = 'start 2000\n' + 'ready\n' * 13 + ('improve 10\n' + 'ready\n' * 8) * 2
        events, _ = self.calibration_targets(lambda *_: False, commands=commands,
            training_runs=training, offsets=[0.15, 0.4, 0.05])
        results = [e for e in events if e['kind'] == 'calibration_complete']
        self.assertEqual([e['kept_previous'] for e in results], [False, True, False])
        self.assertAlmostEqual(results[1]['validation'][0]['offset'][0], 0.15)
        self.assertTrue(all(r['matched'] == r['total'] for r in results[2]['validation']))
        # A later retry in another area must start from the restored training data.
        commands = 'start 2000\n' + 'ready\n' * 13 + 'improve 10\n' + 'ready\n' * 8 + 'improve 11\n' + 'ready\n' * 8
        training = []
        self.calibration_targets(lambda *_: False, commands=commands,
            training_runs=training, offsets=[0.15, 0.4, 0.05])
        top_left = lambda batch: [v for point, v in batch if point == (0.1, 0.1)]
        self.assertEqual(top_left(training[2]), top_left(training[0]))
        self.assertNotEqual(top_left(training[1]), top_left(training[0]))

    def test_retry_cannot_trade_a_passing_area_for_another(self):
        old = [[target] * 20 for target in provider.VALIDATION]
        old[0] = [(1.0, 1.0)] * 20
        new = [[target] * 20 for target in provider.VALIDATION]
        new[1] = [(0.0, 0.0)] * 20
        self.assertFalse(provider.retry_improves(new, old))
        self.assertFalse(provider.retry_improves(old, old))
        new[1] = [provider.VALIDATION[1]] * 20
        self.assertTrue(provider.retry_improves(new, old))

    def test_blink_during_calibration_waits_for_usable_samples(self):
        # Two ordinary 250 ms blinks during the initial collection window.
        events, elapsed = self.calibration_targets(
            lambda _target, now: 2.4 <= now < 2.65 or 3.2 <= now < 3.45
        )
        self.assertNotIn('calibration_failed', [event['kind'] for event in events])
        self.assertEqual(events[-1]['index'], 2)
        self.assertGreater(elapsed, 4.4)

    def test_calibration_feedback_only_claims_eye_detection(self):
        events, _ = self.calibration_targets(lambda _target, now: 2.4 <= now < 2.65)
        feedback = [e['feedback'] for e in events if e['kind'] == 'target_feedback']
        self.assertIn('eyes_visible', feedback)
        self.assertIn('eyes_missing', feedback)
        self.assertNotIn('on_target', feedback)
        self.assertNotIn('off_target', feedback)

    def test_validation_feedback_matches_the_accuracy_gate(self):
        for accurate, expected in [(True, 'on_target'), (False, 'off_target')]:
            events, _ = self.calibration_targets(lambda _target, _now: False, targets=10, accurate=accurate)
            feedback = [e['feedback'] for e in events if e['kind'] == 'target_feedback' and e['index'] == 10]
            self.assertTrue(feedback)
            self.assertEqual(set(feedback), {expected})

    def test_validation_summary_distinguishes_offset_from_scatter(self):
        summary = provider.validation_summary([(0.5, 0.25)] * 20, (0.25, 0.25), 10)
        self.assertEqual(summary['matched'], 0)
        self.assertEqual(summary['offset'], [0.25, 0.0])
        self.assertEqual(summary['spread'], [0.0, 0.0])
        self.assertNotIn('points', summary)
        summary = provider.validation_summary([(0.0, 0.25), (0.5, 0.25)] * 10, (0.25, 0.25), 10)
        self.assertEqual(summary['offset'], [0.0, 0.0])
        self.assertEqual(summary['spread'], [0.25, 0.0])

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
            targets=13, accurate=False,
        )
        self.assertEqual(events[-1]['kind'], 'calibration_complete')
        results = events[-1]['validation']
        self.assertEqual([r['target'] for r in results], [10, 11, 12, 13])
        self.assertTrue(all(r['matched'] == 0 for r in results))

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

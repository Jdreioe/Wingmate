//! Pure geometry + filtering helpers for the head/eye tracking pipeline.
//!
//! These are intentionally dependency-free and testable without MediaPipe, a
//! camera, or any hardware. They operate exclusively on an abstract `Point` in
//! normalized (0.0..=1.0) screen space, so the same code serves the webcam
//! webcam path and the TD-I13 / external gaze stream path.

/// A 2-D point in normalized screen space (`0.0..=1.0`), y-down.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Point {
    pub x: f32,
    pub y: f32,
}

impl Point {
    pub const ZERO: Self = Self { x: 0.0, y: 0.0 };
}

/// First-order (exponential) low-pass filter for jitter reduction.
///
/// `alpha` in `(0.0, 1.0]`; lower smooths more but lags more. Mirrors a
/// simple moving average while being O(1) and easy to tune per axis.
#[derive(Debug, Clone, Copy)]
pub struct OneEuro {
    alpha: f32,
    value: Option<Point>,
}

impl OneEuro {
    /// `alpha` must be in `(0.0, 1.0]`.
    pub fn new(alpha: f32) -> Self {
        let alpha = alpha.clamp(0.001, 1.0);
        Self { alpha, value: None }
    }

    /// Push a raw sample, returning the smoothed value. Clamped to 0..1.
    pub fn push(&mut self, input: Point) -> Point {
        match self.value {
            None => {
                self.value = Some(input);
                input
            }
            Some(prev) => {
                let x = prev.x + self.alpha * (input.x - prev.x);
                let y = prev.y + self.alpha * (input.y - prev.y);
                let out = (Point { x, y }).clamped();
                self.value = Some(out);
                out
            }
        }
    }

    /// Forget prior history (e.g. when tracking is paused/resumed).
    pub fn reset(&mut self) {
        self.value = None;
    }
}

impl Default for OneEuro {
    fn default() -> Self {
        Self::new(0.25)
    }
}

impl Point {
    /// Clamp coordinates to the valid normalized range.
    pub fn clamped(mut self) -> Self {
        self.x = self.x.clamp(0.0, 1.0);
        self.y = self.y.clamp(0.0, 1.0);
        self
    }
}

/// Fit a screen-space calibration from gaze/head samples.
///
/// The simplest usable model is an affine transform (scale + offset) per axis:
///
/// ```text
/// screen_x = scale_x * raw_x + offset_x
/// screen_y = scale_y * raw_y + offset_y
/// ```
///
/// The user calibrates by dwelling on (or clicking) targets whose screen positions
/// are known; this maps their raw gaze coordinates into those screen positions.
/// A live session collects already-acquired pairs; see `CalibrationCollection`.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct AffineCalibration {
    pub scale_x: f32,
    pub offset_x: f32,
    pub scale_y: f32,
    pub offset_y: f32,
}

impl AffineCalibration {
    pub const IDENTITY: Self = Self {
        scale_x: 1.0,
        offset_x: 0.0,
        scale_y: 1.0,
        offset_y: 0.0,
    };

    pub fn apply(&self, raw: Point) -> Point {
        Point {
            x: raw.x * self.scale_x + self.offset_x,
            y: raw.y * self.scale_y + self.offset_y,
        }
        .clamped()
    }
}

/// Accumulator for calibration samples mapping raw ([`Point`]) to target
/// screen ([`Point`]). Uses linear regression per axis to solve the affine
/// once you have at least two distinct samples.
#[derive(Debug, Clone, Default)]
pub struct CalibrationCollection {
    /// (raw, target) pairs already gathered.
    samples: Vec<(Point, Point)>,
}

impl CalibrationCollection {
    pub fn new() -> Self {
        Self { samples: Vec::new() }
    }

    pub fn add(&mut self, raw: Point, target: Point) {
        self.samples.push((raw.clamped(), target.clamped()));
    }

    pub fn len(&self) -> usize {
        self.samples.len()
    }

    pub fn clear(&mut self) {
        self.samples.clear();
    }

    /// Fit the affine per axis using least squares through the origin model:
    /// `target = raw * scale + offset`. Returns an identity-calibration when
    /// there are fewer than two distinct raw samples (cannot fit an offset).
    pub fn solve(&self) -> AffineCalibration {
        let n = self.samples.len();
        if n < 2 {
            return AffineCalibration::IDENTITY;
        }

let fit = |coord: fn(&Point) -> f32, other: fn(&Point) -> f32| -> (f32, f32) {
            let sum_xy: f32 = self.samples.iter().map(|(r, t)| coord(r) * coord(t)).sum();
            let sum_x: f32 = self.samples.iter().map(|(r, _)| coord(r)).sum();
            let sum_y: f32 = self.samples.iter().map(|(_, t)| other(t)).sum();
            let nf = n as f32;

            // spread around mean; if flat -> identity scale.
            let denom: f32 = self.samples.iter().map(|(r, _)| (coord(r) - sum_x / nf).powi(2)).sum();
            if denom.abs() < 1e-6 {
                return (1.0_f32, 0.0_f32);
            }
            // scale = cov(x,y)/var(x)
            let scale: f32 =
                (sum_xy - sum_x * sum_y / nf) / denom;
            // offset = mean(y) - scale*mean(x)
            let offset: f32 = sum_y / nf - scale * (sum_x / nf);
            (scale, offset)
        };

        let (scale_x, offset_x) = fit(|p| p.x, |p| p.x);
        let (scale_y, offset_y) = fit(|p| p.y, |p| p.y);

        AffineCalibration {
            scale_x,
            offset_x,
            scale_y,
            offset_y,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_calibration_leaves_points_alone() {
        let c = AffineCalibration::IDENTITY;
        assert_eq!(c.apply(Point { x: 0.3, y: 0.7 }), Point { x: 0.3, y: 0.7 });
    }

    #[test]
    fn calibration_clamps_to_screen() {
        let c = AffineCalibration {
            scale_x: 2.0,
            offset_x: -0.5,
            scale_y: 1.0,
            offset_y: 0.0,
        };
        // 2*1 - 0.5 = 1.5 -> clamp to 1.0
        assert_eq!(c.apply(Point { x: 1.0, y: 0.5 }).x, 1.0);
        // 2*0 - 0.5 = -0.5 -> clamp to 0.0
        assert_eq!(c.apply(Point { x: 0.0, y: 0.5 }).x, 0.0);
    }

    #[test]
    fn smoothing_converges_towards_input() {
        let mut s = OneEuro::new(0.5);
        let a = Point { x: 0.1, y: 0.1 };
        assert_eq!(s.push(a), a); // first sample passes through
        let b = Point { x: 0.9, y: 0.9 };
        let mid = s.push(b);
        assert!(mid.x > a.x && mid.x < b.x);
        // Repeated pushes move toward b
        let mut cur = mid;
        for _ in 0..20 {
            cur = s.push(b);
        }
        assert!((cur.x - b.x).abs() < 1e-3);
    }

    #[test]
    fn calibration_solves_exact_affine() {
        let mut coll = CalibrationCollection::new();
        // raw 0..1 mapped to a target 0..1 region: target = 0.8*raw + 0.1
        coll.add(Point { x: 0.0, y: 0.0 }, Point { x: 0.1, y: 0.1 });
        coll.add(Point { x: 1.0, y: 1.0 }, Point { x: 0.9, y: 0.9 });
        let cal = coll.solve();
        let app = cal.apply(Point { x: 0.5, y: 0.5 });
        assert!((app.x - 0.5).abs() < 1e-3 && (app.y - 0.5).abs() < 1e-3);
    }

    #[test]
    fn calibration_identity_with_fewer_than_two_samples() {
        let mut coll = CalibrationCollection::new();
        coll.add(Point { x: 0.2, y: 0.4 }, Point { x: 0.5, y: 0.5 });
        assert_eq!(coll.solve(), AffineCalibration::IDENTITY);
    }
}
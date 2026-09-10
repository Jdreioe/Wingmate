//! Local EyeTrax process, owned by the cancellable gaze subscription.
use super::{
    Status,
    protocol::{Point, Sample},
    runner::Source,
};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::time::Instant;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Camera {
    pub path: String,
    pub name: String,
}
impl std::fmt::Display for Camera {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} ({})", self.name, self.path)
    }
}

pub fn cameras() -> Vec<Camera> {
    let mut cameras = Vec::new();
    if let Ok(entries) = std::fs::read_dir("/sys/class/video4linux") {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            // V4L2 may expose additional metadata nodes; opening a non-capture
            // node reports a recoverable camera failure without auto-switching.
            if name.starts_with("video") {
                cameras.push(Camera {
                    path: format!("/dev/{name}"),
                    name: std::fs::read_to_string(entry.path().join("name"))
                        .unwrap_or_else(|_| name.clone())
                        .trim()
                        .into(),
                });
            }
        }
    }
    cameras.sort_by(|a, b| a.path.cmp(&b.path));
    cameras
}

pub struct Control {
    pub camera: Camera,
    pub presented: AtomicU32,
    pub step_through: AtomicBool,
    pub settle_millis: AtomicU32,
    pub target_size: AtomicU32,
    pub selected_result: AtomicU32,
    pub improve_area: AtomicU32,
}
impl Control {
    pub fn new(camera: Camera) -> Self {
        Self {
            camera,
            presented: AtomicU32::new(0),
            step_through: AtomicBool::new(false),
            settle_millis: AtomicU32::new(2000),
            target_size: AtomicU32::new(80),
            selected_result: AtomicU32::new(10),
            improve_area: AtomicU32::new(0),
        }
    }
}

#[derive(Debug, Clone)]
pub enum Progress {
    Positioning {
        feedback: Feedback,
    },
    Target {
        index: u32,
        point: Point,
        learning_total: u32,
        validation: bool,
        feedback: Feedback,
    },
    Validated,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Feedback {
    Waiting,
    EyesMissing,
    EyesVisible,
    OnTarget,
    OffTarget,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Failure {
    Tracking,
    Accuracy,
    Estimator,
}

#[derive(Debug, Clone, serde::Deserialize)]
pub struct ValidationSummary {
    pub target: u32,
    pub matched: u32,
    pub total: u32,
    pub offset: Option<[f64; 2]>,
    pub spread: Option<[f64; 2]>,
}

impl ValidationSummary {
    pub fn passed(&self) -> bool {
        self.total >= 15
            && self.matched <= self.total
            && self.matched as f64 / self.total as f64 >= 0.9
    }
    fn point(&self) -> Point {
        match self.target {
            10 => Point { x: 0.25, y: 0.25 },
            11 => Point { x: 0.75, y: 0.75 },
            12 => Point { x: 0.75, y: 0.25 },
            _ => Point { x: 0.25, y: 0.75 },
        }
    }
}

#[derive(serde::Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Event {
    Positioning {
        feedback: Feedback,
    },
    Target {
        index: u32,
        x: f64,
        y: f64,
        validation: bool,
    },
    CalibrationComplete {
        validation: Vec<ValidationSummary>,
        #[serde(default)]
        kept_previous: bool,
    },
    TargetFeedback {
        index: u32,
        feedback: Feedback,
    },
    Sample {
        point: Option<[f64; 2]>,
        age_ms: f64,
    },
    RuntimeMissing,
    CameraUnavailable,
    CalibrationFailed {
        reason: Failure,
    },
}

#[cfg(target_os = "linux")]
pub async fn run(source: Source) {
    if run_inner(&source).await.is_err() {
        source.unavailable(Status::CameraUnavailable);
    }
    // Keep the failure visible until an explicit retry; don't reopen a camera.
    std::future::pending::<()>().await;
}

#[cfg(target_os = "linux")]
async fn run_inner(source: &Source) -> Result<(), ()> {
    use std::process::Stdio;
    let control = source.webcam.as_ref().ok_or(())?;
    let runtime = crate::data_directory().join("webcam");
    let python = runtime.join("venv/bin/python");
    let model = runtime.join("face_landmarker.task");
    if !python.is_file() || !model.is_file() {
        source.unavailable(Status::WebcamRuntimeMissing);
        return Ok(());
    }
    let child = tokio::process::Command::new(python)
        .args(["-u", "-c", include_str!("../../../webcam/provider.py")])
        .arg(&control.camera.path)
        .arg(model)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .map_err(|_| ())?;
    read_child(source, child).await
}

#[cfg(target_os = "linux")]
async fn read_child(source: &Source, mut child: tokio::process::Child) -> Result<(), ()> {
    use std::time::Duration;
    use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
    let control = source.webcam.as_ref().ok_or(())?;
    let mut input = child.stdin.take().ok_or(())?;
    let mut output = BufReader::new(child.stdout.take().ok_or(())?);
    let mut frame = 0u32;
    let start = Instant::now();
    let mut validated = false;
    let mut started = false;
    let mut expected_target = 1;
    let mut learning_total = 9;
    let mut requested = Instant::now();
    loop {
        // A stalled child cannot keep an old gaze sample alive. Bound each
        // record too, so a broken helper cannot grow the mailbox indefinitely.
        let mut line = String::new();
        let result = tokio::time::timeout(
            Duration::from_secs(15),
            (&mut output).take(4097).read_line(&mut line),
        )
        .await;
        if !matches!(result, Ok(Ok(1..))) || line.len() > 4096 || !line.ends_with('\n') {
            return Err(());
        }
        let event: Event = serde_json::from_str(&line).map_err(|_| ())?;
        match event {
            Event::Positioning { feedback }
                if !started
                    && expected_target == 1
                    && matches!(feedback, Feedback::EyesVisible | Feedback::EyesMissing) =>
            {
                source.progress(Some(Progress::Positioning { feedback }));
                if control.presented.load(Ordering::Acquire) == 100 {
                    let pace = control.settle_millis.load(Ordering::Acquire);
                    input
                        .write_all(format!("start {pace}\n").as_bytes())
                        .await
                        .map_err(|_| ())?;
                    started = true;
                }
            }
            Event::Target {
                index,
                x,
                y,
                validation,
            } if !validated
                && index == expected_target
                && index <= 13
                && validation == (index > 9) =>
            {
                let point = checked_point(Some([x, y]), 0.0).ok_or(())?;
                source.progress(Some(Progress::Target {
                    index,
                    point,
                    learning_total,
                    validation,
                    feedback: Feedback::Waiting,
                }));
                while control.presented.load(Ordering::Acquire) != index {
                    tokio::time::sleep(Duration::from_millis(16)).await;
                }
                expected_target += 1;
                input.write_all(b"ready\n").await.map_err(|_| ())?;
            }
            Event::TargetFeedback { index, feedback }
                if !validated
                    && index > 0
                    && index == expected_target - 1
                    && match feedback {
                        Feedback::OnTarget | Feedback::OffTarget => index > 9,
                        Feedback::EyesVisible => index <= 9,
                        Feedback::EyesMissing | Feedback::Waiting => true,
                    } =>
            {
                source.target_feedback(index, feedback);
            }
            Event::CalibrationComplete {
                validation,
                kept_previous,
            } if !validated && expected_target == 14 => {
                if validation.len() != 4
                    || validation.iter().enumerate().any(|(i, r)| {
                        r.target != i as u32 + 10
                            || r.matched > r.total
                            || r.total == 0
                            || r.offset.is_some_and(|p| p.iter().any(|v| !v.is_finite()))
                            || r.spread
                                .is_some_and(|p| p.iter().any(|v| !v.is_finite() || *v < 0.0))
                    })
                {
                    return Err(());
                }
                let passed = validation.iter().all(ValidationSummary::passed);
                control.improve_area.store(0, Ordering::Release);
                control.presented.store(0, Ordering::Release);
                if let Some(weak) = validation.iter().find(|r| !r.passed()) {
                    control
                        .selected_result
                        .store(weak.target, Ordering::Release);
                }
                source.calibration_complete(validation, kept_previous);
                loop {
                    let area = control.improve_area.load(Ordering::Acquire);
                    if (10..=13).contains(&area) {
                        // Reuse learning IDs 6–9 for four area corners, then
                        // require the complete independent validation sequence.
                        learning_total = 4;
                        expected_target = 6;
                        control.presented.store(0, Ordering::Release);
                        input
                            .write_all(format!("improve {area}\n").as_bytes())
                            .await
                            .map_err(|_| ())?;
                        break;
                    }
                    if passed && control.presented.load(Ordering::Acquire) == 14 {
                        validated = true;
                        source.progress(None);
                        requested = Instant::now();
                        input.write_all(b"ready\n").await.map_err(|_| ())?;
                        break;
                    }
                    tokio::time::sleep(Duration::from_millis(16)).await;
                }
            }
            Event::Sample { point, age_ms } if validated => {
                frame = frame.wrapping_add(1);
                let now = Instant::now();
                let age_valid = age_ms.is_finite() && (0.0..=100.0).contains(&age_ms);
                // Expiry is measured from capture/request time, not arrival.
                // Otherwise a 90 ms estimate could survive another 100 ms.
                let received = if age_valid {
                    requested.min(now - Duration::from_secs_f64(age_ms / 1000.0))
                } else {
                    now
                };
                source.sample(
                    Sample {
                        frame_counter: frame,
                        timestamp_us: start.elapsed().as_micros() as i64,
                        point: checked_point(
                            point,
                            if age_valid {
                                now.saturating_duration_since(received).as_secs_f64() * 1000.0
                            } else {
                                f64::INFINITY
                            },
                        ),
                    },
                    received,
                );
                requested = Instant::now();
                input.write_all(b"next\n").await.map_err(|_| ())?;
            }
            Event::RuntimeMissing => {
                source.unavailable(Status::WebcamRuntimeMissing);
                return Ok(());
            }
            Event::CameraUnavailable => return Err(()),
            Event::CalibrationFailed { reason } => {
                source.unavailable(Status::CalibrationFailed(reason));
                return Ok(());
            }
            _ => return Err(()),
        }
    }
}

fn checked_point(point: Option<[f64; 2]>, age_ms: f64) -> Option<Point> {
    let [x, y] = point?;
    (age_ms.is_finite()
        && (0.0..=100.0).contains(&age_ms)
        && x.is_finite()
        && y.is_finite()
        && (0.0..=1.0).contains(&x)
        && (0.0..=1.0).contains(&y))
    .then_some(Point { x, y })
}

pub fn calibration_view(source: &Source) -> Option<iced::Element<'static, crate::Message>> {
    use iced::{
        Fill,
        widget::{
            Space, button, checkbox, column, container, responsive, row, scrollable, stack, text,
        },
    };
    let snapshot = source.snapshot();
    let control = source.webcam.as_ref()?;
    let fresh = snapshot.received.elapsed() <= std::time::Duration::from_millis(300);
    let green = iced::Color::from_rgb8(80, 220, 120);
    let amber = iced::Color::from_rgb8(240, 180, 65);
    let red = iced::Color::from_rgb8(245, 105, 105);
    let neutral = iced::Color::from_rgb8(180, 190, 200);
    match snapshot.progress? {
        Progress::Positioning { feedback } => {
            let visible = fresh && feedback == Feedback::EyesVisible;
            let status = if visible {
                "Eyes detected"
            } else {
                "Waiting for your eyes"
            };
            let color = if visible { green } else { amber };
            let pace = control.settle_millis.load(Ordering::Acquire);
            let size = control.target_size.load(Ordering::Acquire);
            let mut speeds = row![].spacing(12);
            for (label, value) in [("Slow", 4000), ("Normal", 2000), ("Fast", 1000)] {
                speeds = speeds.push(
                    button(label)
                        .height(52)
                        .width(120)
                        .style(if pace == value {
                            button::primary
                        } else {
                            button::secondary
                        })
                        .on_press(crate::Message::WebcamPace(value)),
                );
            }
            let mut sizes = row![].spacing(12);
            for (label, value) in [("Small", 64), ("Medium", 80), ("Large", 112)] {
                sizes = sizes.push(
                    button(label)
                        .height(52)
                        .width(120)
                        .style(if size == value {
                            button::primary
                        } else {
                            button::secondary
                        })
                        .on_press(crate::Message::WebcamTargetSize(value)),
                );
            }
            Some(scrollable(container(column![
                text("Camera gaze calibration").size(32),
                text("Position the camera so both eyes are visible. Keep a comfortable, steady position."),
                container(column![text("●").size(54).color(color), text(status).size(24).color(color)]
                    .align_x(iced::Alignment::Center).spacing(8)).center_x(Fill).height(150)
                    .style(container::bordered_box),
                checkbox(control.step_through.load(Ordering::Acquire))
                    .label("Step through: press Enter when ready for each target")
                    .on_toggle(crate::Message::WebcamStepThrough),
                text("Time to find each target").size(22), speeds.wrap(),
                text("Target size").size(22), sizes.wrap(),
                text("Look at the center of each circle. Nine learning targets are followed by four accuracy checks. Blink normally."),
                row![button("Start calibration").height(56).on_press_maybe(visible.then_some(crate::Message::WebcamContinue)),
                    button("Cancel").height(56).on_press(crate::Message::ToggleGaze)].spacing(16),
            ].spacing(18).max_width(720)).padding(24).center_x(Fill).center_y(Fill)).height(Fill).into())
        }
        Progress::Target {
            index,
            point,
            learning_total,
            validation,
            feedback,
        } => {
            let feedback = if fresh { feedback } else { Feedback::Waiting };
            let (label, color) = match feedback {
                Feedback::Waiting => ("Waiting for fresh eye tracking", neutral),
                Feedback::EyesMissing => ("Eyes not tracked. Keep both eyes visible.", amber),
                Feedback::EyesVisible => (
                    "Eyes detected. Learning where you look.",
                    iced::Color::from_rgb8(100, 180, 255),
                ),
                Feedback::OnTarget => ("On target", green),
                Feedback::OffTarget => ("Estimated gaze is outside the target", amber),
            };
            let diameter = control.target_size.load(Ordering::Acquire) as f32;
            let waiting = control.step_through.load(Ordering::Acquire)
                && control.presented.load(Ordering::Acquire) != index;
            if !waiting {
                control.presented.store(index, Ordering::Release);
            }
            Some(
                responsive(move |size| {
                    let target = column![
                        Space::new()
                            .height((point.y as f32 * size.height - diameter / 2.0).max(0.0)),
                        row![
                            Space::new()
                                .width((point.x as f32 * size.width - diameter / 2.0).max(0.0)),
                            container(text("•").size(28))
                                .center_x(diameter)
                                .center_y(diameter)
                                .style(move |_| container::Style {
                                    border: iced::Border {
                                        color,
                                        width: 4.0,
                                        radius: (diameter / 2.0).into()
                                    },
                                    text_color: Some(iced::Color::WHITE),
                                    ..Default::default()
                                })
                        ]
                    ];
                    let instructions = column![
                        text(if validation {
                            "Accuracy check: look at the center"
                        } else {
                            "Learning: look at the center"
                        })
                        .size(24),
                        text(if validation {
                            format!("Accuracy check {} of 4. Blink normally.", index - 9)
                        } else {
                            format!(
                                "{} target {} of {learning_total}. Blink normally.",
                                if learning_total == 4 {
                                    "Area"
                                } else {
                                    "Learning"
                                },
                                index + learning_total - 9
                            )
                        }),
                        text(if waiting {
                            "Take your time. Press Enter when looking at the center."
                        } else {
                            label
                        })
                        .size(22)
                        .color(if waiting { neutral } else { color }),
                    ]
                    .spacing(8);
                    let mut actions = row![].spacing(12);
                    if waiting {
                        actions = actions.push(
                            button("Collect this point (Enter)")
                                .height(56)
                                .on_press(crate::Message::WebcamContinue),
                        );
                    }
                    actions = actions.push(
                        button("Cancel calibration (Esc)")
                            .height(56)
                            .on_press(crate::Message::ToggleGaze),
                    );
                    let help = container(instructions.push(actions)).padding(12);
                    let help = container(help).width(Fill).height(Fill).center_x(Fill);
                    let help = if point.y > 0.5 {
                        help
                    } else {
                        help.align_bottom(Fill)
                    };
                    stack![target, help].width(Fill).height(Fill).into()
                })
                .into(),
            )
        }
        Progress::Validated => {
            let results = snapshot.validation;
            let kept_previous = snapshot.kept_previous;
            let passed = results.len() == 4 && results.iter().all(ValidationSummary::passed);
            let selected = control.selected_result.load(Ordering::Acquire);
            let improving = control.improve_area.load(Ordering::Acquire) != 0;
            Some(responsive(move |size| {
            let mut top = row![].spacing(24);
            let mut bottom = row![].spacing(24);
            for id in [10, 12, 13, 11] {
                if let Some(result) = results.iter().find(|r| r.target == id) {
                    let proportion = result.matched as f64 / result.total as f64;
                    let (label, color) = if result.passed() { ("Ready", green) }
                        else if proportion >= 0.5 { ("Variable", amber) } else { ("Needs improvement", red) };
                    let marker = container(text(format!("{}%", (proportion * 100.0).round() as u32)).size(24))
                        .center_x(96).center_y(96).style(move |_| container::Style {
                            border: iced::Border { color, width: 3.0, radius: 48.0.into() },
                            text_color: Some(color), ..Default::default()
                        });
                    let card = button(column![marker, text(label).color(color)].spacing(8).align_x(iced::Alignment::Center))
                        .padding(12).width(iced::Length::Fill).height(170)
                        .style(if selected == id { button::secondary } else { button::text })
                        .on_press(crate::Message::WebcamInspectResult(id));
                    if result.point().y < 0.5 { top = top.push(card); } else { bottom = bottom.push(card); }
                }
            }
            let mut details = column![text("Selected point").size(22)].spacing(16).width(Fill).max_width(360);
            if let Some(result) = results.iter().find(|r| r.target == selected) {
                let point = result.point();
                details = details.push(text(format!("{} {}", if point.y < 0.5 { "Top" } else { "Bottom" }, if point.x < 0.5 { "left" } else { "right" })).size(24))
                    .push(text(format!("{} of {} estimates matched. At least 90% must match.", result.matched, result.total)));
                if let Some([x, y]) = result.offset {
                    details = details.push(text(format!("Average miss: {:.1}% of screen width {}, {:.1}% of screen height {}.",
                        x.abs() * 100.0, if x < 0.0 { "left" } else { "right" }, y.abs() * 100.0, if y < 0.0 { "above" } else { "below" })));
                }
                if let Some([x, y]) = result.spread {
                    details = details.push(text(format!("Spread around the average: {:.1}% of screen width, {:.1}% of screen height.", x * 100.0, y * 100.0)));
                }
            }
            if results.iter().any(|r| r.target == selected) {
                details = details.push(text("Relearn the four corners of this area, then check accuracy across the screen."))
                    .push(button("Improve this area (4 points)").height(56)
                        .on_press_maybe((!improving).then_some(crate::Message::WebcamImproveArea(selected))));
            }
            let heading = if kept_previous { "Previous calibration kept" } else if passed { "Calibration ready for large targets" } else { "Calibration needs improvement" };
            let explanation = if kept_previous && !passed {
                "This retry did not improve the checks. The previous calibration was kept and tested on your latest checks. Selection is still off."
            } else if kept_previous {
                "This retry did not improve the checks. The previous calibration passed all four fresh checks."
            } else if passed { "All four accuracy checks passed. Start with a small grid of large targets." }
                else { "Some areas need another try. Selection is off. Select a weak area, then choose Improve this area." };
            let map = column![top, bottom].spacing(20).width(Fill).max_width(464);
            let content: iced::Element<'_, crate::Message> = if size.width >= 950.0 {
                row![map, details].spacing(32).into()
            } else {
                column![map, details].spacing(24).into()
            };
            scrollable(container(column![
                text(heading).size(30),
                text(explanation),
                content,
                text("Keep your position and camera fixed when improving an area. If either has moved, retry the full calibration. Learning samples stay in memory until you start gaze, retry fully, or exit."),
                row![button("Retry full calibration").height(56).on_press(crate::Message::WebcamRetry),
                    button("Start camera gaze").height(56).on_press_maybe((passed && !improving).then_some(crate::Message::WebcamContinue)),
                    button("Cancel").height(56).on_press(crate::Message::ToggleGaze)].spacing(16).wrap(),
            ].spacing(20).max_width(1000)).padding(24).center_x(Fill)).height(Fill).into()
            }).into())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn calibration_instructions_use_the_full_display() {
        use iced::advanced::{layout::Limits, renderer::Headless, widget::Tree};
        let renderer = iced::futures::executor::block_on(iced::Renderer::new(
            iced::Font::default(),
            iced::Pixels(16.0),
            Some("tiny-skia"),
        ))
        .expect("headless renderer");
        let source = Source::camera(Camera {
            path: "/dev/unused".into(),
            name: "Test".into(),
        });
        for size in [
            iced::Size::new(1100.0, 760.0),
            iced::Size::new(849.0, 493.0),
        ] {
            for (index, y, step_through) in [(10, 0.25, false), (5, 0.5, true), (8, 0.9, true)] {
                let control = source.webcam.as_ref().unwrap();
                control.step_through.store(step_through, Ordering::Release);
                control.target_size.store(112, Ordering::Release);
                source.progress(Some(Progress::Target {
                    index,
                    learning_total: 9,
                    point: Point { x: 0.5, y },
                    validation: index > 9,
                    feedback: Feedback::Waiting,
                }));
                let mut view = calibration_view(&source).unwrap();
                let mut tree = Tree::new(view.as_widget());
                let layout = view.as_widget_mut().layout(
                    &mut tree,
                    &renderer,
                    &Limits::new(iced::Size::ZERO, size),
                );
                let stack = &layout.children()[0];
                assert_eq!(stack.size(), size, "instructions must use the full display");
                let instructions = stack.children()[1].children()[0].bounds();
                let target_y = size.height * y as f32;
                assert!(
                    instructions.y >= target_y + 56.0
                        || instructions.y + instructions.height <= target_y - 56.0,
                    "instructions must not cover target {index} at {size:?}: {instructions:?}"
                );
            }
        }
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn child_protocol_gates_calibration_and_cancels_owned_process() {
        use std::{process::Stdio, time::Duration};
        tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(async {
            let source = Source::camera(Camera { path: "/dev/unused".into(), name: "Test".into() });
            // A helper must never send samples before all calibration stages.
            let premature = tokio::process::Command::new("sh")
                .args(["-c", "printf '%s\\n' '{\"kind\":\"sample\",\"point\":[0.5,0.5],\"age_ms\":0}'"])
                .stdin(Stdio::piped()).stdout(Stdio::piped()).kill_on_drop(true).spawn().unwrap();
            assert!(read_child(&source, premature).await.is_err());
            assert!(source.snapshot().sample.is_none());

            let child = tokio::process::Command::new("sh")
                .args(["-c", "printf '%s\\n' '{\"kind\":\"target\",\"index\":1,\"x\":0.1,\"y\":0.1,\"validation\":false}'; read ready; read next"])
                .stdin(Stdio::piped()).stdout(Stdio::piped()).kill_on_drop(true).spawn().unwrap();
            let pid = child.id().unwrap();
            let worker_source = source.clone();
            let worker = tokio::spawn(async move { read_child(&worker_source, child).await });
            tokio::time::timeout(Duration::from_secs(2), async {
                while source.snapshot().progress.is_none() { tokio::task::yield_now().await; }
            }).await.unwrap();
            assert_eq!(source.snapshot().status, Status::Calibrating);
            assert!(source.snapshot().point(Instant::now()).is_none());
            worker.abort();
            let _ = worker.await;
            tokio::time::timeout(Duration::from_secs(2), async {
                while std::path::Path::new(&format!("/proc/{pid}")).exists() {
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
            }).await.expect("owned camera process must be reaped on cancellation");
        });
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn validated_child_reaches_the_shared_mailbox_and_reports_losses() {
        use std::{process::Stdio, time::Duration};
        tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(async {
            let source = Source::camera(Camera { path: "/dev/unused".into(), name: "Test".into() });
            let mut script = String::new();
            for index in 1..=13 {
                let event = serde_json::json!({"kind":"target", "index":index, "x":0.5, "y":0.5, "validation": index > 9});
                script.push_str(&format!("printf '%s\\n' '{event}'; read ready || exit; "));
            }
            let validation: Vec<_> = (10..=13).map(|target| serde_json::json!({
                "target": target, "matched": 20, "total": 20, "offset": [0.0, 0.0], "spread": [0.0, 0.0]
            })).collect();
            let event = serde_json::json!({"kind": "calibration_complete", "validation": validation});
            script.push_str(&format!("printf '%s\\n' '{event}'; read ready || exit; "));
            for point in [Some([0.25, 0.25]), None, Some([0.75, 0.75])] {
                let event = serde_json::json!({"kind":"sample", "point":point, "age_ms":0});
                script.push_str(&format!("printf '%s\\n' '{event}'; read next || exit; "));
            }
            script.push_str("read end");
            let child = tokio::process::Command::new("sh").args(["-c", &script])
                .stdin(Stdio::piped()).stdout(Stdio::piped()).kill_on_drop(true).spawn().unwrap();
            let worker_source = source.clone();
            let worker = tokio::spawn(async move { read_child(&worker_source, child).await });
            tokio::time::timeout(Duration::from_secs(3), async {
                loop {
                    let snapshot = source.snapshot();
                    match snapshot.progress {
                        Some(Progress::Target { index, .. }) => source.webcam.as_ref().unwrap().presented.store(index, Ordering::Release),
                        Some(Progress::Validated) => source.webcam.as_ref().unwrap().presented.store(14, Ordering::Release),
                        Some(Progress::Positioning { .. }) | None => {},
                    }
                    if snapshot.sample.is_some_and(|sample| sample.frame_counter == 3) { break; }
                    tokio::task::yield_now().await;
                }
            }).await.unwrap();
            let snapshot = source.snapshot();
            assert_eq!(snapshot.point(Instant::now()), Some(Point { x: 0.75, y: 0.75 }));
            assert!(snapshot.losses >= 16); // 15 setup resets plus the invalid frame.
            worker.abort();
            let _ = worker.await;
        });
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn area_retry_requires_four_learning_points_and_all_accuracy_checks() {
        use std::{process::Stdio, time::Duration};
        tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(async {
            for complete_recheck in [true, false] {
                let source = Source::camera(Camera { path: "/dev/unused".into(), name: "Test".into() });
                let mut script = String::new();
                for round in 0..2 {
                    let last = if round == 1 && !complete_recheck { 12 } else { 13 };
                    for index in (if round == 0 { 1 } else { 6 })..=last {
                        let event = serde_json::json!({"kind":"target", "index":index, "x":0.5, "y":0.5, "validation": index > 9});
                        script.push_str(&format!("printf '%s\\n' '{event}'; read ready || exit; "));
                    }
                    let validation: Vec<_> = (10..=13).map(|target| serde_json::json!({
                        "target": target, "matched": if round == 0 && target == 10 { 19 } else { 22 },
                        "total": 22, "offset": [0.0, 0.0], "spread": [0.0, 0.0]
                    })).collect();
                    let event = serde_json::json!({"kind":"calibration_complete", "validation":validation, "kept_previous": round == 1});
                    script.push_str(&format!("printf '%s\\n' '{event}'; read command || exit; "));
                    if round == 0 { script.push_str("[ \"$command\" = 'improve 10' ] || exit; "); }
                }
                script.push_str("printf '%s\\n' '{\"kind\":\"sample\",\"point\":[0.5,0.5],\"age_ms\":0}'; read next; read end");
                let child = tokio::process::Command::new("sh").args(["-c", &script])
                    .stdin(Stdio::piped()).stdout(Stdio::piped()).kill_on_drop(true).spawn().unwrap();
                let worker_source = source.clone();
                let worker = tokio::spawn(async move { read_child(&worker_source, child).await });
                let mut retried = false;
                let mut area_points = Vec::new();
                tokio::time::timeout(Duration::from_secs(3), async {
                    loop {
                        let snapshot = source.snapshot();
                        let control = source.webcam.as_ref().unwrap();
                        match snapshot.progress {
                            Some(Progress::Target { index, learning_total, .. }) => {
                                if retried && index <= 9 {
                                    assert_eq!(learning_total, 4);
                                    if area_points.last() != Some(&index) { area_points.push(index); }
                                }
                                assert!(snapshot.sample.is_none());
                                control.presented.store(index, Ordering::Release);
                            }
                            Some(Progress::Validated) if !retried => {
                                assert_eq!(snapshot.status, Status::CalibrationFailed(Failure::Accuracy));
                                assert!(snapshot.point(Instant::now()).is_none());
                                retried = true;
                                control.improve_area.store(10, Ordering::Release);
                            }
                            Some(Progress::Validated) if snapshot.validation.iter().all(ValidationSummary::passed) => {
                                assert!(snapshot.kept_previous);
                                assert!(snapshot.sample.is_none());
                                control.presented.store(14, Ordering::Release);
                            }
                            _ => {}
                        }
                        if snapshot.sample.is_some() || worker.is_finished() { break; }
                        tokio::task::yield_now().await;
                    }
                }).await.unwrap();
                assert_eq!(area_points, [6, 7, 8, 9]);
                if complete_recheck {
                    assert_eq!(source.snapshot().point(Instant::now()), Some(Point { x: 0.5, y: 0.5 }));
                    worker.abort();
                    let _ = worker.await;
                } else {
                    assert!(worker.await.unwrap().is_err(), "an incomplete recheck must be rejected");
                    assert!(source.snapshot().sample.is_none());
                }
            }
        });
    }

    #[test]
    fn rejects_invalid_offscreen_and_stale_estimates() {
        for point in [
            [f64::NAN, 0.5],
            [0.5, f64::INFINITY],
            [-0.01, 0.5],
            [1.01, 0.5],
        ] {
            assert!(checked_point(Some(point), 0.0).is_none());
        }
        for age in [-1.0, 101.0, f64::NAN] {
            assert!(checked_point(Some([0.5, 0.5]), age).is_none());
        }
        assert_eq!(
            checked_point(Some([0.25, 0.75]), 50.0),
            Some(Point { x: 0.25, y: 0.75 })
        );
    }
}

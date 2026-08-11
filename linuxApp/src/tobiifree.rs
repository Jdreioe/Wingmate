use cosmic::iced::futures::stream;
use cosmic::iced::Subscription;
use std::collections::VecDeque;
use std::env;
use std::path::PathBuf;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::UnixStream;

const GAZE_MESSAGE: u8 = 0x01;
const SUBSCRIBE_COMMAND: [u8; 9] = [0x01, 0x04, 0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00];
const HEADER_SIZE: usize = 5;
const MAX_PAYLOAD_SIZE: usize = 4096;

// Pinned to Aetherall/tobiifree d303e47. The daemon currently sends the native
// Zig extern struct, so any size change is an incompatible protocol revision.
const GAZE_PAYLOAD_SIZE: usize = 392;
const GAZE_2D_PRESENT: u32 = 1 << 6;

const PRESENT_MASK_OFFSET: usize = 0;
const FRAME_COUNTER_OFFSET: usize = 4;
const VALIDITY_LEFT_OFFSET: usize = 8;
const VALIDITY_RIGHT_OFFSET: usize = 12;
const TIMESTAMP_OFFSET: usize = 16;
const GAZE_X_OFFSET: usize = 40;
const GAZE_Y_OFFSET: usize = 48;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct GazeSample {
    pub frame_counter: u32,
    pub timestamp_us: i64,
    pub x: f32,
    pub y: f32,
    pub valid: bool,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TargetBounds {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

impl TargetBounds {
    fn contains(self, x: f32, y: f32) -> bool {
        x >= self.x && y >= self.y && x <= self.x + self.width && y <= self.y + self.height
    }
}

pub fn resolve_target<T>(
    point_x: f32,
    point_y: f32,
    targets: impl IntoIterator<Item = (T, TargetBounds)>,
) -> Option<T> {
    targets
        .into_iter()
        .filter(|(_, bounds)| bounds.contains(point_x, point_y))
        .min_by(|(_, left), (_, right)| {
            (left.width * left.height).total_cmp(&(right.width * right.height))
        })
        .map(|(target, _)| target)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionStatus {
    Connecting,
    Connected,
    DaemonUnavailable,
    IncompatibleProtocol,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Event {
    Status(ConnectionStatus),
    Sample(GazeSample),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ProtocolError {
    FrameTooLarge,
    IncompatibleGazePayload,
}

#[derive(Debug, Default)]
struct FrameDecoder {
    buffer: Vec<u8>,
}

impl FrameDecoder {
    fn push(&mut self, bytes: &[u8]) -> Result<VecDeque<GazeSample>, ProtocolError> {
        self.buffer.extend_from_slice(bytes);
        let mut samples = VecDeque::new();

        loop {
            if self.buffer.len() < HEADER_SIZE {
                break;
            }
            let message_type = self.buffer[0];
            let payload_size = u32::from_le_bytes(
                self.buffer[1..HEADER_SIZE]
                    .try_into()
                    .expect("the frame header has a fixed length"),
            ) as usize;
            if payload_size > MAX_PAYLOAD_SIZE {
                return Err(ProtocolError::FrameTooLarge);
            }
            let frame_size = HEADER_SIZE + payload_size;
            if self.buffer.len() < frame_size {
                break;
            }

            if message_type == GAZE_MESSAGE {
                if payload_size != GAZE_PAYLOAD_SIZE {
                    return Err(ProtocolError::IncompatibleGazePayload);
                }
                samples.push_back(decode_gaze_payload(&self.buffer[HEADER_SIZE..frame_size]));
            }
            self.buffer.drain(..frame_size);
        }

        Ok(samples)
    }
}

fn decode_gaze_payload(payload: &[u8]) -> GazeSample {
    let present_mask = read_u32(payload, PRESENT_MASK_OFFSET);
    let validity_left = read_u32(payload, VALIDITY_LEFT_OFFSET);
    let validity_right = read_u32(payload, VALIDITY_RIGHT_OFFSET);
    let x = read_f64(payload, GAZE_X_OFFSET);
    let y = read_f64(payload, GAZE_Y_OFFSET);
    let coordinates_are_valid =
        x.is_finite() && y.is_finite() && (0.0..=1.0).contains(&x) && (0.0..=1.0).contains(&y);

    GazeSample {
        frame_counter: read_u32(payload, FRAME_COUNTER_OFFSET),
        timestamp_us: read_i64(payload, TIMESTAMP_OFFSET),
        x: x as f32,
        y: y as f32,
        valid: present_mask & GAZE_2D_PRESENT != 0
            && (validity_left == 0 || validity_right == 0)
            && coordinates_are_valid,
    }
}

fn read_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(
        bytes[offset..offset + 4]
            .try_into()
            .expect("validated gaze payload"),
    )
}

fn read_i64(bytes: &[u8], offset: usize) -> i64 {
    i64::from_le_bytes(
        bytes[offset..offset + 8]
            .try_into()
            .expect("validated gaze payload"),
    )
}

fn read_f64(bytes: &[u8], offset: usize) -> f64 {
    f64::from_le_bytes(
        bytes[offset..offset + 8]
            .try_into()
            .expect("validated gaze payload"),
    )
}

fn socket_path() -> PathBuf {
    env::var_os("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/tmp"))
        .join("tobiifreed/gaze.sock")
}

enum StreamState {
    Connecting,
    Streaming {
        stream: UnixStream,
        decoder: FrameDecoder,
    },
    Backoff,
}

pub fn subscription() -> Subscription<Event> {
    Subscription::run_with("wingmate-tobiifreed-v1", |_| {
        stream::unfold(StreamState::Connecting, next_event)
    })
}

async fn next_event(state: StreamState) -> Option<(Event, StreamState)> {
    match state {
        StreamState::Connecting => match connect().await {
            Ok(stream) => Some((
                Event::Status(ConnectionStatus::Connected),
                StreamState::Streaming {
                    stream,
                    decoder: FrameDecoder::default(),
                },
            )),
            Err(()) => Some((
                Event::Status(ConnectionStatus::DaemonUnavailable),
                StreamState::Backoff,
            )),
        },
        StreamState::Backoff => {
            tokio::time::sleep(Duration::from_secs(1)).await;
            Some((
                Event::Status(ConnectionStatus::Connecting),
                StreamState::Connecting,
            ))
        }
        StreamState::Streaming {
            mut stream,
            mut decoder,
        } => {
            let mut bytes = [0u8; 4096];
            loop {
                match stream.read(&mut bytes).await {
                    Ok(0) | Err(_) => {
                        return Some((
                            Event::Status(ConnectionStatus::DaemonUnavailable),
                            StreamState::Backoff,
                        ));
                    }
                    Ok(read) => match decoder.push(&bytes[..read]) {
                        Err(_) => {
                            return Some((
                                Event::Status(ConnectionStatus::IncompatibleProtocol),
                                StreamState::Backoff,
                            ));
                        }
                        Ok(mut samples) => {
                            // Rendering every hardware frame is unnecessary for target-level
                            // selection. When one socket read contains a burst, keep its newest
                            // sample.
                            if let Some(sample) = samples.pop_back() {
                                return Some((
                                    Event::Sample(sample),
                                    StreamState::Streaming { stream, decoder },
                                ));
                            }
                        }
                    },
                }
            }
        }
    }
}

async fn connect() -> Result<UnixStream, ()> {
    let mut stream = UnixStream::connect(socket_path()).await.map_err(|_| ())?;
    stream.write_all(&SUBSCRIBE_COMMAND).await.map_err(|_| ())?;
    Ok(stream)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn gaze_payload(x: f64, y: f64, left_validity: u32, right_validity: u32) -> Vec<u8> {
        let mut payload = vec![0u8; GAZE_PAYLOAD_SIZE];
        payload[PRESENT_MASK_OFFSET..PRESENT_MASK_OFFSET + 4]
            .copy_from_slice(&GAZE_2D_PRESENT.to_le_bytes());
        payload[FRAME_COUNTER_OFFSET..FRAME_COUNTER_OFFSET + 4]
            .copy_from_slice(&42u32.to_le_bytes());
        payload[VALIDITY_LEFT_OFFSET..VALIDITY_LEFT_OFFSET + 4]
            .copy_from_slice(&left_validity.to_le_bytes());
        payload[VALIDITY_RIGHT_OFFSET..VALIDITY_RIGHT_OFFSET + 4]
            .copy_from_slice(&right_validity.to_le_bytes());
        payload[TIMESTAMP_OFFSET..TIMESTAMP_OFFSET + 8].copy_from_slice(&123_456i64.to_le_bytes());
        payload[GAZE_X_OFFSET..GAZE_X_OFFSET + 8].copy_from_slice(&x.to_le_bytes());
        payload[GAZE_Y_OFFSET..GAZE_Y_OFFSET + 8].copy_from_slice(&y.to_le_bytes());
        payload
    }

    fn frame(payload: &[u8]) -> Vec<u8> {
        let mut frame = Vec::with_capacity(HEADER_SIZE + payload.len());
        frame.push(GAZE_MESSAGE);
        frame.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        frame.extend_from_slice(payload);
        frame
    }

    #[test]
    fn decodes_a_pinned_gaze_frame_across_partial_reads() {
        let bytes = frame(&gaze_payload(0.25, 0.75, 0, 4));
        let mut decoder = FrameDecoder::default();

        assert!(decoder.push(&bytes[..3]).unwrap().is_empty());
        assert!(decoder.push(&bytes[3..97]).unwrap().is_empty());
        let samples = decoder.push(&bytes[97..]).unwrap();

        assert_eq!(samples.len(), 1);
        assert_eq!(
            samples[0],
            GazeSample {
                frame_counter: 42,
                timestamp_us: 123_456,
                x: 0.25,
                y: 0.75,
                valid: true,
            }
        );
    }

    #[test]
    fn rejects_an_unknown_native_struct_size() {
        let mut decoder = FrameDecoder::default();
        let bytes = frame(&vec![0u8; GAZE_PAYLOAD_SIZE - 1]);

        assert_eq!(
            decoder.push(&bytes),
            Err(ProtocolError::IncompatibleGazePayload)
        );
    }

    #[test]
    fn invalidates_lost_eyes_and_out_of_bounds_coordinates() {
        let lost = decode_gaze_payload(&gaze_payload(0.5, 0.5, 4, 4));
        let outside = decode_gaze_payload(&gaze_payload(1.1, 0.5, 0, 0));

        assert!(!lost.valid);
        assert!(!outside.valid);
    }

    #[test]
    fn hit_testing_chooses_the_smallest_overlapping_target() {
        let target = resolve_target(
            50.0,
            50.0,
            [
                (
                    "row",
                    TargetBounds {
                        x: 0.0,
                        y: 0.0,
                        width: 200.0,
                        height: 100.0,
                    },
                ),
                (
                    "cell",
                    TargetBounds {
                        x: 25.0,
                        y: 25.0,
                        width: 50.0,
                        height: 50.0,
                    },
                ),
            ],
        );

        assert_eq!(target, Some("cell"));
        assert_eq!(
            resolve_target(
                250.0,
                250.0,
                [(
                    "cell",
                    TargetBounds {
                        x: 25.0,
                        y: 25.0,
                        width: 50.0,
                        height: 50.0,
                    }
                )],
            ),
            None
        );
    }
}

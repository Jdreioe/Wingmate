//! Wire format of the `tobiifreed` Unix socket, pinned to the layout described
//! in `docs/GAZE_TD_I13.md`.
//!
//! The daemon copies a native Zig `extern struct` straight onto the socket and
//! upstream has already changed its size once, so nothing here follows the
//! struct blindly: a gaze payload of unexpected length, or one missing a field
//! selection needs, is a protocol mismatch and stops decoding.
//!
//! The daemon and Wingmate always run on the same machine, so the struct is
//! read with that machine's little-endian ordering.

/// `[u8 msg_type][u32 LE payload_len]`.
pub const HEADER_SIZE: usize = 5;

/// Size of the daemon's current `GazeSample`.
pub const GAZE_SAMPLE_SIZE: usize = 392;

/// A payload larger than this cannot be anything we understand, and reading it
/// would let a confused daemon grow our buffer without bound.
const MAX_PAYLOAD: usize = 64 * 1024;

const CMD_SUBSCRIBE: u8 = 0x01;
const CMD_DISCONNECT: u8 = 0xFF;
const SRV_GAZE: u8 = 0x01;

/// `subscribe` payload: the daemon's `STREAM_GAZE` selector.
const STREAM_GAZE: u32 = 0x500;

// Field offsets into `GazeSample`. Only the fields selection needs are read.
const OFFSET_PRESENT_MASK: usize = 0;
const OFFSET_FRAME_COUNTER: usize = 4;
const OFFSET_VALIDITY_L: usize = 8;
const OFFSET_VALIDITY_R: usize = 12;
const OFFSET_TIMESTAMP_US: usize = 16;
const OFFSET_GAZE_2D: usize = 40;

// Present-mask bits for those fields.
const BIT_TIMESTAMP: u32 = 1 << 0;
const BIT_FRAME_COUNTER: u32 = 1 << 1;
const BIT_VALIDITY_L: u32 = 1 << 2;
const BIT_VALIDITY_R: u32 = 1 << 3;
const BIT_GAZE_2D: u32 = 1 << 6;

/// Fields every sample must carry for dwell and gaze-loss handling to work.
const REQUIRED_BITS: u32 = BIT_TIMESTAMP | BIT_FRAME_COUNTER | BIT_VALIDITY_L | BIT_VALIDITY_R;

/// The daemon reports an eye it could not detect as `4`.
const VALIDITY_VALID: u32 = 0;

/// A gaze position on the daemon's configured display area, normalized to
/// `[0,1]²` with the origin in the top-left corner.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Point {
    pub x: f64,
    pub y: f64,
}

/// One decoded gaze frame.
///
/// `point` is `None` whenever the sample cannot place gaze on the display —
/// an eye was not detected, the field is absent, or the coordinate left the
/// display area. Callers treat that as gaze loss, not as an error.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Sample {
    pub frame_counter: u32,
    /// Device µs clock. Monotonic, but unrelated to wall time: only use it for
    /// elapsed-time arithmetic.
    pub timestamp_us: i64,
    pub point: Option<Point>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Message {
    Gaze,
    /// A response, display-area, or error message. The first slice does not
    /// need them, but they still have to be skipped by length.
    Ignored,
}

/// The daemon is speaking a protocol this build does not understand. Decoding
/// stops rather than guessing at coordinates.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProtocolError {
    /// A gaze payload whose length is not [`GAZE_SAMPLE_SIZE`].
    UnexpectedGazeSize(usize),
    /// A gaze sample that omits a field selection depends on.
    MissingFields(u32),
    /// A payload too large to be any message we know.
    OversizedPayload(usize),
}

/// `subscribe` (`0x01`) with the gaze stream selector.
pub fn subscribe_command() -> [u8; HEADER_SIZE + 4] {
    let mut command = [0u8; HEADER_SIZE + 4];
    command[0] = CMD_SUBSCRIBE;
    command[1..5].copy_from_slice(&4u32.to_le_bytes());
    command[5..9].copy_from_slice(&STREAM_GAZE.to_le_bytes());
    command
}

/// `disconnect` (`0xFF`), so the daemon frees the client slot promptly.
pub fn disconnect_command() -> [u8; HEADER_SIZE] {
    let mut command = [0u8; HEADER_SIZE];
    command[0] = CMD_DISCONNECT;
    command
}

/// Reassembles the daemon's stream into messages. Socket reads split and
/// coalesce messages freely, so bytes are buffered until one is complete.
#[derive(Debug, Default)]
pub struct Decoder {
    buffer: Vec<u8>,
}

impl Decoder {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn push(&mut self, bytes: &[u8]) {
        self.buffer.extend_from_slice(bytes);
    }

    /// Takes the next complete message, or `None` while one is still partial.
    /// An error leaves the buffer untouched: the caller is expected to drop the
    /// connection rather than resynchronize.
    pub fn next(&mut self) -> Result<Option<(Message, Option<Sample>)>, ProtocolError> {
        if self.buffer.len() < HEADER_SIZE {
            return Ok(None);
        }
        let message_type = self.buffer[0];
        let payload_length = u32::from_le_bytes([
            self.buffer[1],
            self.buffer[2],
            self.buffer[3],
            self.buffer[4],
        ]) as usize;
        if payload_length > MAX_PAYLOAD {
            return Err(ProtocolError::OversizedPayload(payload_length));
        }
        let end = HEADER_SIZE + payload_length;
        if self.buffer.len() < end {
            return Ok(None);
        }
        let decoded = if message_type == SRV_GAZE {
            let sample = decode_sample(&self.buffer[HEADER_SIZE..end])?;
            (Message::Gaze, Some(sample))
        } else {
            (Message::Ignored, None)
        };
        self.buffer.drain(..end);
        Ok(Some(decoded))
    }
}

fn decode_sample(payload: &[u8]) -> Result<Sample, ProtocolError> {
    if payload.len() != GAZE_SAMPLE_SIZE {
        return Err(ProtocolError::UnexpectedGazeSize(payload.len()));
    }
    let present = read_u32(payload, OFFSET_PRESENT_MASK);
    let missing = REQUIRED_BITS & !present;
    if missing != 0 {
        return Err(ProtocolError::MissingFields(missing));
    }
    let eyes_valid = read_u32(payload, OFFSET_VALIDITY_L) == VALIDITY_VALID
        && read_u32(payload, OFFSET_VALIDITY_R) == VALIDITY_VALID;
    let point = (eyes_valid && present & BIT_GAZE_2D != 0)
        .then(|| Point {
            x: read_f64(payload, OFFSET_GAZE_2D),
            y: read_f64(payload, OFFSET_GAZE_2D + 8),
        })
        .filter(on_display);
    Ok(Sample {
        frame_counter: read_u32(payload, OFFSET_FRAME_COUNTER),
        timestamp_us: read_i64(payload, OFFSET_TIMESTAMP_US),
        point,
    })
}

/// Gaze that leaves the display area, or arrives as NaN, cannot select a
/// target; `filter` on a `NaN` comparison rejects it in both directions.
fn on_display(point: &Point) -> bool {
    (0.0..=1.0).contains(&point.x) && (0.0..=1.0).contains(&point.y)
}

fn read_u32(payload: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(payload[offset..offset + 4].try_into().expect("4 bytes"))
}

fn read_i64(payload: &[u8], offset: usize) -> i64 {
    i64::from_le_bytes(payload[offset..offset + 8].try_into().expect("8 bytes"))
}

fn read_f64(payload: &[u8], offset: usize) -> f64 {
    f64::from_le_bytes(payload[offset..offset + 8].try_into().expect("8 bytes"))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Builds a gaze message the way the daemon frames one.
    fn gaze_message(sample: &[u8]) -> Vec<u8> {
        let mut message = vec![SRV_GAZE];
        message.extend_from_slice(&(sample.len() as u32).to_le_bytes());
        message.extend_from_slice(sample);
        message
    }

    /// A valid sample looking at `(x, y)`, with every required field present.
    fn sample(x: f64, y: f64) -> Vec<u8> {
        let mut payload = vec![0u8; GAZE_SAMPLE_SIZE];
        let present = REQUIRED_BITS | BIT_GAZE_2D;
        payload[OFFSET_PRESENT_MASK..][..4].copy_from_slice(&present.to_le_bytes());
        payload[OFFSET_FRAME_COUNTER..][..4].copy_from_slice(&7u32.to_le_bytes());
        payload[OFFSET_TIMESTAMP_US..][..8].copy_from_slice(&1_234_567i64.to_le_bytes());
        payload[OFFSET_GAZE_2D..][..8].copy_from_slice(&x.to_le_bytes());
        payload[OFFSET_GAZE_2D + 8..][..8].copy_from_slice(&y.to_le_bytes());
        payload
    }

    fn eye_not_detected(payload: &mut [u8], offset: usize) {
        payload[offset..][..4].copy_from_slice(&4u32.to_le_bytes());
    }

    fn decode_one(bytes: &[u8]) -> Result<Option<(Message, Option<Sample>)>, ProtocolError> {
        let mut decoder = Decoder::new();
        decoder.push(bytes);
        decoder.next()
    }

    #[test]
    fn subscribe_asks_for_the_gaze_stream() {
        let command = subscribe_command();
        assert_eq!(command[0], CMD_SUBSCRIBE);
        assert_eq!(u32::from_le_bytes(command[1..5].try_into().unwrap()), 4);
        assert_eq!(
            u32::from_le_bytes(command[5..9].try_into().unwrap()),
            STREAM_GAZE
        );
        assert_eq!(disconnect_command()[0], CMD_DISCONNECT);
    }

    #[test]
    fn decodes_a_gaze_sample() {
        let decoded = decode_one(&gaze_message(&sample(0.25, 0.75)))
            .expect("valid sample")
            .expect("complete message");
        assert_eq!(
            decoded,
            (
                Message::Gaze,
                Some(Sample {
                    frame_counter: 7,
                    timestamp_us: 1_234_567,
                    point: Some(Point { x: 0.25, y: 0.75 }),
                })
            )
        );
    }

    #[test]
    fn reassembles_messages_split_across_reads() {
        let stream = gaze_message(&sample(0.5, 0.5));
        let mut decoder = Decoder::new();
        // Header arrives first, then the payload one byte short, then the rest.
        decoder.push(&stream[..3]);
        assert_eq!(decoder.next().unwrap(), None);
        decoder.push(&stream[3..stream.len() - 1]);
        assert_eq!(decoder.next().unwrap(), None);
        decoder.push(&stream[stream.len() - 1..]);
        assert!(matches!(decoder.next().unwrap(), Some((Message::Gaze, _))));
        assert_eq!(decoder.next().unwrap(), None);
    }

    #[test]
    fn reads_coalesced_messages_in_order() {
        let mut stream = gaze_message(&sample(0.1, 0.1));
        stream.extend(gaze_message(&sample(0.9, 0.9)));
        let mut decoder = Decoder::new();
        decoder.push(&stream);

        let first = decoder.next().unwrap().unwrap().1.unwrap();
        let second = decoder.next().unwrap().unwrap().1.unwrap();
        assert_eq!(first.point, Some(Point { x: 0.1, y: 0.1 }));
        assert_eq!(second.point, Some(Point { x: 0.9, y: 0.9 }));
        assert_eq!(decoder.next().unwrap(), None);
    }

    #[test]
    fn skips_message_types_the_first_slice_ignores() {
        // A display-area message, then gaze: the unknown one is stepped over.
        let mut stream = vec![0x03, 8, 0, 0, 0];
        stream.extend_from_slice(&[0u8; 8]);
        stream.extend(gaze_message(&sample(0.5, 0.5)));
        let mut decoder = Decoder::new();
        decoder.push(&stream);

        assert_eq!(decoder.next().unwrap(), Some((Message::Ignored, None)));
        assert!(matches!(decoder.next().unwrap(), Some((Message::Gaze, _))));
    }

    #[test]
    fn rejects_a_gaze_payload_of_another_size() {
        // The size upstream's architecture document still describes.
        let stale = vec![0u8; 232];
        assert_eq!(
            decode_one(&gaze_message(&stale)),
            Err(ProtocolError::UnexpectedGazeSize(232))
        );
    }

    #[test]
    fn rejects_a_sample_missing_a_field_selection_needs() {
        let mut payload = sample(0.5, 0.5);
        let without_validity = (REQUIRED_BITS | BIT_GAZE_2D) & !BIT_VALIDITY_R;
        payload[OFFSET_PRESENT_MASK..][..4].copy_from_slice(&without_validity.to_le_bytes());
        assert_eq!(
            decode_one(&gaze_message(&payload)),
            Err(ProtocolError::MissingFields(BIT_VALIDITY_R))
        );
    }

    #[test]
    fn rejects_an_oversized_payload_without_buffering_it() {
        let header = [0x01, 0xFF, 0xFF, 0xFF, 0xFF];
        assert_eq!(
            decode_one(&header),
            Err(ProtocolError::OversizedPayload(u32::MAX as usize))
        );
    }

    #[test]
    fn an_undetected_eye_reports_no_position() {
        let mut payload = sample(0.5, 0.5);
        eye_not_detected(&mut payload, OFFSET_VALIDITY_L);
        let decoded = decode_one(&gaze_message(&payload))
            .unwrap()
            .unwrap()
            .1
            .unwrap();
        assert_eq!(decoded.point, None);
        // The frame is still well formed, so timing stays available.
        assert_eq!(decoded.timestamp_us, 1_234_567);
    }

    #[test]
    fn an_absent_gaze_field_reports_no_position() {
        let mut payload = sample(0.5, 0.5);
        payload[OFFSET_PRESENT_MASK..][..4].copy_from_slice(&REQUIRED_BITS.to_le_bytes());
        let decoded = decode_one(&gaze_message(&payload))
            .unwrap()
            .unwrap()
            .1
            .unwrap();
        assert_eq!(decoded.point, None);
    }

    #[test]
    fn gaze_outside_the_display_reports_no_position() {
        for (x, y) in [(-0.01, 0.5), (0.5, 1.01), (f64::NAN, 0.5)] {
            let decoded = decode_one(&gaze_message(&sample(x, y)))
                .unwrap()
                .unwrap()
                .1
                .unwrap();
            assert_eq!(decoded.point, None, "accepted gaze at ({x}, {y})");
        }
        // The edges of the display area are still on it.
        for (x, y) in [(0.0, 0.0), (1.0, 1.0)] {
            let decoded = decode_one(&gaze_message(&sample(x, y)))
                .unwrap()
                .unwrap()
                .1
                .unwrap();
            assert_eq!(decoded.point, Some(Point { x, y }));
        }
    }
}

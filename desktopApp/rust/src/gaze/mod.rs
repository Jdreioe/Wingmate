//! Native gaze input from the `tobiifreed` daemon (issue #129).
//!
//! The daemon owns the tracker's USB device and broadcasts samples over a Unix
//! socket, so Wingmate reads gaze without touching libusb or linking the
//! driver. This module is the transport half of that path: it connects,
//! subscribes, and turns the byte stream into [`Sample`]s. Mapping a sample to
//! a communication target, and everything about dwell and activation, stays
//! out of here — see `docs/GAZE_TD_I13.md`.

#[cfg(unix)]
pub mod probe;
pub mod protocol;
pub mod runner;
pub mod setup;
pub mod targets;

use protocol::{Decoder, Message, ProtocolError, Sample};
use std::time::Duration;

/// What the user is told about the gaze source. Only one state is reported at
/// a time, and every state except [`Status::Disabled`] is recoverable without
/// restarting Wingmate.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Status {
    /// Native gaze is switched off.
    #[default]
    Disabled,
    Connecting,
    /// Connected and receiving usable gaze.
    Connected,
    /// Connected, but the eyes are not currently tracked.
    GazeLost,
    /// No daemon to connect to; Wingmate keeps retrying.
    DaemonUnavailable,
    /// The daemon speaks a protocol this build does not understand.
    IncompatibleProtocol,
}

#[derive(Debug)]
pub enum ConnectError {
    /// The socket is missing or refusing connections: the daemon is not running.
    Unavailable,
    Io(std::io::Error),
}

#[derive(Debug)]
pub enum ReadError {
    /// The daemon closed the connection or went away.
    Disconnected,
    Protocol(ProtocolError),
}

impl ReadError {
    pub fn status(&self) -> Status {
        match self {
            Self::Disconnected => Status::DaemonUnavailable,
            Self::Protocol(_) => Status::IncompatibleProtocol,
        }
    }
}

/// Reconnect delay after a failed or lost connection. A tracker that is simply
/// switched off must not turn into a busy loop, and a user who plugs one in
/// should not wait long for it.
#[derive(Debug)]
pub struct Backoff {
    delay: Duration,
}

impl Backoff {
    const FIRST: Duration = Duration::from_millis(250);
    const LIMIT: Duration = Duration::from_secs(5);

    pub fn new() -> Self {
        Self { delay: Self::FIRST }
    }

    /// The delay to wait before the next attempt, doubling up to [`Self::LIMIT`].
    pub fn next_delay(&mut self) -> Duration {
        let delay = self.delay;
        self.delay = (delay * 2).min(Self::LIMIT);
        delay
    }

    /// Called once a connection produces gaze, so the next outage retries fast.
    pub fn reset(&mut self) {
        self.delay = Self::FIRST;
    }
}

impl Default for Backoff {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(unix)]
pub mod client {
    use super::{ConnectError, Decoder, Message, ReadError, Sample, protocol};
    use std::io::{ErrorKind, Read, Write};
    use std::os::unix::net::UnixStream;
    use std::path::PathBuf;

    /// Where `tobiifreed` listens. Matches the daemon's own fallback when
    /// `XDG_RUNTIME_DIR` is unset.
    pub fn socket_path() -> PathBuf {
        let runtime_directory =
            std::env::var_os("XDG_RUNTIME_DIR").unwrap_or_else(|| "/tmp".into());
        PathBuf::from(runtime_directory)
            .join("tobiifreed")
            .join("gaze.sock")
    }

    /// A subscribed connection to the daemon. Reads block, so this is meant to
    /// be driven from its own thread.
    pub struct Client {
        stream: UnixStream,
        decoder: Decoder,
        buffer: [u8; 8192],
    }

    impl Client {
        /// Connects and subscribes to the gaze stream. The daemon serves
        /// several clients, so this never takes the tracker away from the
        /// user's other gaze tools.
        pub fn connect() -> Result<Self, ConnectError> {
            Self::connect_at(&socket_path())
        }

        /// Connects to a specific socket. `connect` is the path users take;
        /// this exists so tests can drive a daemon of their own.
        pub fn connect_at(path: &std::path::Path) -> Result<Self, ConnectError> {
            let mut stream = UnixStream::connect(path).map_err(|error| match error.kind() {
                ErrorKind::NotFound | ErrorKind::ConnectionRefused => ConnectError::Unavailable,
                _ => ConnectError::Io(error),
            })?;
            stream
                .write_all(&protocol::subscribe_command())
                .map_err(ConnectError::Io)?;
            Ok(Self {
                stream,
                decoder: Decoder::new(),
                buffer: [0; 8192],
            })
        }

        /// Blocks until the next gaze sample arrives. Other message types are
        /// skipped; a malformed one ends the connection.
        pub fn next_sample(&mut self) -> Result<Sample, ReadError> {
            loop {
                match self.decoder.next().map_err(ReadError::Protocol)? {
                    Some((Message::Gaze, Some(sample))) => return Ok(sample),
                    Some(_) => continue,
                    None => {
                        let read = self
                            .stream
                            .read(&mut self.buffer)
                            .map_err(|_| ReadError::Disconnected)?;
                        if read == 0 {
                            return Err(ReadError::Disconnected);
                        }
                        self.decoder.push(&self.buffer[..read]);
                    }
                }
            }
        }
    }

    impl Drop for Client {
        fn drop(&mut self) {
            // Best effort: tell the daemon to free the client slot.
            let _ = self.stream.write_all(&protocol::disconnect_command());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(unix)]
    mod client {
        use crate::gaze::client::Client;
        use crate::gaze::protocol::{GAZE_SAMPLE_SIZE, Point, subscribe_command};
        use std::io::{Read, Write};
        use std::os::unix::net::UnixListener;

        /// One framed gaze message carrying a valid sample at `(x, y)`.
        fn gaze_message(x: f64, y: f64) -> Vec<u8> {
            let mut payload = vec![0u8; GAZE_SAMPLE_SIZE];
            // present mask: timestamp, frame counter, both validities, gaze 2D.
            payload[0..4].copy_from_slice(&0b100_1111u32.to_le_bytes());
            payload[4..8].copy_from_slice(&3u32.to_le_bytes());
            payload[40..48].copy_from_slice(&x.to_le_bytes());
            payload[48..56].copy_from_slice(&y.to_le_bytes());

            let mut message = vec![0x01];
            message.extend_from_slice(&(GAZE_SAMPLE_SIZE as u32).to_le_bytes());
            message.extend(payload);
            message
        }

        /// Stands in for `tobiifreed`: accepts one client, checks that it
        /// subscribes, then writes two samples split across several writes.
        #[test]
        fn subscribes_and_reads_samples_from_a_daemon() {
            let directory = tempfile::tempdir().expect("temporary directory");
            let path = directory.path().join("gaze.sock");
            let listener = UnixListener::bind(&path).expect("bind");

            let daemon = std::thread::spawn(move || {
                let (mut stream, _) = listener.accept().expect("accept");
                let mut subscribe = [0u8; 9];
                stream
                    .read_exact(&mut subscribe)
                    .expect("subscribe command");
                assert_eq!(subscribe, subscribe_command());

                let mut bytes = gaze_message(0.25, 0.75);
                bytes.extend(gaze_message(0.5, 0.5));
                let split = bytes.len() / 3;
                for chunk in bytes.chunks(split) {
                    stream.write_all(chunk).expect("write");
                }
                // Hold the connection open until the client has read both.
                std::thread::sleep(std::time::Duration::from_millis(200));
            });

            let mut client = Client::connect_at(&path).expect("connect");
            assert_eq!(
                client.next_sample().expect("first sample").point,
                Some(Point { x: 0.25, y: 0.75 })
            );
            assert_eq!(
                client.next_sample().expect("second sample").point,
                Some(Point { x: 0.5, y: 0.5 })
            );
            daemon.join().expect("daemon thread");
        }

        #[test]
        fn reports_a_missing_daemon_as_unavailable() {
            let directory = tempfile::tempdir().expect("temporary directory");
            let missing = directory.path().join("gaze.sock");
            assert!(matches!(
                Client::connect_at(&missing),
                Err(crate::gaze::ConnectError::Unavailable)
            ));
        }
    }

    #[test]
    fn backoff_grows_to_a_bounded_delay_and_resets() {
        let mut backoff = Backoff::new();
        assert_eq!(backoff.next_delay(), Duration::from_millis(250));
        assert_eq!(backoff.next_delay(), Duration::from_millis(500));
        assert_eq!(backoff.next_delay(), Duration::from_secs(1));
        for _ in 0..10 {
            backoff.next_delay();
        }
        assert_eq!(backoff.next_delay(), Duration::from_secs(5));

        backoff.reset();
        assert_eq!(backoff.next_delay(), Duration::from_millis(250));
    }

    #[test]
    fn read_failures_map_to_recoverable_states() {
        assert_eq!(ReadError::Disconnected.status(), Status::DaemonUnavailable);
        assert_eq!(
            ReadError::Protocol(ProtocolError::UnexpectedGazeSize(232)).status(),
            Status::IncompatibleProtocol
        );
    }
}

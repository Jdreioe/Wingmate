//! Native gaze input from the `tobiifreed` daemon (issue #129).
//!
//! The daemon owns the tracker's USB device and broadcasts samples over a Unix
//! socket, so Wingmate reads gaze without touching libusb or linking the
//! driver. This module is the transport half of that path: it connects,
//! subscribes, and turns the byte stream into [`Sample`]s. Mapping a sample to
//! a communication target, and everything about dwell and activation, stays
//! out of here — see `docs/GAZE_TD_I13.md`.

pub mod protocol;

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
            let mut stream =
                UnixStream::connect(socket_path()).map_err(|error| match error.kind() {
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

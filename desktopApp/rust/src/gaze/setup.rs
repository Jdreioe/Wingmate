//! Linux discovery and ownership of the optional bundled daemon. Samples never persist.
use super::runner::Source;
use std::process::Child;
#[cfg(any(target_os = "linux", test))]
use std::{path::Path, process::Command};
#[cfg(target_os = "linux")]
use std::{path::PathBuf, process::Stdio};

#[derive(Default)]
pub struct Setup {
    pub tracker: bool,
    pub cameras: Vec<super::webcam::Camera>,
    pub camera: Option<super::webcam::Camera>,
    pub use_webcam: bool,
    pub reachable: bool,
    pub autostart: bool,
    pub diagnostics: Option<Source>,
    pub error: Option<&'static str>,
    child: Option<Child>,
    lock: Option<std::fs::File>,
}
impl Setup {
    pub fn refresh(&mut self) {
        #[cfg(target_os = "linux")]
        {
            self.cameras = super::webcam::cameras();
            self.tracker = detect(Path::new("/sys/bus/usb/devices"));
            self.reachable =
                std::os::unix::net::UnixStream::connect(super::client::socket_path()).is_ok();
            if let Some(child) = self.child.as_mut()
                && !matches!(child.try_wait(), Ok(None))
            {
                self.child = None;
                self.lock = None;
                self.error = Some(
                    "The gaze daemon stopped. Check USB access and reconnect the tracker, then retry.",
                );
            }
        }
    }
    pub fn visible(&self) -> bool {
        cfg!(target_os = "linux")
    }
    pub fn start(&mut self) {
        #[cfg(target_os = "linux")]
        {
            self.refresh();
            let result = self.start_at(
                &crate::data_directory(),
                &super::client::socket_path(),
                &bundled_path(),
            );
            self.error = result.err().map(|_| "Could not start the bundled gaze daemon. Check the AppImage installation or whether another Wingmate is starting it.");
        }
    }
    #[cfg(target_os = "linux")]
    fn start_at(&mut self, directory: &Path, socket: &Path, binary: &Path) -> std::io::Result<()> {
        let reachable = || std::os::unix::net::UnixStream::connect(socket).is_ok();
        if self.child.is_some() || reachable() {
            return Ok(());
        }
        std::fs::create_dir_all(directory)?;
        let lock = std::fs::File::options()
            .create(true)
            .truncate(false)
            .write(true)
            .open(directory.join("gaze-daemon.lock"))?;
        lock.try_lock().map_err(std::io::Error::other)?;
        // Recheck under the cross-instance lock before claiming USB.
        if reachable() {
            return Ok(());
        }
        self.child = Some(
            Command::new(binary)
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()?,
        );
        self.lock = Some(lock);
        Ok(())
    }
    pub fn stop(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
        self.lock = None;
    }
    pub fn load(&mut self) {
        self.autostart = std::fs::read(crate::data_directory().join("gaze-autostart"))
            .is_ok_and(|v| v == b"true");
        self.refresh();
        if self.autostart {
            self.start();
        }
    }
    pub fn set_autostart(&mut self, enabled: bool) {
        let directory = crate::data_directory();
        if std::fs::create_dir_all(&directory)
            .and_then(|_| {
                std::fs::write(
                    directory.join("gaze-autostart"),
                    if enabled { "true" } else { "false" },
                )
            })
            .is_err()
        {
            self.error = Some("Could not save the gaze startup preference.");
            return;
        }
        self.autostart = enabled;
        if enabled {
            self.start();
        } else {
            self.stop();
        }
    }
}
impl Drop for Setup {
    fn drop(&mut self) {
        self.stop();
    }
}
#[cfg(target_os = "linux")]
fn bundled_path() -> PathBuf {
    std::env::current_exe()
        .unwrap_or_default()
        .with_file_name("tobiifreed")
}
#[cfg(any(target_os = "linux", test))]
fn detect(root: &Path) -> bool {
    std::fs::read_dir(root).is_ok_and(|entries| {
        entries.flatten().any(|entry| {
            let read = |name| {
                std::fs::read_to_string(entry.path().join(name))
                    .unwrap_or_default()
                    .trim()
                    .to_ascii_lowercase()
            };
            read("idVendor") == "2104" && matches!(read("idProduct").as_str(), "0313" | "031e")
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn discovery_only_accepts_runtime_tobii_devices() {
        let root = tempfile::tempdir().unwrap();
        let device = root.path().join("1-1");
        std::fs::create_dir(&device).unwrap();
        std::fs::write(device.join("idVendor"), "2104\n").unwrap();
        for (id, expected) in [
            ("0102", false),
            ("031e\n", true),
            ("0313", true),
            ("ffff", false),
        ] {
            std::fs::write(device.join("idProduct"), id).unwrap();
            assert_eq!(detect(root.path()), expected);
        }
        std::fs::write(device.join("idVendor"), "0000").unwrap();
        assert!(!detect(root.path()));
    }
    #[cfg(target_os = "linux")]
    #[test]
    fn existing_socket_is_reused_and_concurrent_start_is_blocked() {
        let directory = tempfile::tempdir().unwrap();
        let socket = directory.path().join("gaze.sock");
        let listener = std::os::unix::net::UnixListener::bind(&socket).unwrap();
        let mut setup = Setup::default();
        let missing_binary = directory.path().join("absent");
        setup
            .start_at(directory.path(), &socket, &missing_binary)
            .unwrap();
        assert!(setup.child.is_none());
        setup.stop();
        assert!(std::os::unix::net::UnixStream::connect(&socket).is_ok());
        drop(listener);
        std::fs::remove_file(&socket).unwrap();
        let lock = std::fs::File::create(directory.path().join("gaze-daemon.lock")).unwrap();
        lock.lock().unwrap();
        let error = setup
            .start_at(directory.path(), &socket, &missing_binary)
            .unwrap_err();
        assert_ne!(error.kind(), std::io::ErrorKind::NotFound);
        assert!(setup.child.is_none());
        drop(lock);
        assert_eq!(
            setup
                .start_at(directory.path(), &socket, &missing_binary)
                .unwrap_err()
                .kind(),
            std::io::ErrorKind::NotFound
        );
    }
    #[cfg(unix)]
    #[test]
    fn stopping_only_reaps_owned_child() {
        let mut setup = Setup::default();
        setup.child = Some(Command::new("sleep").arg("30").spawn().unwrap());
        setup.stop();
        assert!(setup.child.is_none());
    }
}

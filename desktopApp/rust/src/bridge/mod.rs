use crate::access::{Event, State};
use crate::models::{Activation, BoardSet, Pronunciation, Settings};
use serde::de::DeserializeOwned;
use std::{
    ffi::{CStr, CString, c_char, c_void},
    ptr::NonNull,
};

unsafe extern "C" {
    fn wm_access_target_entered_json(
        context: *mut c_void,
        target: *const c_char,
        now: i64,
    ) -> *mut c_char;
    fn wm_access_target_exited_json(
        context: *mut c_void,
        target: *const c_char,
        now: i64,
    ) -> *mut c_char;
    fn wm_access_clear_transient_input_json(context: *mut c_void, now: i64) -> *mut c_char;
    fn wm_access_tick_json(context: *mut c_void, now: i64) -> *mut c_char;
    fn wm_access_set_paused_json(context: *mut c_void, paused: i32, now: i64) -> *mut c_char;
    fn wm_access_key_down_json(context: *mut c_void, key: *const c_char, now: i64) -> *mut c_char;
    fn wm_access_key_up_json(context: *mut c_void, key: *const c_char, now: i64) -> *mut c_char;

    fn wm_editor_json(context: *mut c_void, value: *const c_char) -> *mut c_char;
    fn wm_create(data_directory: *const c_char) -> *mut c_void;
    fn wm_destroy(context: *mut c_void);
    fn wm_string_free(value: *mut c_char);
    fn wm_library_json(context: *mut c_void) -> *mut c_char;
    fn wm_recents_json(context: *mut c_void) -> *mut c_char;
    fn wm_import_file_json(context: *mut c_void, path: *const c_char) -> *mut c_char;
    fn wm_open_json(context: *mut c_void, id: *const c_char) -> *mut c_char;
    fn wm_activate_json(context: *mut c_void, id: *const c_char) -> *mut c_char;
    fn wm_back_json(context: *mut c_void) -> *mut c_char;
    fn wm_clear_json(context: *mut c_void) -> *mut c_char;
    fn wm_hold_json(context: *mut c_void) -> *mut c_char;
    fn wm_speak_json(context: *mut c_void) -> *mut c_char;
    fn wm_settings_json(context: *mut c_void) -> *mut c_char;
    fn wm_update_settings_json(context: *mut c_void, value: *const c_char) -> *mut c_char;
    fn wm_pronunciations_json(context: *mut c_void) -> *mut c_char;
    fn wm_add_pronunciation_json(context: *mut c_void, value: *const c_char) -> *mut c_char;
    fn wm_delete_pronunciation_json(context: *mut c_void, word: *const c_char) -> *mut c_char;
    fn wm_export_backup_json(context: *mut c_void, path: *const c_char) -> *mut c_char;
    fn wm_restore_backup_json(context: *mut c_void, path: *const c_char) -> *mut c_char;
}

pub struct NativeCore {
    context: NonNull<c_void>,
}

/// Why a Kotlin core call failed; mirrors `DesktopFailureKind` in the bindings.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum FailureKind {
    /// The user stopped the operation; nothing to report.
    Cancelled,
    Validation,
    NotFound,
    Persistence,
    Unexpected,
}

/// A failed core call. `message` is a fixed sentence safe to show and log.
#[derive(Debug, Clone, PartialEq, Eq, serde::Deserialize)]
pub struct CoreError {
    pub kind: FailureKind,
    pub message: String,
    pub retryable: bool,
}

impl CoreError {
    fn new(kind: FailureKind, message: &str) -> Self {
        Self {
            kind,
            message: message.into(),
            retryable: false,
        }
    }
    /// The core answered with JSON this build does not understand.
    pub fn invalid_response() -> Self {
        Self::new(
            FailureKind::Unexpected,
            "Wingmate core returned an invalid response",
        )
    }
}

impl std::fmt::Display for CoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)?;
        if self.retryable {
            f.write_str(". Try again.")?;
        }
        Ok(())
    }
}

/// Every `wm_*_json` call answers `{"ok": value}` or `{"error": CoreError}`.
#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
enum Reply<T> {
    Ok(T),
    Error(CoreError),
}

fn decode_reply<T: DeserializeOwned>(json: &str) -> Result<T, CoreError> {
    match serde_json::from_str(json) {
        Ok(Reply::Ok(value)) => Ok(value),
        Ok(Reply::Error(error)) => Err(error),
        Err(_) => Err(CoreError::invalid_response()),
    }
}

fn c_string(value: &str) -> Result<CString, CoreError> {
    CString::new(value).map_err(|_| {
        CoreError::new(
            FailureKind::Validation,
            "Text cannot contain a null character",
        )
    })
}

fn encode(value: &impl serde::Serialize) -> Result<String, CoreError> {
    serde_json::to_string(value)
        .map_err(|_| CoreError::new(FailureKind::Unexpected, "Could not prepare the request"))
}

impl NativeCore {
    pub fn new(data_directory: &str) -> Result<Self, CoreError> {
        let path = c_string(data_directory)?;
        let context = unsafe { NonNull::new(wm_create(path.as_ptr())) }.ok_or_else(|| {
            CoreError::new(FailureKind::Unexpected, "Kotlin core initialization failed")
        })?;
        Ok(Self { context })
    }
    fn read<T: DeserializeOwned>(
        &self,
        call: unsafe extern "C" fn(*mut c_void) -> *mut c_char,
    ) -> Result<T, CoreError> {
        self.decode(unsafe { call(self.context.as_ptr()) })
    }
    fn input<T: DeserializeOwned>(
        &self,
        input: &str,
        call: unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_char,
    ) -> Result<T, CoreError> {
        let input = c_string(input)?;
        self.decode(unsafe { call(self.context.as_ptr(), input.as_ptr()) })
    }
    fn decode<T: DeserializeOwned>(&self, value: *mut c_char) -> Result<T, CoreError> {
        let value = NonNull::new(value).ok_or_else(|| {
            CoreError::new(FailureKind::Unexpected, "Kotlin core returned no value")
        })?;
        let json = unsafe { CStr::from_ptr(value.as_ptr()) }
            .to_string_lossy()
            .into_owned();
        unsafe { wm_string_free(value.as_ptr()) };
        decode_reply(&json)
    }
}

impl NativeCore {
    pub fn access(&self, event: &Event, now: i64) -> Result<State, CoreError> {
        let context = self.context.as_ptr();
        let input = match event {
            Event::Enter(target) | Event::Exit(target) => target.id(),
            Event::KeyDown(key) | Event::KeyUp(key) => key.clone(),
            _ => String::new(),
        };
        let input = c_string(&input)?;
        self.decode(unsafe {
            match event {
                Event::Enter(_) => wm_access_target_entered_json(context, input.as_ptr(), now),
                Event::Exit(_) => wm_access_target_exited_json(context, input.as_ptr(), now),
                Event::Clear => wm_access_clear_transient_input_json(context, now),
                Event::Tick => wm_access_tick_json(context, now),
                Event::SetPaused(paused) => {
                    wm_access_set_paused_json(context, i32::from(*paused), now)
                }
                Event::KeyDown(_) => wm_access_key_down_json(context, input.as_ptr(), now),
                Event::KeyUp(_) => wm_access_key_up_json(context, input.as_ptr(), now),
            }
        })
    }

    pub fn editor(&self, value: &serde_json::Value) -> Result<serde_json::Value, CoreError> {
        self.input(&value.to_string(), wm_editor_json)
    }
    pub fn library(&self) -> Result<Vec<BoardSet>, CoreError> {
        self.read(wm_library_json)
    }
    pub fn recents(&self) -> Result<Vec<String>, CoreError> {
        self.read(wm_recents_json)
    }
    pub fn import_file(&self, path: &str) -> Result<Activation, CoreError> {
        self.input(path, wm_import_file_json)
    }
    pub fn open(&self, id: &str) -> Result<Activation, CoreError> {
        self.input(id, wm_open_json)
    }
    pub fn activate(&self, id: &str) -> Result<Activation, CoreError> {
        self.input(id, wm_activate_json)
    }
    pub fn back(&self) -> Result<Activation, CoreError> {
        self.read(wm_back_json)
    }
    pub fn clear(&self) -> Result<Activation, CoreError> {
        self.read(wm_clear_json)
    }
    pub fn hold(&self) -> Result<Activation, CoreError> {
        self.read(wm_hold_json)
    }
    pub fn speak(&self) -> Result<Activation, CoreError> {
        self.read(wm_speak_json)
    }
    pub fn settings(&self) -> Result<Settings, CoreError> {
        self.read(wm_settings_json)
    }
    pub fn update_settings(&self, value: &Settings) -> Result<Settings, CoreError> {
        self.input(&encode(value)?, wm_update_settings_json)
    }
    pub fn pronunciations(&self) -> Result<Vec<Pronunciation>, CoreError> {
        self.read(wm_pronunciations_json)
    }
    pub fn add_pronunciation(
        &self,
        value: &Pronunciation,
    ) -> Result<Vec<Pronunciation>, CoreError> {
        self.input(&encode(value)?, wm_add_pronunciation_json)
    }
    pub fn delete_pronunciation(&self, word: &str) -> Result<Vec<Pronunciation>, CoreError> {
        self.input(word, wm_delete_pronunciation_json)
    }
    pub fn export_backup(&self, path: &str) -> Result<(), CoreError> {
        self.input(path, wm_export_backup_json)
    }
    pub fn restore_backup(&self, path: &str) -> Result<(), CoreError> {
        self.input(path, wm_restore_backup_json)
    }
}

impl Drop for NativeCore {
    fn drop(&mut self) {
        unsafe { wm_destroy(self.context.as_ptr()) }
    }
}

#[cfg(test)]
mod tests {
    use super::{CoreError, FailureKind, NativeCore, decode_reply};

    #[test]
    fn replies_decode_into_values_or_typed_errors() {
        assert_eq!(
            decode_reply::<Vec<String>>(r#"{"ok":["a"]}"#),
            Ok(vec!["a".into()])
        );
        assert_eq!(decode_reply::<()>(r#"{"ok":null}"#), Ok(()));
        let error = decode_reply::<()>(
            r#"{"error":{"kind":"persistence","message":"Could not save","retryable":true}}"#,
        )
        .unwrap_err();
        assert_eq!(error.kind, FailureKind::Persistence);
        assert_eq!(error.to_string(), "Could not save. Try again.");
        // The old string-only shape is no longer a valid reply.
        let legacy = decode_reply::<()>(r#"{"error":"raw exception text"}"#).unwrap_err();
        assert_eq!(legacy.kind, FailureKind::Unexpected);
        assert!(!legacy.message.contains("raw"));
    }

    #[test]
    fn failures_keep_their_kind_across_the_real_c_bridge() {
        let directory = tempfile::tempdir().unwrap();
        let core = NativeCore::new(directory.path().to_str().unwrap()).unwrap();
        let kind = |result: Result<_, CoreError>| result.map(|_| ()).unwrap_err().kind;
        assert_eq!(kind(core.open("missing")), FailureKind::NotFound);
        assert_eq!(
            kind(core.import_file("/missing/board.obf")),
            FailureKind::NotFound
        );
        assert_eq!(kind(core.open("nul\0")), FailureKind::Validation);
        core.export_backup(
            directory
                .path()
                .join("backup.wingmate-backup")
                .to_str()
                .unwrap(),
        )
        .unwrap();
    }

    #[test]
    fn safe_wrapper_calls_embedded_kotlin_core() {
        let directory = tempfile::tempdir().unwrap();
        let core = NativeCore::new(directory.path().to_str().unwrap()).unwrap();
        assert!(core.library().unwrap().is_empty());
        assert_eq!(core.settings().unwrap().speech_rate, 1.0);
    }
    #[test]
    fn editor_draft_round_trips_through_native_boundary() {
        let directory = tempfile::tempdir().unwrap();
        let core = NativeCore::new(directory.path().to_str().unwrap()).unwrap();
        let draft = core
            .editor(&serde_json::json!({"operation":"new", "name":"Test Screen"}))
            .unwrap();
        let _: crate::editor::View = serde_json::from_value(draft).unwrap();
        assert!(core.library().unwrap().is_empty());
        core.editor(&serde_json::json!({"operation":"button", "label":"Hello"}))
            .unwrap();
        assert_eq!(
            core.editor(&serde_json::json!({"operation":"span", "rowSpan":100}))
                .unwrap_err()
                .kind,
            FailureKind::Validation
        );
        let saved = core
            .editor(&serde_json::json!({"operation":"save"}))
            .unwrap();
        let id = saved["id"].as_str().unwrap();
        let opened = core.open(id).unwrap();
        assert_eq!(opened.view.cells[0].label, "Hello");
        core.editor(&serde_json::json!({"operation":"begin", "id":id}))
            .unwrap();
        core.editor(&serde_json::json!({"operation":"renameScreen", "name":"Discard"}))
            .unwrap();
        core.editor(&serde_json::json!({"operation":"discard"}))
            .unwrap();
        assert_eq!(core.library().unwrap()[0].name, "Test Screen");
    }
}

#[cfg(test)]
mod access_tests {
    use super::NativeCore;
    use crate::access::{Effect, Event, Target};

    #[test]
    fn dwell_settings_and_cancellation_cross_the_real_c_bridge() {
        let directory = tempfile::tempdir().unwrap();
        let core = NativeCore::new(directory.path().to_str().unwrap()).unwrap();
        let mut settings = core.settings().unwrap();
        settings.dwell_to_select_millis = 500;
        settings.dwell_rearm_delay_millis = 120;
        core.update_settings(&settings).unwrap();
        let target = Target::Cell {
            board_set: "set".into(),
            page: "page".into(),
            button: "quoted\" id".into(),
        };
        let entered = core.access(&Event::Enter(target.clone()), 1000).unwrap();
        assert_eq!(entered.current_target_id, Some(target.id()));
        assert_eq!(core.access(&Event::Tick, 1100).unwrap().dwell_progress, 0.0);
        assert_eq!(core.access(&Event::Tick, 1370).unwrap().dwell_progress, 0.5);
        assert!(matches!(core.access(&Event::Tick, 1620).unwrap().effect,
            Some(Effect::Activate { target_id }) if target_id == target.id()));
        assert!(core.access(&Event::Tick, 3000).unwrap().effect.is_none());
        core.access(&Event::Exit(target.clone()), 3001).unwrap();
        core.access(&Event::Enter(target.clone()), 4000).unwrap();
        core.access(&Event::Clear, 4500).unwrap();
        let lost = core.access(&Event::Tick, 5000).unwrap();
        assert!(lost.current_target_id.is_none());
        assert!(lost.effect.is_none());
        core.access(&Event::Enter(target), 6000).unwrap();
        assert!(core.access(&Event::Tick, 6619).unwrap().effect.is_none());
        assert!(matches!(
            core.access(&Event::Tick, 6620).unwrap().effect,
            Some(Effect::Activate { .. })
        ));
        drop(core);
        let reopened = NativeCore::new(directory.path().to_str().unwrap()).unwrap();
        assert_eq!(reopened.settings().unwrap().dwell_rearm_delay_millis, 120);
        assert_eq!(reopened.settings().unwrap().dwell_to_select_millis, 500);
    }

    #[test]
    fn rest_and_select_key_effects_cross_the_real_c_bridge() {
        let directory = tempfile::tempdir().unwrap();
        let core = NativeCore::new(directory.path().to_str().unwrap()).unwrap();
        let mut settings = core.settings().unwrap();
        settings.dwell_to_select_millis = 500;
        settings.dwell_rearm_delay_millis = 0;
        settings.select_key_binding = "F8".into();
        settings.rest_mode_key_binding = "F9".into();
        core.update_settings(&settings).unwrap();
        core.access(&Event::Enter(Target::Speak), 0).unwrap();
        assert!(matches!(
            core.access(&Event::KeyDown("F8".into()), 10)
                .unwrap()
                .effect,
            Some(Effect::Activate { .. })
        ));
        assert!(
            core.access(&Event::KeyDown("F8".into()), 20)
                .unwrap()
                .effect
                .is_none()
        );
        core.access(&Event::KeyUp("F8".into()), 30).unwrap();
        assert!(core.access(&Event::Tick, 1000).unwrap().effect.is_none());
        let paused = core.access(&Event::KeyDown("F9".into()), 1100).unwrap();
        assert!(paused.is_paused);
        assert!(matches!(
            paused.effect,
            Some(Effect::PauseChanged { is_paused: true })
        ));
        assert!(core.access(&Event::Tick, 2000).unwrap().effect.is_none());
        core.access(&Event::KeyDown("F8".into()), 2100).unwrap();
        let resumed = core.access(&Event::KeyUp("F8".into()), 4100).unwrap();
        assert!(!resumed.is_paused);
        assert!(core.access(&Event::Tick, 4599).unwrap().effect.is_none());
        assert!(matches!(
            core.access(&Event::Tick, 4600).unwrap().effect,
            Some(Effect::Activate { .. })
        ));
        assert!(
            core.access(&Event::SetPaused(true), 5000)
                .unwrap()
                .is_paused
        );
        let cleared = core.access(&Event::Clear, 5001).unwrap();
        assert!(cleared.is_paused);
        assert!(cleared.current_target_id.is_none());
    }
}

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

pub trait Core {
    fn access(&self, event: &Event, now: i64) -> Result<State, String>;
    fn editor(&self, value: &serde_json::Value) -> Result<serde_json::Value, String>;
    fn library(&self) -> Result<Vec<BoardSet>, String>;
    fn recents(&self) -> Result<Vec<String>, String>;
    fn import_file(&self, path: &str) -> Result<Activation, String>;
    fn open(&self, id: &str) -> Result<Activation, String>;
    fn activate(&self, id: &str) -> Result<Activation, String>;
    fn back(&self) -> Result<Activation, String>;
    fn clear(&self) -> Result<Activation, String>;
    fn hold(&self) -> Result<Activation, String>;
    fn speak(&self) -> Result<Activation, String>;
    fn settings(&self) -> Result<Settings, String>;
    fn update_settings(&self, value: &Settings) -> Result<Settings, String>;
    fn pronunciations(&self) -> Result<Vec<Pronunciation>, String>;
    fn add_pronunciation(&self, value: &Pronunciation) -> Result<Vec<Pronunciation>, String>;
    fn delete_pronunciation(&self, word: &str) -> Result<Vec<Pronunciation>, String>;
    fn export_backup(&self, path: &str) -> Result<(), String>;
    fn restore_backup(&self, path: &str) -> Result<(), String>;
}

pub struct NativeCore {
    context: NonNull<c_void>,
}

impl NativeCore {
    pub fn new(data_directory: &str) -> Result<Self, String> {
        let path =
            CString::new(data_directory).map_err(|_| "Invalid data directory".to_string())?;
        let context = unsafe { NonNull::new(wm_create(path.as_ptr())) }
            .ok_or("Kotlin core initialization failed")?;
        Ok(Self { context })
    }
    fn read<T: DeserializeOwned>(
        &self,
        call: unsafe extern "C" fn(*mut c_void) -> *mut c_char,
    ) -> Result<T, String> {
        self.decode(unsafe { call(self.context.as_ptr()) })
    }
    fn input<T: DeserializeOwned>(
        &self,
        input: &str,
        call: unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_char,
    ) -> Result<T, String> {
        let input =
            CString::new(input).map_err(|_| "Input contains a null character".to_string())?;
        self.decode(unsafe { call(self.context.as_ptr(), input.as_ptr()) })
    }
    fn decode<T: DeserializeOwned>(&self, value: *mut c_char) -> Result<T, String> {
        let value = NonNull::new(value).ok_or("Kotlin core returned no value")?;
        let json = unsafe { CStr::from_ptr(value.as_ptr()) }
            .to_string_lossy()
            .into_owned();
        unsafe { wm_string_free(value.as_ptr()) };
        if let Ok(error) = serde_json::from_str::<BridgeError>(&json) {
            return Err(error.error);
        }
        serde_json::from_str(&json)
            .map_err(|error| format!("Invalid Kotlin core response: {error}"))
    }
    fn unit_input(
        &self,
        value: &str,
        call: unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_char,
    ) -> Result<(), String> {
        let _: serde_json::Value = self.input(value, call)?;
        Ok(())
    }
}

#[derive(serde::Deserialize)]
struct BridgeError {
    error: String,
}

impl Core for NativeCore {
    fn access(&self, event: &Event, now: i64) -> Result<State, String> {
        let context = self.context.as_ptr();
        let input = match event {
            Event::Enter(target) | Event::Exit(target) => target.id(),
            Event::KeyDown(key) | Event::KeyUp(key) => key.clone(),
            _ => String::new(),
        };
        let input = CString::new(input).map_err(|_| "Invalid access input".to_string())?;
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

    fn editor(&self, value: &serde_json::Value) -> Result<serde_json::Value, String> {
        self.input(&value.to_string(), wm_editor_json)
    }
    fn library(&self) -> Result<Vec<BoardSet>, String> {
        self.read(wm_library_json)
    }
    fn recents(&self) -> Result<Vec<String>, String> {
        self.read(wm_recents_json)
    }
    fn import_file(&self, path: &str) -> Result<Activation, String> {
        self.input(path, wm_import_file_json)
    }
    fn open(&self, id: &str) -> Result<Activation, String> {
        self.input(id, wm_open_json)
    }
    fn activate(&self, id: &str) -> Result<Activation, String> {
        self.input(id, wm_activate_json)
    }
    fn back(&self) -> Result<Activation, String> {
        self.read(wm_back_json)
    }
    fn clear(&self) -> Result<Activation, String> {
        self.read(wm_clear_json)
    }
    fn hold(&self) -> Result<Activation, String> {
        self.read(wm_hold_json)
    }
    fn speak(&self) -> Result<Activation, String> {
        self.read(wm_speak_json)
    }
    fn settings(&self) -> Result<Settings, String> {
        self.read(wm_settings_json)
    }
    fn update_settings(&self, value: &Settings) -> Result<Settings, String> {
        self.input(
            &serde_json::to_string(value).map_err(|e| e.to_string())?,
            wm_update_settings_json,
        )
    }
    fn pronunciations(&self) -> Result<Vec<Pronunciation>, String> {
        self.read(wm_pronunciations_json)
    }
    fn add_pronunciation(&self, value: &Pronunciation) -> Result<Vec<Pronunciation>, String> {
        self.input(
            &serde_json::to_string(value).map_err(|e| e.to_string())?,
            wm_add_pronunciation_json,
        )
    }
    fn delete_pronunciation(&self, word: &str) -> Result<Vec<Pronunciation>, String> {
        self.input(word, wm_delete_pronunciation_json)
    }
    fn export_backup(&self, path: &str) -> Result<(), String> {
        self.unit_input(path, wm_export_backup_json)
    }
    fn restore_backup(&self, path: &str) -> Result<(), String> {
        self.unit_input(path, wm_restore_backup_json)
    }
}

impl Drop for NativeCore {
    fn drop(&mut self) {
        unsafe { wm_destroy(self.context.as_ptr()) }
    }
}

#[cfg(test)]
mod tests {
    use super::{Core, NativeCore};

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
        assert!(
            core.editor(&serde_json::json!({"operation":"span", "rowSpan":100}))
                .is_err()
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
    use super::{Core, NativeCore};
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

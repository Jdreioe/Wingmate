//! Desktop target identities and presentation. Kotlin owns all selection timing.
use crate::Message;
use iced::{
    Element,
    widget::{button, container, mouse_area},
};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum Target {
    Cell {
        board_set: String,
        page: String,
        button: String,
    },
    Back,
    Clear,
    Hold,
    Speak,
}

impl Target {
    pub fn id(&self) -> String {
        serde_json::to_string(self).expect("target serializes")
    }
}

#[derive(Debug, Clone)]
pub enum Event {
    Enter(Target),
    Exit(Target),
    Clear,
    Tick,
    SetPaused(bool),
    KeyDown(String),
    KeyUp(String),
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct State {
    pub is_paused: bool,
    pub current_target_id: Option<String>,
    pub dwell_progress: f32,
    pub effect: Option<Effect>,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Effect {
    Activate {
        #[serde(rename = "targetId")]
        target_id: String,
    },
    PauseChanged {
        #[serde(rename = "isPaused")]
        is_paused: bool,
    },
}

pub fn area<'a>(
    control: button::Button<'a, Message>,
    target: Target,
    state: &State,
) -> Element<'a, Message> {
    let selected =
        !state.is_paused && state.current_target_id.as_deref() == Some(target.id().as_str());
    let control = if selected {
        control.style(button::success)
    } else {
        control
    };
    container(
        mouse_area(control)
            .on_enter(Message::Access(Event::Enter(target.clone())))
            .on_exit(Message::Access(Event::Exit(target.clone()))),
    )
    .id(target.id())
    .into()
}

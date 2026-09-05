use crate::Message;
use iced::{Element, widget};

/// Semantic targets: future gaze dwell and switch scanning dispatch these same
/// actions. Activation never depends on hover, pointer coordinates or dragging.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Action {
    RenameScreen,
    RenamePage,
    AddPage,
    SelectPage(String),
    StartingPage,
    ResizeGrid,
    ApplyButton,
    MoveButton,
    ResizeButton,
    RemoveButton,
    ScrollUp,
    ScrollDown,
    ScrollLeft,
    ScrollRight,
}

/// Keep hit areas large even when a label is short. Grid Cells are larger still.
pub fn button<'a>(content: impl Into<Element<'a, Message>>) -> widget::Button<'a, Message> {
    widget::button(content).padding(14).height(56)
}

use crate::{
    App, Message, Route,
    editor::{Editor, Event, View, controls::Action},
};
use iced::Task;
use serde_json::{Value, json};

impl App {
    pub(crate) fn update_editor(&mut self, event: Event) -> Task<Message> {
        match event {
            Event::Action(action) => {
                let operation = match action {
                    Action::SelectPage(id) => {
                        return self
                            .update_editor(Event::Command(json!({"operation":"page", "id":id})));
                    }
                    Action::ScrollUp
                    | Action::ScrollDown
                    | Action::ScrollLeft
                    | Action::ScrollRight => {
                        let (x, y) = match action {
                            Action::ScrollUp => (0.0, -280.0),
                            Action::ScrollDown => (0.0, 280.0),
                            Action::ScrollLeft => (-280.0, 0.0),
                            _ => (280.0, 0.0),
                        };
                        return iced::widget::operation::scroll_by(
                            "editor-body",
                            iced::widget::scrollable::AbsoluteOffset { x, y },
                        );
                    }
                    Action::RenameScreen => "renameScreen",
                    Action::RenamePage => "renamePage",
                    Action::AddPage => {
                        return self.update_editor(Event::Command(json!({"operation":"addPage"})));
                    }
                    Action::StartingPage => "root",
                    Action::ResizeGrid => "resizeGrid",
                    Action::ApplyButton => "button",
                    Action::MoveButton => "move",
                    Action::ResizeButton => "span",
                    Action::RemoveButton => "clear",
                };
                if let Some(editor) = &self.editor {
                    return self.update_editor(Event::Command(editor.command(operation)));
                }
            }
            Event::New => self.editor_command(json!({"operation":"new"})),
            Event::Begin(id) => self.editor_command(json!({"operation":"begin", "id":id})),
            Event::Command(command) => self.editor_command(command),
            Event::Select(row, column) => {
                if let Some(editor) = &mut self.editor {
                    if editor.pending.is_empty() {
                        editor.select(row, column);
                    } else {
                        self.error =
                            Some("Apply the form changes before selecting another Cell.".into());
                    }
                }
            }
            Event::Input(field, value) => {
                if let Some(editor) = &mut self.editor {
                    editor.input(field, value);
                }
            }
            Event::Hidden(value) => {
                if let Some(editor) = &mut self.editor {
                    editor.hidden = value;
                    editor.pending.insert("button".into());
                }
            }
            Event::Link(page) => {
                if let Some(editor) = &mut self.editor {
                    editor.link = page.id;
                    editor.pending.insert("button".into());
                }
            }
            Event::Save => {
                if self.editor.as_ref().is_some_and(|e| !e.pending.is_empty()) {
                    self.error = Some("Apply the form changes to the draft before saving.".into());
                    return Task::none();
                }
                match self.core.editor(&json!({"operation":"save"})) {
                    Ok(value) => {
                        self.editor = None;
                        if self.close_after_editor {
                            return iced::exit();
                        }
                        self.refresh_library();
                        if let Some(id) = value.get("id").and_then(Value::as_str) {
                            return self.apply(self.core.open(id));
                        }
                    }
                    Err(error) => self.error = Some(error),
                }
            }
            Event::Discard => {
                if let Some(editor) = &mut self.editor {
                    if editor.view.dirty || !editor.pending.is_empty() {
                        editor.confirm_discard = true;
                    } else {
                        return self.update_editor(Event::ConfirmDiscard);
                    }
                }
            }
            Event::ConfirmDiscard => match self.core.editor(&json!({"operation":"discard"})) {
                Ok(_) => {
                    self.editor = None;
                    if self.close_after_editor {
                        return iced::exit();
                    }
                    self.route = Route::Library;
                    self.refresh_library();
                    self.error = None;
                }
                Err(error) => self.error = Some(error),
            },
            Event::KeepEditing => {
                self.close_after_editor = false;
                if let Some(editor) = &mut self.editor {
                    editor.confirm_discard = false;
                }
            }
        }
        Task::none()
    }
    fn editor_command(&mut self, command: Value) {
        let operation = command["operation"].as_str().unwrap_or("");
        if self
            .editor
            .as_ref()
            .is_some_and(|e| !e.pending.is_empty() && !e.pending.contains(operation))
        {
            self.error = Some("Apply the form changes before another editor action.".into());
            return;
        }
        match self
            .core
            .editor(&command)
            .and_then(|value| serde_json::from_value::<View>(value).map_err(|e| e.to_string()))
        {
            Ok(view) => {
                if let Some(mut previous) = self.editor.take() {
                    previous.pending.remove(operation);
                    if operation == "move" {
                        let row = command["toRow"].as_u64();
                        let column = command["toColumn"].as_u64();
                        previous.selected = view
                            .cells
                            .iter()
                            .find(|c| Some(c.row as u64) == row && Some(c.column as u64) == column)
                            .cloned();
                    }
                    if previous.pending.is_empty() {
                        let selected = previous.selected.as_ref().map(|c| (c.row, c.column));
                        let same_page = previous.view.page_id == view.page_id;
                        let mut next = Editor::new(view);
                        if same_page {
                            if let Some((row, column)) = selected {
                                next.select(row, column);
                            }
                        }
                        self.editor = Some(next);
                    } else {
                        previous.view = view;
                        self.editor = Some(previous);
                    }
                } else {
                    self.editor = Some(Editor::new(view));
                }
                self.route = Route::Editor;
                self.error = None;
            }
            Err(error) => self.error = Some(error),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        bridge::{Core, NativeCore},
        editor::Field,
    };

    #[test]
    fn semantic_actions_edit_and_save_without_pointer_events() {
        let directory = tempfile::tempdir().unwrap();
        let core = NativeCore::new(directory.path().to_str().unwrap()).unwrap();
        let mut app = App {
            settings: core.settings().unwrap(),
            core: Box::new(core),
            editor: None,
            close_after_editor: false,
            route: Route::Library,
            previous_route: Route::Library,
            library: vec![],
            recents: vec![],
            board: None,
            pronunciations: vec![],
            settings_section: Default::default(),
            pronunciation_word: String::new(),
            pronunciation_replacement: String::new(),
            error: None,
            system_theme: iced::Theme::Light,
        };
        let _ = app.update_editor(Event::New);
        let _ = app.update_editor(Event::Select(0, 0));
        let _ = app.update_editor(Event::Input(Field::Label, "Hello".into()));
        // Switching targets cannot silently throw away an unapplied form.
        let _ = app.update_editor(Event::Select(1, 1));
        assert_eq!(
            app.editor.as_ref().unwrap().selected.as_ref().unwrap().row,
            0
        );
        // Save stays unavailable, and says why, until the form is applied.
        assert_eq!(
            app.editor.as_ref().unwrap().unapplied_actions(),
            Some(vec!["Apply Button"])
        );
        let _ = app.update_editor(Event::Action(Action::ApplyButton));
        assert_eq!(app.editor.as_ref().unwrap().unapplied_actions(), None);
        let _ = app.update_editor(Event::Input(Field::Row, "2".into()));
        let _ = app.update_editor(Event::Input(Field::Label, "Moved".into()));
        let _ = app.update_editor(Event::Action(Action::MoveButton));
        let _ = app.update_editor(Event::Action(Action::ApplyButton));
        let _ = app.update_editor(Event::Save);
        assert!(app.error.is_none(), "{:?}", app.error);
        let button = &app.board.as_ref().unwrap().cells[0];
        assert_eq!(button.label, "Moved");
        assert_eq!(button.row, 1);
    }
}

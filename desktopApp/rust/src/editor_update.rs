use crate::{
    App, Message, Route,
    editor::{Editor, Event, View},
};
use iced::Task;
use serde_json::{Value, json};

impl App {
    pub(crate) fn update_editor(&mut self, event: Event) -> Task<Message> {
        match event {
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

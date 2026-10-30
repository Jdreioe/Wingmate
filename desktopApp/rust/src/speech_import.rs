use crate::Message;
use iced::widget::{button, column, pick_list, row, scrollable, text, text_editor, text_input};
use iced::{Element, Fill};
use serde::Deserialize;
use serde_json::{Value, json};

#[derive(Debug, Clone, Deserialize)]
pub struct Page {
    pub name: String,
    pub paragraphs: Vec<String>,
}

#[derive(Debug, Clone)]
pub enum Event {
    Open,
    Close,
    Name(String),
    Text(text_editor::Action),
    Count(u32),
    Import,
}

pub struct Draft {
    pub name: String,
    pub content: text_editor::Content,
    pub count: u32,
    pub pages: Vec<Page>,
}

impl Default for Draft {
    fn default() -> Self {
        Self {
            name: String::new(),
            content: text_editor::Content::new(),
            count: 4,
            pages: Vec::new(),
        }
    }
}

impl Draft {
    pub fn request(&self) -> Value {
        json!({"name": self.name, "text": self.content.text(), "paragraphsPerPage": self.count})
    }

    pub fn view(&self) -> Element<'_, Message> {
        let msg = Message::SpeechImport;
        let mut import = button("Create Screen").height(48);
        if !self.name.trim().is_empty() && !self.pages.is_empty() {
            import = import.on_press(msg(Event::Import));
        }
        let header = row![
            text("Import speech").size(30),
            import,
            button("Cancel").height(48).on_press(msg(Event::Close)),
        ]
        .spacing(16);
        let mut body = column![
            text("Paste your speech. A blank line starts a new Button; remove a blank line to merge paragraphs. Markdown headings name Pages and are not spoken. Tap a Button to speak its full paragraph."),
            text("Speech name"),
            text_input("Speech name", &self.name).on_input(move |name| msg(Event::Name(name))).padding(12),
            text("Speech text"),
            text_editor(&self.content).on_action(move |action| msg(Event::Text(action))).height(240).padding(12),
            row![text("Paragraphs per Page"), pick_list((1..=8).collect::<Vec<u32>>(), Some(self.count), move |count| msg(Event::Count(count)))].spacing(12),
            text(format!("Preview · {} Pages", self.pages.len())).size(22),
        ].spacing(12);
        for (index, page) in self.pages.iter().enumerate() {
            body = body.push(text(format!("{}. {}", index + 1, page.name)).size(20));
            for paragraph in &page.paragraphs {
                body = body.push(text(paragraph));
            }
        }
        column![header, scrollable(body).height(Fill)]
            .spacing(16)
            .padding(20)
            .into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{App, Route, bridge::NativeCore};

    #[test]
    fn import_preview_and_save_use_the_shared_core_through_the_native_bridge() {
        let directory = tempfile::tempdir().unwrap();
        let core = NativeCore::new(directory.path().to_str().unwrap()).unwrap();
        let mut app = App::with_core(core);
        let _ = app.update(Message::SpeechImport(Event::Open));
        let _ = app.update(Message::SpeechImport(Event::Name("Min tale".into())));
        app.speech_import.as_mut().unwrap().content =
            text_editor::Content::with_text("Hej. Jeg hedder Jonas.\n\n## Afslutning\nTak.");
        let _ = app.update(Message::SpeechImport(Event::Count(1)));
        assert_eq!(app.speech_import.as_ref().unwrap().pages.len(), 2);
        assert!(app.core.library().unwrap().is_empty());
        let _ = app.update(Message::SpeechImport(Event::Close));
        let _ = app.update(Message::SpeechImport(Event::Open));
        assert_eq!(app.speech_import.as_ref().unwrap().pages.len(), 2);
        let _ = app.update(Message::SpeechImport(Event::Import));
        assert!(app.error.is_none(), "{:?}", app.error);
        assert_eq!(app.route, Route::Runner);
        assert!(app.speech_import.is_none());
        assert_eq!(app.core.library().unwrap().len(), 1);
        let board = app.board.as_ref().unwrap();
        let paragraph = board
            .cells
            .iter()
            .find(|cell| cell.label.starts_with("Hej."))
            .unwrap();
        let spoken = app.core.activate(&paragraph.id).unwrap();
        assert_eq!(spoken.speech.as_deref(), Some("Hej. Jeg hedder Jonas."));
        assert!(spoken.view.message.is_empty());
    }
}

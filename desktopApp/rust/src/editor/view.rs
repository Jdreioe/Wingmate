use super::controls::{Action, button};
use super::{Editor, Event, Field, Page};
use crate::Message;
use iced::widget::{checkbox, column, container, pick_list, row, scrollable, text, text_input};
use iced::{Element, Fill};

impl Editor {
    pub fn view(&self) -> Element<'_, Message> {
        let action = |label: &'static str, action: Action| {
            button(label).on_press(msg(Event::Action(action)))
        };
        if self.confirm_discard {
            return container(
                column![
                    text("Discard all unsaved changes?").size(28),
                    text("Your saved Screen will stay unchanged."),
                    button("Keep editing").on_press(msg(Event::KeepEditing)),
                    button("Discard changes").on_press(msg(Event::ConfirmDiscard)),
                ]
                .spacing(20)
                .padding(24),
            )
            .width(Fill)
            .height(Fill)
            .into();
        }
        // Saving is only possible once every form edit has been applied to the
        // draft, so the status names what is missing and Save Screen stays
        // disabled until then.
        let unapplied = self.unapplied_actions();
        let status = if let Some(actions) = &unapplied {
            format!("Apply first: {}", actions.join(", "))
        } else if self.view.dirty {
            "Unsaved changes".into()
        } else {
            "Saved".into()
        };
        let mut save = button("Save Screen");
        if unapplied.is_none() {
            save = save.on_press(msg(Event::Save));
        }
        let header = row![
            text("Screen editor").size(30),
            text(status).width(320),
            save,
            button("Discard / close").on_press(msg(Event::Discard))
        ]
        .spacing(12);
        let mut content = column![
            text("Apply each change to the draft, then Save Screen to keep it."),
            row![
                input("Screen name", &self.screen, Field::Screen),
                action("Rename Screen", Action::RenameScreen)
            ]
            .spacing(12),
            row![
                pick_list(
                    self.view.pages.clone(),
                    self.view
                        .pages
                        .iter()
                        .find(|p| p.id == self.view.page_id)
                        .cloned(),
                    |p| msg(Event::Action(Action::SelectPage(p.id)))
                )
                .padding(14)
                .text_size(20),
                button("Add Page").on_press(msg(Event::Action(Action::AddPage))),
                action("Use as starting Page", Action::StartingPage),
                text(if self.view.page_id == self.view.root_page_id {
                    "Starting Page"
                } else {
                    ""
                })
            ]
            .spacing(12),
            row![
                input("Page name", &self.page, Field::Page),
                action("Rename Page", Action::RenamePage)
            ]
            .spacing(12),
            row![
                text("Rows"),
                input("Rows", &self.rows, Field::Rows),
                text("Columns"),
                input("Columns", &self.columns, Field::Columns),
                action("Resize Grid", Action::ResizeGrid)
            ]
            .spacing(12),
        ]
        .spacing(12);
        let mut grid = column![].spacing(12);
        for r in 0..self.view.rows {
            let mut line = row![].spacing(12);
            for c in 0..self.view.columns {
                let cell = self.view.cells.iter().find(|cell| {
                    r >= cell.row
                        && r < cell.row + cell.row_span
                        && c >= cell.column
                        && c < cell.column + cell.column_span
                });
                if let Some(cell) = cell {
                    let label = if cell.occupied {
                        if cell.label.is_empty() {
                            "(Button)"
                        } else {
                            &cell.label
                        }
                    } else {
                        "Add Button"
                    };
                    let selected = self
                        .selected
                        .as_ref()
                        .is_some_and(|s| s.row == cell.row && s.column == cell.column);
                    line = line.push(
                        button(text(format!(
                            "{}{}{}",
                            if selected { "Selected: " } else { "" },
                            label,
                            if cell.hidden { " (hidden)" } else { "" }
                        )))
                        .width(120)
                        .height(88)
                        .on_press(msg(Event::Select(cell.row, cell.column))),
                    );
                }
            }
            grid = grid.push(line);
        }
        content = content.push(grid);
        for element in &self.view.unsupported_elements {
            content = content.push(text(format!(
                "Page element: {element} — preserved; editing is unavailable on desktop."
            )));
        }
        if let Some(cell) = &self.selected {
            let mut targets = vec![Page {
                id: String::new(),
                name: "No Page link".into(),
            }];
            targets.extend(self.view.pages.clone());
            let selected = targets.iter().find(|p| p.id == self.link).cloned();
            content = content.push(
                column![
                    text(format!(
                        "Button at row {}, column {}",
                        cell.row + 1,
                        cell.column + 1
                    ))
                    .size(22),
                    row![
                        text("Label"),
                        input("Label", &self.label, Field::Label),
                        text("Spoken text"),
                        input("Spoken text", &self.speech, Field::Speech)
                    ]
                    .spacing(12),
                    row![
                        text("Color"),
                        input("Background color (e.g. #ffffff)", &self.color, Field::Color),
                        checkbox(self.hidden)
                            .label("Hidden")
                            .size(48)
                            .text_size(20)
                            .on_toggle(|v| msg(Event::Hidden(v))),
                        pick_list(targets, selected, |p| msg(Event::Link(p)))
                            .padding(14)
                            .text_size(20),
                        action("Apply Button", Action::ApplyButton)
                    ]
                    .spacing(12),
                    row![
                        text("Row"),
                        input("Row", &self.row, Field::Row),
                        text("Column"),
                        input("Column", &self.column, Field::Column),
                        action("Move / swap", Action::MoveButton)
                    ]
                    .spacing(12),
                    row![
                        text("Row span"),
                        input("Row span", &self.row_span, Field::RowSpan),
                        text("Column span"),
                        input("Column span", &self.column_span, Field::ColumnSpan),
                        action("Resize Button", Action::ResizeButton),
                        action("Remove Button", Action::RemoveButton)
                    ]
                    .spacing(12),
                ]
                .spacing(10),
            );
        }
        let body = scrollable(
            container(content.padding(20)).width((self.view.columns * 132 + 40).max(920) as f32),
        )
        .id("editor-body")
        .direction(scrollable::Direction::Both {
            vertical: scrollable::Scrollbar::default(),
            horizontal: scrollable::Scrollbar::default(),
        })
        .width(Fill)
        .height(Fill);
        container(
            column![
                header,
                body,
                row![
                    action("Scroll up", Action::ScrollUp),
                    action("Scroll down", Action::ScrollDown),
                    action("Scroll left", Action::ScrollLeft),
                    action("Scroll right", Action::ScrollRight),
                ]
                .spacing(12)
            ]
            .spacing(12)
            .padding(16),
        )
        .width(Fill)
        .height(Fill)
        .into()
    }
}
fn msg(event: Event) -> Message {
    Message::Editor(event)
}
fn input<'a>(hint: &'a str, value: &'a str, field: Field) -> Element<'a, Message> {
    text_input(hint, value)
        .size(20)
        .padding(14)
        .on_input(move |s| msg(Event::Input(field, s)))
        .into()
}

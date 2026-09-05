use crate::Message;
use iced::widget::{
    button, checkbox, column, container, pick_list, row, scrollable, text, text_input,
};
use iced::{Element, Fill};
use serde::Deserialize;
use serde_json::{Value, json};

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Cell {
    pub row: usize,
    pub column: usize,
    pub row_span: usize,
    pub column_span: usize,
    pub label: String,
    pub vocalization: String,
    pub color: String,
    pub hidden: bool,
    pub linked_page: String,
    pub occupied: bool,
}
#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
pub struct Page {
    pub id: String,
    pub name: String,
}
impl std::fmt::Display for Page {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.name)
    }
}
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct View {
    pub screen_name: String,
    pub page_id: String,
    pub page_name: String,
    pub root_page_id: String,
    pub pages: Vec<Page>,
    pub rows: usize,
    pub columns: usize,
    pub cells: Vec<Cell>,
    pub dirty: bool,
    pub unsupported_elements: Vec<String>,
}
#[derive(Clone, Copy, Debug)]
pub enum Field {
    Screen,
    Page,
    Rows,
    Columns,
    Label,
    Speech,
    Color,
    Row,
    Column,
    RowSpan,
    ColumnSpan,
}
#[derive(Clone, Debug)]
pub enum Event {
    Begin(String),
    New,
    Select(usize, usize),
    Input(Field, String),
    Hidden(bool),
    Link(Page),
    Command(Value),
    Save,
    Discard,
    ConfirmDiscard,
    KeepEditing,
}
pub struct Editor {
    pub view: View,
    pub selected: Option<Cell>,
    pub screen: String,
    pub page: String,
    pub rows: String,
    pub columns: String,
    pub label: String,
    pub speech: String,
    pub color: String,
    pub hidden: bool,
    pub link: String,
    pub row: String,
    pub column: String,
    pub row_span: String,
    pub column_span: String,
    pub confirm_discard: bool,
    pub pending: std::collections::HashSet<String>,
}
impl Editor {
    pub fn new(view: View) -> Self {
        Self {
            screen: view.screen_name.clone(),
            page: view.page_name.clone(),
            rows: view.rows.to_string(),
            columns: view.columns.to_string(),
            view,
            selected: None,
            label: String::new(),
            speech: String::new(),
            color: String::new(),
            hidden: false,
            link: String::new(),
            row: "1".into(),
            column: "1".into(),
            row_span: "1".into(),
            column_span: "1".into(),
            confirm_discard: false,
            pending: Default::default(),
        }
    }
    pub fn select(&mut self, row: usize, column: usize) {
        if let Some(cell) = self
            .view
            .cells
            .iter()
            .find(|c| c.row == row && c.column == column)
            .cloned()
        {
            self.label = cell.label.clone();
            self.speech = cell.vocalization.clone();
            self.color = cell.color.clone();
            self.hidden = cell.hidden;
            self.link = cell.linked_page.clone();
            self.row = (cell.row + 1).to_string();
            self.column = (cell.column + 1).to_string();
            self.row_span = cell.row_span.to_string();
            self.column_span = cell.column_span.to_string();
            self.selected = Some(cell);
        }
    }
    pub fn input(&mut self, field: Field, value: String) {
        self.pending.insert(
            match field {
                Field::Screen => "renameScreen",
                Field::Page => "renamePage",
                Field::Rows | Field::Columns => "resizeGrid",
                Field::Row | Field::Column => "move",
                Field::RowSpan | Field::ColumnSpan => "span",
                _ => "button",
            }
            .into(),
        );
        *match field {
            Field::Screen => &mut self.screen,
            Field::Page => &mut self.page,
            Field::Rows => &mut self.rows,
            Field::Columns => &mut self.columns,
            Field::Label => &mut self.label,
            Field::Speech => &mut self.speech,
            Field::Color => &mut self.color,
            Field::Row => &mut self.row,
            Field::Column => &mut self.column,
            Field::RowSpan => &mut self.row_span,
            Field::ColumnSpan => &mut self.column_span,
        } = value;
    }
    fn command(&self, operation: &str) -> Value {
        let cell = self.selected.as_ref();
        json!({"operation": operation, "name": if operation == "renameScreen" { &self.screen } else { &self.page },
            "rows": self.rows.parse::<i32>().unwrap_or(0), "columns": self.columns.parse::<i32>().unwrap_or(0),
            "row": cell.map_or(0, |c| c.row), "column": cell.map_or(0, |c| c.column),
            "toRow": self.row.parse::<i32>().unwrap_or(0).saturating_sub(1),
            "toColumn": self.column.parse::<i32>().unwrap_or(0).saturating_sub(1),
            "rowSpan": self.row_span.parse::<i32>().unwrap_or(0), "columnSpan": self.column_span.parse::<i32>().unwrap_or(0),
            "label": self.label, "vocalization": self.speech, "color": self.color,
            "hidden": self.hidden, "linkedPage": self.link})
    }
    pub fn view(&self) -> Element<'_, Message> {
        let action = |label: &'static str, op: &str| {
            button(label).on_press(msg(Event::Command(self.command(op))))
        };
        let mut content = column![
            row![
                text("Screen editor").size(30),
                text(if self.view.dirty || !self.pending.is_empty() {
                    "Unsaved changes"
                } else {
                    "Saved"
                }),
                button("Save Screen").on_press(msg(Event::Save)),
                button("Discard / close").on_press(msg(Event::Discard))
            ]
            .spacing(12),
            text("Apply each change to the draft, then Save Screen to keep it."),
            row![
                input("Screen name", &self.screen, Field::Screen),
                action("Rename Screen", "renameScreen")
            ]
            .spacing(8),
            row![
                pick_list(
                    self.view.pages.clone(),
                    self.view
                        .pages
                        .iter()
                        .find(|p| p.id == self.view.page_id)
                        .cloned(),
                    |p| msg(Event::Command(json!({"operation":"page", "id":p.id})))
                ),
                button("Add Page").on_press(msg(Event::Command(json!({"operation":"addPage"})))),
                action("Use as starting Page", "root"),
                text(if self.view.page_id == self.view.root_page_id {
                    "Starting Page"
                } else {
                    ""
                })
            ]
            .spacing(8),
            row![
                input("Page name", &self.page, Field::Page),
                action("Rename Page", "renamePage")
            ]
            .spacing(8),
            row![
                text("Rows"),
                input("Rows", &self.rows, Field::Rows),
                text("Columns"),
                input("Columns", &self.columns, Field::Columns),
                action("Resize Grid", "resizeGrid")
            ]
            .spacing(8),
        ]
        .spacing(12);
        if self.confirm_discard {
            content = content.push(
                row![
                    text("Discard all unsaved changes?"),
                    button("Discard changes").on_press(msg(Event::ConfirmDiscard)),
                    button("Keep editing").on_press(msg(Event::KeepEditing))
                ]
                .spacing(10),
            );
        }
        let mut grid = column![].spacing(6);
        for r in 0..self.view.rows {
            let mut line = row![].spacing(6);
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
                        "+"
                    };
                    let selected = self
                        .selected
                        .as_ref()
                        .is_some_and(|s| s.row == cell.row && s.column == cell.column);
                    line = line.push(
                        button(text(format!(
                            "{}{}{}",
                            if selected { "• " } else { "" },
                            label,
                            if cell.hidden { " (hidden)" } else { "" }
                        )))
                        .width(Fill)
                        .height(64)
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
                    .spacing(8),
                    row![
                        text("Color"),
                        input("Background color (e.g. #ffffff)", &self.color, Field::Color),
                        checkbox(self.hidden)
                            .label("Hidden")
                            .on_toggle(|v| msg(Event::Hidden(v))),
                        pick_list(targets, selected, |p| msg(Event::Link(p))),
                        action("Apply Button", "button")
                    ]
                    .spacing(8),
                    row![
                        text("Row"),
                        input("Row", &self.row, Field::Row),
                        text("Column"),
                        input("Column", &self.column, Field::Column),
                        action("Move / swap", "move")
                    ]
                    .spacing(8),
                    row![
                        text("Row span"),
                        input("Row span", &self.row_span, Field::RowSpan),
                        text("Column span"),
                        input("Column span", &self.column_span, Field::ColumnSpan),
                        action("Resize Button", "span"),
                        action("Remove Button", "clear")
                    ]
                    .spacing(8),
                ]
                .spacing(10),
            );
        }
        container(scrollable(content.padding(20)))
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
        .on_input(move |s| msg(Event::Input(field, s)))
        .into()
}

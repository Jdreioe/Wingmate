pub mod controls;
mod view;
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
    Action(controls::Action),
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
    pub(crate) fn command(&self, operation: &str) -> Value {
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
}

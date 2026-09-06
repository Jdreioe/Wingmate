use crate::access::{self, Target};
use crate::{
    Message,
    models::{BoardSet, BoardView, Cell},
};
use base64::Engine;
use iced::widget::{Space, button, column, container, image, row, scrollable, text};
use iced::{Element, Fill};

/// Only opens a saved Screen. Creating, importing, and reopening a recent file
/// live in Settings > Screens.
pub fn library(sets: &[BoardSet]) -> Element<'_, Message> {
    let mut content = column![text("Screens").size(36)].spacing(16);
    if sets.is_empty() {
        content = content.push(
            text("No Screens yet. Open Settings, then Screens, to make one or import an OBF or OBZ file.")
                .size(19),
        );
    }
    for set in sets {
        content = content.push(
            button(set.name.as_str())
                .width(Fill)
                .on_press(Message::OpenBoardSet(set.id.clone())),
        );
    }
    container(scrollable(content.padding(24)))
        .width(Fill)
        .height(Fill)
        .into()
}

pub fn runner<'a>(view: &'a BoardView, access_state: &'a access::State) -> Element<'a, Message> {
    let mut page = column![
        row![
            access::area(
                button("Back").on_press(Message::Back),
                Target::Back,
                access_state
            ),
            text(&view.title).size(30),
            Space::new().width(Fill),
            crate::editor::controls::button("Edit Screen").on_press(Message::Editor(
                crate::editor::Event::Begin(view.board_set_id.clone())
            )),
            button("Library").on_press(Message::ShowLibrary),
            button(if access_state.is_paused {
                "Resume input"
            } else {
                "Rest"
            })
            .height(48)
            .on_press(Message::Access(access::Event::SetPaused(
                !access_state.is_paused
            )))
        ]
        .spacing(16)
        .align_y(iced::Center)
    ]
    .spacing(12)
    .padding(20);
    page = page.push(iced::widget::responsive(move |size| {
        let mut grid = iced::widget::stack![Space::new().width(Fill).height(Fill)].clip(true);
        for cell in &view.cells {
            let Some(bounds) = cell_bounds(cell, view.rows, view.columns, size) else {
                continue;
            };
            let label = if cell.label.is_empty() {
                &cell.vocalization
            } else {
                &cell.label
            };
            let mut content = column![].align_x(iced::Center).spacing(6);
            if let Some(handle) = cell.image.as_deref().and_then(image_handle) {
                content = content.push(image(handle).height(Fill));
            }
            content = content.push(text(label).size(22));
            let control = access::area(
                button(content)
                    .width(bounds.width)
                    .height(bounds.height)
                    .on_press(Message::Activate(cell.id.clone())),
                Target::Cell {
                    board_set: view.board_set_id.clone(),
                    page: view.board_id.clone(),
                    button: cell.id.clone(),
                },
                access_state,
            );
            grid = grid.push(iced::widget::pin(control).position(bounds.position()));
        }
        grid.into()
    }));
    if view.show_message_bar {
        page = page.push(crate::message_bar::view(
            &view.message,
            view.show_speak_button,
            access_state,
        ));
    }
    page = page.push(iced::widget::progress_bar(0.0..=1.0, access_state.dwell_progress).girth(6));
    if access_state.is_paused {
        page = page.push(text(
            "Rest mode. Select Resume input, or hold your select key for two seconds and release.",
        ));
    }
    container(page).width(Fill).height(Fill).into()
}

/// Lay out spans in both directions. Empty cells and the gaps remain untargeted.
pub(crate) fn cell_bounds(
    cell: &Cell,
    rows: usize,
    columns: usize,
    size: iced::Size,
) -> Option<iced::Rectangle> {
    const GAP: f32 = 10.0;
    if rows == 0
        || columns == 0
        || cell.row_span == 0
        || cell.column_span == 0
        || cell.row.checked_add(cell.row_span as usize)? > rows
        || cell.column.checked_add(cell.column_span as usize)? > columns
    {
        return None;
    }
    let width = (size.width - GAP * (columns - 1) as f32) / columns as f32;
    let height = (size.height - GAP * (rows - 1) as f32) / rows as f32;
    if width <= 0.0 || height <= 0.0 {
        return None;
    }
    Some(iced::Rectangle {
        x: cell.column as f32 * (width + GAP),
        y: cell.row as f32 * (height + GAP),
        width: cell.column_span as f32 * (width + GAP) - GAP,
        height: cell.row_span as f32 * (height + GAP) - GAP,
    })
}

fn image_handle(value: &str) -> Option<image::Handle> {
    if let Some(encoded) = value
        .strip_prefix("data:")
        .and_then(|data| data.split_once("base64,").map(|(_, payload)| payload))
    {
        return base64::engine::general_purpose::STANDARD
            .decode(encoded)
            .ok()
            .map(image::Handle::from_bytes);
    }
    let path = std::path::Path::new(value);
    path.exists().then(|| image::Handle::from_path(path))
}

#[cfg(test)]
mod tests {
    use super::image_handle;

    #[test]
    fn accepts_embedded_obf_images() {
        assert!(image_handle("data:image/png;base64,iVBORw0KGgo=").is_some());
        assert!(image_handle("data:image/png;base64,not-base64").is_none());
    }
}

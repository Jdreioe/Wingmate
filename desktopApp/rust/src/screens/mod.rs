use crate::access::{self, Target};
use crate::{
    Message,
    models::{BoardSet, BoardView},
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

pub fn runner<'a>(view: &'a BoardView, access_state: &access::State) -> Element<'a, Message> {
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
    for row_index in 0..view.rows {
        let mut cells = row![].spacing(10).height(Fill);
        let mut column_index = 0;
        while column_index < view.columns {
            if let Some(cell) = view
                .cells
                .iter()
                .find(|cell| cell.row == row_index && cell.column == column_index)
            {
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
                cells = cells.push(access::area(
                    button(content)
                        .width(iced::FillPortion(cell.column_span))
                        .height(iced::FillPortion(cell.row_span))
                        .on_press(Message::Activate(cell.id.clone())),
                    Target::Cell {
                        board_set: view.board_set_id.clone(),
                        page: view.board_id.clone(),
                        button: cell.id.clone(),
                    },
                    access_state,
                ));
                column_index += cell.column_span as usize;
            } else {
                cells = cells.push(Space::new().width(iced::FillPortion(1)));
                column_index += 1;
            }
        }
        page = page.push(cells);
    }
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

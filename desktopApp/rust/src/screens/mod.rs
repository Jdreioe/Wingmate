use crate::{
    Message,
    models::{BoardSet, BoardView},
};
use base64::Engine;
use iced::widget::{Space, button, column, container, image, row, scrollable, text};
use iced::{Element, Fill};

pub fn library<'a>(sets: &'a [BoardSet], recents: &'a [String]) -> Element<'a, Message> {
    let mut content = column![
        text("Screens").size(36),
        button("New Screen").on_press(Message::Editor(crate::editor::Event::New)),
        button("Open OBF or OBZ file").on_press(Message::ChooseBoardFile),
    ]
    .spacing(16);
    if !recents.is_empty() {
        content = content.push(text("Recent files").size(22));
        for path in recents {
            content =
                content.push(button(path.as_str()).on_press(Message::ImportFile(path.clone())));
        }
    }
    if !sets.is_empty() {
        content = content.push(text("Library").size(22));
        for set in sets {
            content = content.push(
                button(set.name.as_str())
                    .width(Fill)
                    .on_press(Message::OpenBoardSet(set.id.clone())),
            );
        }
    }
    container(scrollable(content.padding(24)))
        .width(Fill)
        .height(Fill)
        .into()
}

pub fn runner(view: &BoardView) -> Element<'_, Message> {
    let mut page = column![
        row![
            button("Back").on_press(Message::Back),
            text(&view.title).size(30),
            Space::new().width(Fill),
            button("Edit Screen").on_press(Message::Editor(crate::editor::Event::Begin(
                view.board_set_id.clone()
            ))),
            button("Library").on_press(Message::ShowLibrary)
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
                cells = cells.push(
                    button(content)
                        .width(iced::FillPortion(cell.column_span))
                        .height(iced::FillPortion(cell.row_span))
                        .on_press(Message::Activate(cell.id.clone())),
                );
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

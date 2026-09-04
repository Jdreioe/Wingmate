use crate::Message;
use iced::widget::{button, container, row, text};
use iced::{Element, Fill};

pub fn view(message: &str, show_speak: bool) -> Element<'_, Message> {
    let mut controls = row![
        button("Clear").on_press(Message::Clear),
        button("Hold").on_press(Message::Hold),
        container(
            text(if message.is_empty() {
                "Your message will appear here"
            } else {
                message
            })
            .size(24)
        )
        .width(Fill)
        .padding(12),
    ]
    .spacing(12)
    .align_y(iced::Center);
    if show_speak {
        controls = controls.push(button("Speak").on_press(Message::Speak));
    }
    container(controls).width(Fill).padding(12).into()
}

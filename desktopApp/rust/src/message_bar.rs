use crate::Message;
use crate::access::{self, Target};
use iced::widget::{button, container, row, text};
use iced::{Element, Fill};

pub fn view<'a>(message: &'a str, show_speak: bool, state: &access::State) -> Element<'a, Message> {
    let mut controls = row![
        access::area(
            button("Clear").height(48).on_press(Message::Clear),
            Target::Clear,
            state
        ),
        access::area(
            button("Hold").height(48).on_press(Message::Hold),
            Target::Hold,
            state
        ),
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
        controls = controls.push(access::area(
            button("Speak").height(48).on_press(Message::Speak),
            Target::Speak,
            state,
        ));
    }
    container(controls).width(Fill).padding(12).into()
}

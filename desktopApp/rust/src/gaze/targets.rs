//! Query actual laid-out controls instead of approximating the board geometry.
use crate::{access::Target, models::BoardView};
use iced::{
    Point, Rectangle, Size,
    advanced::widget::{Id, Operation, operation::Outcome},
};

pub struct HitTest {
    point: Point,
    targets: Vec<(Id, Target)>,
    hit: Option<Target>,
    ambiguous: bool,
}
impl HitTest {
    pub fn new(point: Point, board: &BoardView) -> Self {
        let targets = board
            .cells
            .iter()
            .map(|cell| Target::Cell {
                board_set: board.board_set_id.clone(),
                page: board.board_id.clone(),
                button: cell.id.clone(),
            })
            .chain([Target::Back, Target::Clear, Target::Hold, Target::Speak]);
        Self {
            point,
            targets: targets
                .map(|target| (Id::from(target.id()), target))
                .collect(),
            hit: None,
            ambiguous: false,
        }
    }
}
impl Operation<Option<Target>> for HitTest {
    fn traverse(&mut self, operate: &mut dyn FnMut(&mut dyn Operation<Option<Target>>)) {
        operate(self);
    }
    fn container(&mut self, id: Option<&Id>, bounds: Rectangle) {
        if contains(bounds, self.point)
            && let Some((_, target)) = self
                .targets
                .iter()
                .find(|(candidate, _)| Some(candidate) == id)
        {
            if self.hit.is_some() {
                self.ambiguous = true;
            }
            self.hit = Some(target.clone());
        }
    }
    fn finish(&self) -> Outcome<Option<Target>> {
        Outcome::Some(if self.ambiguous {
            None
        } else {
            self.hit.clone()
        })
    }
}

pub fn map(point: super::protocol::Point, size: Size) -> Option<Point> {
    if !point.x.is_finite()
        || !point.y.is_finite()
        || !(0.0..=1.0).contains(&point.x)
        || !(0.0..=1.0).contains(&point.y)
        || !size.width.is_finite()
        || !size.height.is_finite()
        || size.width <= 0.0
        || size.height <= 0.0
    {
        return None;
    }
    Some(Point::new(
        point.x as f32 * size.width,
        point.y as f32 * size.height,
    ))
}

// Half-open bounds give a shared edge to at most one target.
pub fn contains(bounds: Rectangle, point: Point) -> bool {
    point.x >= bounds.x
        && point.y >= bounds.y
        && point.x < bounds.x + bounds.width
        && point.y < bounds.y + bounds.height
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::Cell;
    fn fixture() -> BoardView {
        serde_json::from_value(serde_json::json!({
            "boardSetId":"set", "boardId":"page", "title":"Test", "rows":2, "columns":3,
            "cells":[
                {"id":"span", "row":0, "column":0,"rowSpan":2,"columnSpan":2,"label":"Span","vocalization":"","image":null},
                {"id":"single", "row":1, "column":2,"rowSpan":1,"columnSpan":1,"label":"Single","vocalization":"","image":null}
            ], "message":"", "showMessageBar":true, "showSpeakButton":true
        })).unwrap()
    }
    fn resolve(board: &BoardView, point: Point, size: Size) -> Option<Target> {
        let mut test = HitTest::new(point, board);
        for cell in &board.cells {
            let bounds =
                crate::screens::cell_bounds(cell, board.rows, board.columns, size).unwrap();
            let target = Target::Cell {
                board_set: board.board_set_id.clone(),
                page: board.board_id.clone(),
                button: cell.id.clone(),
            };
            test.container(Some(&Id::from(target.id())), bounds);
        }
        match test.finish() {
            Outcome::Some(target) => target,
            _ => unreachable!(),
        }
    }
    #[test]
    fn spans_empty_cells_gaps_and_display_edges_resolve_consistently() {
        let board = fixture();
        let size = Size::new(320.0, 210.0);
        assert!(
            matches!(resolve(&board, Point::new(205.0, 205.0), size), Some(Target::Cell { button, .. }) if button == "span")
        );
        assert!(
            matches!(resolve(&board, Point::new(250.0, 150.0), size), Some(Target::Cell { button, .. }) if button == "single")
        );
        assert!(resolve(&board, Point::new(215.0, 150.0), size).is_none());
        assert!(resolve(&board, Point::new(250.0, 50.0), size).is_none());
        assert!(resolve(&board, Point::new(320.0, 150.0), size).is_none());
        // Logical window size performs the same mapping on HiDPI displays.
        assert_eq!(
            map(super::super::protocol::Point { x: 0.5, y: 0.5 }, size),
            Some(Point::new(160.0, 105.0))
        );
        assert!(
            map(
                super::super::protocol::Point {
                    x: f64::NAN,
                    y: 0.5
                },
                size
            )
            .is_none()
        );
        assert!(map(super::super::protocol::Point { x: 0.5, y: 0.5 }, Size::ZERO).is_none());
    }
    #[test]
    fn overlapping_or_out_of_bounds_cells_cannot_be_selected() {
        let mut board = fixture();
        let mut overlapping = board.cells[0].clone();
        overlapping.id = "overlap".into();
        board.cells.push(overlapping);
        assert!(resolve(&board, Point::new(50.0, 50.0), Size::new(320.0, 210.0)).is_none());
        let invalid = Cell {
            row: 2,
            ..board.cells[0].clone()
        };
        assert!(crate::screens::cell_bounds(&invalid, 2, 3, Size::new(320.0, 210.0)).is_none());
    }
}

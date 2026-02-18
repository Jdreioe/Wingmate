import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic

// Reusable chip/pill component (for syllables, categories, emphasis levels)
Button {
    id: control
    
    property bool selected: false
    property color chipColor: selected ? Theme.primary : Theme.surfaceHighlight
    property color textColor: selected ? "#ffffff" : Theme.text
    
    implicitWidth: contentLabel.implicitWidth + 24
    implicitHeight: 32
    
    contentItem: Text {
        id: contentLabel
        text: control.text
        font.pixelSize: Theme.fontSizeSmall
        font.weight: Font.Medium
        color: control.textColor
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
    }
    
    background: Rectangle {
        radius: height / 2
        color: {
            if (control.down) return Qt.darker(control.chipColor, 1.1);
            if (control.hovered) return Qt.lighter(control.chipColor, 1.1);
            return control.chipColor;
        }
        border.color: selected ? "transparent" : Theme.surfaceLight
        border.width: 1
        
        Behavior on color { ColorAnimation { duration: 100 } }
    }
}

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
    
    property bool contextMenuEnabled: false
    signal renameRequested()
    signal deleteRequested()
    
    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.RightButton
        propagateComposedEvents: true
        
        // Pass through clicks unless it's a right click we want to handle
        onClicked: (mouse) => {
            if (control.contextMenuEnabled && mouse.button === Qt.RightButton) {
                contextMenu.popup();
            } else {
                mouse.accepted = false;
            }
        }
        
        onPressAndHold: {
            if (control.contextMenuEnabled) contextMenu.popup();
            else mouse.accepted = false;
        }
    }

    Menu {
        id: contextMenu
        enabled: control.contextMenuEnabled
        
        MenuItem {
            text: "Rename"
            onTriggered: control.renameRequested()
            contentItem: Text {
                text: parent.text
                color: Theme.text
                font.pixelSize: Theme.fontSizeNormal
                padding: 12
            }
            background: Rectangle {
                color: parent.highlighted ? Theme.surfaceHighlight : "transparent"
            }
        }
        
        MenuItem {
            text: "Delete"
            onTriggered: control.deleteRequested()
            contentItem: Text {
                text: parent.text
                color: Theme.error
                font.pixelSize: Theme.fontSizeNormal
                padding: 12
            }
            background: Rectangle {
                color: parent.highlighted ? Theme.surfaceHighlight : "transparent"
            }
        }
        
        background: Rectangle {
            color: Theme.surface
            border.color: Theme.surfaceHighlight
            radius: Theme.radius
        }
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

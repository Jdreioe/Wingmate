import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts
import org.kde.kirigami as Kirigami

Button {
    id: control
    
    property bool primary: false
    property string iconName: ""
    
    contentItem: RowLayout {
        spacing: 8
        
        Item { // Icon placeholder if name provided
            visible: control.iconName !== ""
            Layout.preferredWidth: 24
            Layout.preferredHeight: 24
             
            Kirigami.Icon {
                source: control.iconName
                anchors.fill: parent
                visible: source !== ""
                opacity: enabled ? 1 : 0.5
            }
        }
        
        Text {
            text: control.text
            font.pixelSize: Theme.fontSizeNormal
            font.weight: Font.Medium
            color: control.primary ? Theme.background : Theme.text
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
            Layout.fillWidth: true
        }
    }
    
    background: Rectangle {
        implicitWidth: 100
        implicitHeight: 40
        radius: Theme.smallRadius
        
        color: {
            if (!control.enabled) return Theme.surfaceHighlight;
            if (control.down) return control.primary ? Qt.darker(Theme.primary, 1.1) : Theme.surfaceHighlight;
            if (control.hovered) return control.primary ? Qt.lighter(Theme.primary, 1.1) : Theme.surfaceHighlight;
            return control.primary ? Theme.primary : Theme.surface;
        }
        
        border.color: control.primary ? "transparent" : Theme.surfaceHighlight
        border.width: 1
        
        Behavior on color { ColorAnimation { duration: 150 } }
    }
}

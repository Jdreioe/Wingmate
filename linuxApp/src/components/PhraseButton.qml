import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic

Button {
    id: control
    
    property string phraseText: ""
    property string phraseImage: ""
    property color cardColor: Theme.surface
    
    background: Rectangle {
        radius: Theme.radius
        color: control.down ? Qt.darker(control.cardColor, 1.1) : (control.hovered ? Qt.lighter(control.cardColor, 1.1) : control.cardColor)
        border.color: Theme.surfaceHighlight
        border.width: 1
        
        Behavior on color { ColorAnimation { duration: 150 } }
        
        // Shadow effect (simple)
        Rectangle {
            z: -1
            anchors.fill: parent
            anchors.topMargin: 2
            anchors.leftMargin: 2
            radius: parent.radius
            color: Qt.rgba(0,0,0,0.2)
            visible: !control.down
        }
    }
    
    contentItem: ColumnLayout {
        anchors.centerIn: parent
        spacing: 8
        
        // Image support can be added properly later, placeholder for now
        Item {
            visible: control.phraseImage !== ""
            Layout.preferredWidth: 48
            Layout.preferredHeight: 48
            Layout.alignment: Qt.AlignHCenter
            
            Image {
                source: control.phraseImage
                anchors.fill: parent
                fillMode: Image.PreserveAspectFit
                visible: source !== ""
            }
        }
        
        Text {
            text: control.phraseText
            color: Theme.text
            font.pixelSize: Theme.fontSizeNormal
            font.bold: true
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            Layout.fillWidth: true
            Layout.maximumWidth: parent.width - 16
        }
    }
}

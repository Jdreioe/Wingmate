import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic
import org.kde.kirigami as Kirigami

Rectangle {
    id: root
    
    property int collapsedWidth: 80
    property int expandedWidth: 240
    property bool expanded: false
    
    signal navigationRequested(string page)
    
    implicitWidth: expanded ? expandedWidth : collapsedWidth
    width: implicitWidth
    color: Theme.background
    
    Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.InOutQuad } }
    
    ColumnLayout {
        anchors.fill: parent
        spacing: 0
        
        // Logo / Toggle
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 80
            
            Rectangle {
                anchors.centerIn: parent
                width: 48; height: 48
                radius: 12
                color: Theme.primary
                
                Text {
                    anchors.centerIn: parent
                    text: "W"
                    color: Theme.background
                    font.bold: true
                    font.pixelSize: 24
                }
                
                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onClicked: root.expanded = !root.expanded
                }
            }
        }
        
        // Nav Items
        Repeater {
            model: [
                { name: "Home", icon: "home", page: "qrc:/main.qml", id: "home" },
                { name: "Dictionary", icon: "document-edit", page: "qrc:/pages/PronunciationDictionaryPage.qml", id: "dictionary" },
                { name: "Settings", icon: "settings-configure", page: "qrc:/pages/SettingsPage.qml", id: "settings" }
            ]
            
            Button {
                id: navBtn
                Layout.fillWidth: true
                Layout.preferredHeight: 60
                
                background: Rectangle {
                    color: navBtn.hovered ? Theme.surfaceHighlight : "transparent"
                    
                    Rectangle { // Active indicator
                        visible: navBtn.down // Simplified active state for now
                        width: 4
                        height: parent.height
                        color: Theme.accent
                        anchors.left: parent.left
                    }
                }
                
                contentItem: RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: (root.collapsedWidth - 24) / 2
                    spacing: 20
                    
                    Item {
                        Layout.preferredWidth: 24
                        Layout.preferredHeight: 24
                        Kirigami.Icon {
                            source: modelData.icon
                            anchors.fill: parent
                            opacity: 0.8
                        }
                    }
                    
                    Text {
                        text: modelData.name
                        color: Theme.text
                        font.pixelSize: Theme.fontSizeNormal
                        visible: root.expanded
                        opacity: root.expanded ? 1 : 0
                        Behavior on opacity { NumberAnimation { duration: 150 } }
                        Layout.fillWidth: true
                    }
                }
                
                onClicked: root.navigationRequested(modelData.page)
                
                ToolTip.visible: hovered && !root.expanded
                ToolTip.text: modelData.name
                ToolTip.delay: 500
            }
        }
        
        Item { Layout.fillHeight: true } // Spacer
        
    }
    
    // Separator
    Rectangle {
        width: 1
        height: parent.height
        color: Theme.surfaceHighlight
        anchors.right: parent.right
    }
}

import QtQuick
import QtQuick.Layouts

// Horizontal bar showing word predictions above the keyboard
Item {
    id: predictionBar
    
    property var predictions: []  // Array of word strings
    property bool loading: false
    
    signal wordSelected(string word)
    
    implicitHeight: 40
    
    Rectangle {
        anchors.fill: parent
        color: Theme.surface
        border.color: Theme.surfaceHighlight
        border.width: 1
        radius: Theme.smallRadius
        
        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: 8
            anchors.rightMargin: 8
            spacing: 4
            
            Repeater {
                model: predictionBar.predictions
                
                Rectangle {
                    Layout.fillHeight: true
                    Layout.fillWidth: true
                    Layout.topMargin: 4
                    Layout.bottomMargin: 4
                    
                    color: predMouseArea.containsMouse 
                        ? Theme.primaryLight 
                        : "transparent"
                    radius: Theme.smallRadius
                    
                    Text {
                        anchors.centerIn: parent
                        text: modelData
                        font.pixelSize: Theme.fontSizeNormal
                        font.weight: Font.Medium
                        color: predMouseArea.containsMouse 
                            ? "#ffffff" 
                            : Theme.text
                        elide: Text.ElideRight
                        
                        Behavior on color { ColorAnimation { duration: 100 } }
                    }
                    
                    Behavior on color { ColorAnimation { duration: 100 } }
                    
                    MouseArea {
                        id: predMouseArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: predictionBar.wordSelected(modelData)
                    }
                    
                    // Separator line between predictions
                    Rectangle {
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        width: 1
                        height: parent.height * 0.6
                        color: Theme.surfaceHighlight
                        visible: index < predictionBar.predictions.length - 1
                    }
                }
            }
            
            // Placeholder when empty
            Text {
                Layout.fillWidth: true
                text: predictionBar.loading ? "..." : ""
                color: Theme.subText
                font.pixelSize: Theme.fontSizeSmall
                horizontalAlignment: Text.AlignHCenter
                visible: predictionBar.predictions.length === 0
            }
        }
    }
}

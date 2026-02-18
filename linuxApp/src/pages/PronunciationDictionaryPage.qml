import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic
import org.kde.kirigami as Kirigami
import "../components"

// Pronunciation Dictionary Page
Item {
    id: root
    
    property string baseUrl: ""
    property var entries: []
    
    signal backRequested()
    signal addEntryRequested()
    
    Component.onCompleted: loadEntries()
    
    function loadEntries() {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE && xhr.status === 200) {
                entries = JSON.parse(xhr.responseText);
            }
        }
        xhr.open("GET", baseUrl + "/api/pronunciation");
        xhr.send();
    }
    
    function deleteEntry(word) {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) loadEntries();
        }
        xhr.open("DELETE", baseUrl + "/api/pronunciation/" + encodeURIComponent(word));
        xhr.send();
    }
    
    ColumnLayout {
        anchors.fill: parent
        anchors.margins: Theme.spacingXLarge
        spacing: Theme.spacingLarge
        
        // Header with back arrow
        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingMedium
            
            Button {
                implicitWidth: 40
                implicitHeight: 40
                
                contentItem: Kirigami.Icon {
                    source: "go-previous"
                    implicitWidth: 20
                    implicitHeight: 20
                }
                
                background: Rectangle {
                    radius: 20
                    color: parent.hovered ? Theme.surfaceHighlight : "transparent"
                }
                
                onClicked: root.backRequested()
            }
            
            ColumnLayout {
                spacing: 2
                
                Text {
                    text: "Pronunciation Dictionary"
                    font.pixelSize: Theme.fontSizeHeader
                    font.bold: true
                    color: Theme.text
                }
                
                Text {
                    text: "Global pronunciation rules applied to all speech"
                    font.pixelSize: Theme.fontSizeSmall
                    color: Theme.subText
                }
            }
        }
        
        // Entry list or empty state
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            
            // Empty state
            ColumnLayout {
                visible: root.entries.length === 0
                anchors.centerIn: parent
                spacing: 12
                
                Kirigami.Icon {
                    source: "document-edit"
                    implicitWidth: 64
                    implicitHeight: 64
                    opacity: 0.4
                    Layout.alignment: Qt.AlignHCenter
                }
                
                Text {
                    text: "No pronunciation entries yet"
                    font.pixelSize: Theme.fontSizeMedium
                    color: Theme.subText
                    Layout.alignment: Qt.AlignHCenter
                }
                
                Text {
                    text: "Tap + to add custom pronunciations"
                    font.pixelSize: Theme.fontSizeSmall
                    color: Theme.subText
                    Layout.alignment: Qt.AlignHCenter
                }
            }
            
            // Entry ListView
            ListView {
                visible: root.entries.length > 0
                anchors.fill: parent
                clip: true
                spacing: 4
                
                model: root.entries
                
                delegate: Rectangle {
                    width: ListView.view.width
                    height: 56
                    radius: Theme.smallRadius
                    color: delMouse.containsMouse ? Theme.surfaceHighlight : Theme.surface
                    
                    MouseArea {
                        id: delMouse
                        anchors.fill: parent
                        hoverEnabled: true
                    }
                    
                    RowLayout {
                        anchors.fill: parent
                        anchors.margins: 12
                        spacing: 12
                        
                        ColumnLayout {
                            spacing: 2
                            Layout.fillWidth: true
                            
                            Text {
                                text: modelData.word || ""
                                font.pixelSize: Theme.fontSizeNormal
                                font.bold: true
                                color: Theme.text
                            }
                            Text {
                                text: "→ " + (modelData.phoneme || "")
                                font.pixelSize: Theme.fontSizeSmall
                                color: Theme.subText
                            }
                        }
                        
                        Button {
                            implicitWidth: 32
                            implicitHeight: 32
                            
                            contentItem: Kirigami.Icon {
                                source: "edit-delete"
                                implicitWidth: 18
                                implicitHeight: 18
                            }
                            
                            background: Rectangle {
                                radius: 16
                                color: parent.hovered ? Theme.error : "transparent"
                                opacity: 0.8
                            }
                            
                            onClicked: root.deleteEntry(modelData.word)
                        }
                    }
                }
            }
        }
    }
    
    // FAB (Add entry) — navigates to add entry page
    Button {
        id: fab
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.margins: 24
        width: 56; height: 56
        
        contentItem: Text {
            text: "+"
            font.pixelSize: 28
            font.bold: true
            color: "#ffffff"
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
        }
        
        background: Rectangle {
            radius: 28
            color: {
                if (fab.down) return Qt.darker(Theme.primary, 1.1);
                if (fab.hovered) return Qt.lighter(Theme.primary, 1.1);
                return Theme.primary;
            }
            
            Behavior on color { ColorAnimation { duration: 100 } }
        }
        
        onClicked: root.addEntryRequested()
    }
}


import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic
import "../components"

// Voice Selection Dialog — browse and select voices
Popup {
    id: root
    
    property string baseUrl: ""
    property var voices: []
    property string currentLanguageFilter: ""
    property var availableLanguages: []
    
    signal voiceSelected(string voiceName)
    signal voiceSettingsRequested(var voice)
    
    anchors.centerIn: parent
    width: 480
    height: Math.min(600, parent.height * 0.8)
    modal: true
    dim: true
    closePolicy: Popup.CloseOnEscape
    
    background: Rectangle {
        color: Theme.surface
        radius: Theme.largeRadius
        border.color: Theme.surfaceHighlight
        border.width: 1
    }
    
    Overlay.modal: Rectangle { color: Qt.rgba(0, 0, 0, 0.6) }
    
    contentItem: ColumnLayout {
        spacing: Theme.spacingLarge
        
        // Title
        Text {
            text: "Select Voice"
            font.pixelSize: Theme.fontSizeLarge
            font.bold: true
            color: Theme.text
        }
        
        // Language Filter
        RowLayout {
            Layout.fillWidth: true
            spacing: 12
            
            Text {
                text: "Filter by Language:"
                font.pixelSize: Theme.fontSizeNormal
                color: Theme.text
            }
            
            Button {
                id: langFilterBtn
                text: root.currentLanguageFilter || "All Languages"
                
                contentItem: RowLayout {
                    spacing: 6
                    Text {
                        text: "≡"
                        font.pixelSize: 14
                        color: Theme.text
                    }
                    Text {
                        text: langFilterBtn.text
                        font.pixelSize: Theme.fontSizeNormal
                        color: Theme.text
                    }
                }
                
                background: Rectangle {
                    radius: Theme.smallRadius
                    color: langFilterBtn.hovered ? Theme.surfaceHighlight : Theme.background
                    border.color: Theme.surfaceHighlight
                    border.width: 1
                }
                
                onClicked: langMenu.open()
                
                Menu {
                    id: langMenu
                    
                    MenuItem {
                        text: "All Languages"
                        onTriggered: root.currentLanguageFilter = ""
                    }
                    
                    MenuSeparator {}
                    
                    Instantiator {
                        model: root.availableLanguages
                        MenuItem {
                            text: modelData
                            onTriggered: root.currentLanguageFilter = modelData
                        }
                        onObjectAdded: (index, object) => langMenu.insertItem(index + 2, object)
                        onObjectRemoved: (index, object) => langMenu.removeItem(object)
                    }
                }
            }
        }
        
        // Section header
        Text {
            text: "Azure TTS Voices"
            font.pixelSize: Theme.fontSizeMedium
            font.bold: true
            color: Theme.text
        }
        
        // Voice List
        ListView {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: 4
            
            model: {
                if (!root.voices) return [];
                if (root.currentLanguageFilter === "") return root.voices;
                return root.voices.filter(function(v) {
                    return v.primaryLanguage === root.currentLanguageFilter;
                });
            }
            
            delegate: Rectangle {
                width: ListView.view.width
                height: 52
                radius: Theme.smallRadius
                color: mouseArea.containsMouse ? Theme.surfaceHighlight : "transparent"
                
                MouseArea {
                    id: mouseArea
                    anchors.fill: parent
                    hoverEnabled: true
                    onClicked: root.voiceSelected(modelData.name)
                }
                
                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 12
                    spacing: 12
                    
                    ColumnLayout {
                        spacing: 2
                        Layout.fillWidth: true
                        
                        Text {
                            text: modelData.displayName || modelData.name
                            font.pixelSize: Theme.fontSizeNormal
                            color: Theme.text
                        }
                        Text {
                            text: modelData.primaryLanguage || ""
                            font.pixelSize: Theme.fontSizeSmall
                            color: Theme.subText
                        }
                    }
                    
                    ModernButton {
                        text: "Settings"
                        primary: true
                        onClicked: root.voiceSettingsRequested(modelData)
                    }
                }
            }
        }
        
        // Close button
        RowLayout {
            Layout.fillWidth: true
            Item { Layout.fillWidth: true }
            
            Button {
                text: "Close"
                contentItem: Text {
                    text: parent.text
                    font.pixelSize: Theme.fontSizeNormal
                    color: Theme.accent
                    horizontalAlignment: Text.AlignHCenter
                }
                background: Item {}
                onClicked: root.close()
            }
        }
    }
}

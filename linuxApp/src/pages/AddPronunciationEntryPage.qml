import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic
import org.kde.kirigami as Kirigami
import "../components"

// Add Pronunciation Entry — full page
Item {
    id: root
    
    property string baseUrl: ""
    
    signal backRequested()
    signal entryAdded()
    
    function addEntry() {
        if (wordField.text.length === 0 || phonemeField.text.length === 0) return;
        
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) {
                wordField.text = "";
                phonemeField.text = "";
                root.entryAdded();
                root.backRequested();
            }
        }
        xhr.open("POST", baseUrl + "/api/pronunciation");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ word: wordField.text, phoneme: phonemeField.text }));
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
            
            Text {
                text: "Add Pronunciation Entry"
                font.pixelSize: Theme.fontSizeHeader
                font.bold: true
                color: Theme.text
            }
        }
        
        // Description
        Text {
            text: "Add a custom pronunciation rule. When the word is encountered in text, it will be read using the phoneme you specify."
            font.pixelSize: Theme.fontSizeNormal
            color: Theme.subText
            wrapMode: Text.Wrap
            Layout.fillWidth: true
        }
        
        // Word field
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4
            
            Text {
                text: "Word"
                font.pixelSize: Theme.fontSizeSmall
                font.bold: true
                color: Theme.text
            }
            
            TextField {
                id: wordField
                Layout.fillWidth: true
                placeholderText: "e.g., tomato"
                font.pixelSize: Theme.fontSizeNormal
                color: Theme.text
                
                background: Rectangle {
                    color: Theme.background
                    radius: Theme.smallRadius
                    border.color: wordField.activeFocus ? Theme.primary : Theme.surfaceHighlight
                    border.width: wordField.activeFocus ? 2 : 1
                }
            }
        }
        
        // Phoneme field
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4
            
            Text {
                text: "Phoneme / Pronunciation"
                font.pixelSize: Theme.fontSizeSmall
                font.bold: true
                color: Theme.text
            }
            
            TextField {
                id: phonemeField
                Layout.fillWidth: true
                placeholderText: "e.g., tuh-MAY-toh"
                font.pixelSize: Theme.fontSizeNormal
                color: Theme.text
                
                background: Rectangle {
                    color: Theme.background
                    radius: Theme.smallRadius
                    border.color: phonemeField.activeFocus ? Theme.primary : Theme.surfaceHighlight
                    border.width: phonemeField.activeFocus ? 2 : 1
                }
            }
            
            Text {
                text: "Enter how you want this word to be pronounced"
                font.pixelSize: Theme.fontSizeSmall
                color: Theme.subText
            }
        }
        
        // Spacer
        Item { Layout.fillHeight: true }
        
        // Action buttons
        RowLayout {
            Layout.fillWidth: true
            spacing: 12
            
            Item { Layout.fillWidth: true }
            
            ModernButton {
                text: "Cancel"
                onClicked: root.backRequested()
            }
            
            ModernButton {
                text: "Add Entry"
                primary: true
                enabled: wordField.text.length > 0 && phonemeField.text.length > 0
                onClicked: root.addEntry()
            }
        }
    }
}

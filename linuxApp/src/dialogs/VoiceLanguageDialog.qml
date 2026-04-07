import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic
import "../components"

Dialog {
    id: root
    
    title: "Voice Language"
    modal: true
    width: 400
    x: (parent.width - width) / 2
    y: (parent.height - height) / 2 // Center in parent (ApplicationWindow)
    
    property string baseUrl: ""
    property var availableLanguages: []
    property string primaryLanguage: "en-US"
    property string secondaryLanguage: "en-US"
    
    signal settingsSaved(string primary, string secondary)
    
    onOpened: {
        // Load current settings
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE && xhr.status === 200) {
                var settings = JSON.parse(xhr.responseText);
                if (settings.primaryLanguage) root.primaryLanguage = settings.primaryLanguage;
                if (settings.secondaryLanguage) root.secondaryLanguage = settings.secondaryLanguage;
            }
        }
        xhr.open("GET", root.baseUrl + "/api/settings");
        xhr.send();
    }
    
    background: Rectangle {
        color: Theme.surface
        radius: Theme.radius
        border.color: Theme.surfaceHighlight
        border.width: 1
    }
    
    header: Label {
        text: root.title
        color: Theme.text
        font.pixelSize: Theme.fontSizeMedium
        font.bold: true
        padding: 16
        background: Rectangle { color: "transparent" }
    }
    
    contentItem: ColumnLayout {
        spacing: 16
        
        // Filter (search)
        TextField {
            id: languageFilter
            Layout.fillWidth: true
            placeholderText: "Filter languages..."
            color: Theme.text
            background: Rectangle {
                color: Theme.background
                radius: Theme.smallRadius
                border.color: languageFilter.activeFocus ? Theme.primary : Theme.surfaceHighlight
            }
        }
        
        // Primary
        ColumnLayout {
            spacing: 4
            Label { text: "Primary Language"; color: Theme.subText; font.pixelSize: Theme.fontSizeSmall }
            
            ComboBox {
                id: primaryCombo
                Layout.fillWidth: true
                model: root.availableLanguages.filter(l => l.toLowerCase().includes(languageFilter.text.toLowerCase()))
                
                // Handling selection binding manually due to model filtering
                onActivated: root.primaryLanguage = currentText
                
                // Helper to set current index based on property
                onModelChanged: {
                    var idx = find(root.primaryLanguage);
                    if (idx !== -1) currentIndex = idx;
                }
                
                // Custom delegate/contentItem for theme matching
                delegate: ItemDelegate {
                    width: primaryCombo.width
                    contentItem: Text {
                        text: modelData
                        color: Theme.text
                        font.pixelSize: Theme.fontSizeNormal
                        verticalAlignment: Text.AlignVCenter
                    }
                    background: Rectangle {
                        color: highlighted ? Theme.surfaceHighlight : "transparent"
                    }
                }
                contentItem: Text {
                    leftPadding: 12
                    text: primaryCombo.displayText
                    color: Theme.text
                    font.pixelSize: Theme.fontSizeNormal
                    verticalAlignment: Text.AlignVCenter
                }
                background: Rectangle {
                    color: Theme.background
                    radius: Theme.smallRadius
                    border.color: Theme.surfaceHighlight
                }
            }
        }
        
        // Secondary
        ColumnLayout {
            spacing: 4
            Label { text: "Secondary Language"; color: Theme.subText; font.pixelSize: Theme.fontSizeSmall }
            
            ComboBox {
                id: secondaryCombo
                Layout.fillWidth: true
                model: root.availableLanguages.filter(l => l.toLowerCase().includes(languageFilter.text.toLowerCase()))
                
                onActivated: root.secondaryLanguage = currentText
                
                onModelChanged: {
                    var idx = find(root.secondaryLanguage);
                    if (idx !== -1) currentIndex = idx;
                }
                
                delegate: ItemDelegate {
                    width: secondaryCombo.width
                    contentItem: Text {
                        text: modelData
                        color: Theme.text
                        font.pixelSize: Theme.fontSizeNormal
                        verticalAlignment: Text.AlignVCenter
                    }
                    background: Rectangle {
                        color: highlighted ? Theme.surfaceHighlight : "transparent"
                    }
                }
                contentItem: Text {
                    leftPadding: 12
                    text: secondaryCombo.displayText
                    color: Theme.text
                    font.pixelSize: Theme.fontSizeNormal
                    verticalAlignment: Text.AlignVCenter
                }
                background: Rectangle {
                    color: Theme.background
                    radius: Theme.smallRadius
                    border.color: Theme.surfaceHighlight
                }
            }
        }
    }
    
    footer: RowLayout {
        spacing: 12
        Layout.margins: 16
        
        Item { Layout.fillWidth: true }
        
        Button {
            text: "Cancel"
            flat: true
            contentItem: Text { 
                text: parent.text
                color: Theme.subText
                font.pixelSize: Theme.fontSizeNormal
            }
            background: Item {}
            onClicked: root.close()
        }
        
        ModernButton {
            text: "Save"
            primary: true
            onClicked: {
                // Save settings
                var xhr = new XMLHttpRequest();
                xhr.open("PUT", root.baseUrl + "/api/settings"); // Assuming endpoint
                xhr.setRequestHeader("Content-Type", "application/json");
                xhr.send(JSON.stringify({ 
                    primaryLanguage: root.primaryLanguage,
                    secondaryLanguage: root.secondaryLanguage
                }));
                
                root.settingsSaved(root.primaryLanguage, root.secondaryLanguage);
                root.close();
            }
        }
    }
}

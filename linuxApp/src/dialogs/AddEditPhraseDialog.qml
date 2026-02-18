import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic
import "../components"

Dialog {
    id: root
    
    title: isEditing ? "Edit Phrase" : "Add Phrase"
    modal: true
    width: 400
    x: (parent.width - width) / 2
    y: (parent.height - height) / 2
    
    property bool isEditing: false
    property string originalPhrase: ""
    
    signal phraseSaved(string text)
    
    function openForAdd() {
        isEditing = false;
        originalPhrase = "";
        phraseField.text = "";
        phraseField.forceActiveFocus();
        open();
    }
    
    function openForEdit(text) {
        isEditing = true;
        originalPhrase = text;
        phraseField.text = text;
        phraseField.forceActiveFocus();
        open();
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
        
        TextField {
            id: phraseField
            Layout.fillWidth: true
            placeholderText: "Phrase text"
            color: Theme.text
            font.pixelSize: Theme.fontSizeNormal
            topPadding: 12; bottomPadding: 12
            
            background: Rectangle {
                color: Theme.background
                radius: Theme.smallRadius
                border.color: phraseField.activeFocus ? Theme.primary : Theme.surfaceHighlight
                border.width: phraseField.activeFocus ? 2 : 1
            }
            
            Keys.onReturnPressed: {
                if (phraseField.text.trim().length > 0) {
                    root.phraseSaved(phraseField.text.trim());
                    root.close();
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
                horizontalAlignment: Text.AlignHCenter
                verticalAlignment: Text.AlignVCenter
            }
            background: Item {}
            onClicked: root.close()
        }
        
        ModernButton {
            text: root.isEditing ? "Save" : "Add"
            primary: true
            enabled: phraseField.text.trim().length > 0
            onClicked: {
                root.phraseSaved(phraseField.text.trim());
                root.close();
            }
        }
    }
}

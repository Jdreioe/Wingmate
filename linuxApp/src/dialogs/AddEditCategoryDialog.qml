import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic
import "../components"

Dialog {
    id: root
    
    title: isEditing ? "Rename Category" : "Add Category"
    modal: true
    width: 360
    x: (parent.width - width) / 2
    y: (parent.height - height) / 2
    
    property bool isEditing: false
    property string originalName: ""
    property string categoryId: ""
    
    signal categorySaved(string name)
    
    function openForAdd() {
        isEditing = false;
        originalName = "";
        categoryId = "";
        nameField.text = "";
        nameField.forceActiveFocus();
        open();
    }
    
    function openForEdit(id, name) {
        isEditing = true;
        categoryId = id;
        originalName = name;
        nameField.text = name;
        nameField.forceActiveFocus();
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
            id: nameField
            Layout.fillWidth: true
            placeholderText: "Category name"
            color: Theme.text
            font.pixelSize: Theme.fontSizeNormal
            topPadding: 12; bottomPadding: 12
            
            background: Rectangle {
                color: Theme.background
                radius: Theme.smallRadius
                border.color: nameField.activeFocus ? Theme.primary : Theme.surfaceHighlight
                border.width: nameField.activeFocus ? 2 : 1
            }
            
            Keys.onReturnPressed: {
                if (nameField.text.trim().length > 0) {
                    root.categorySaved(nameField.text.trim());
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
            enabled: nameField.text.trim().length > 0
            onClicked: {
                root.categorySaved(nameField.text.trim());
                root.close();
            }
        }
    }
}

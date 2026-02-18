import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic
import "../components"

// Speech Settings Dialog — matches Compose Desktop "Speech Settings" modal
Popup {
    id: root
    
    property string baseUrl: ""
    property bool useAzure: true
    property string azureEndpoint: ""
    property string azureKey: ""
    property bool useVirtualMic: false
    property real uiScale: 1.0
    
    signal saved()
    
    anchors.centerIn: parent
    width: 560
    height: contentCol.implicitHeight + 80
    modal: true
    dim: true
    closePolicy: Popup.CloseOnEscape
    
    background: Rectangle {
        color: Theme.surface
        radius: Theme.largeRadius
        border.color: Theme.surfaceHighlight
        border.width: 1
    }
    
    // Dim overlay
    Overlay.modal: Rectangle { color: Qt.rgba(0, 0, 0, 0.6) }
    
    contentItem: ColumnLayout {
        id: contentCol
        spacing: Theme.spacingLarge
        
        // Title
        Text {
            text: "Speech Settings"
            font.pixelSize: Theme.fontSizeLarge
            font.bold: true
            color: Theme.text
            Layout.fillWidth: true
        }
        
        // TTS Engine Toggle
        Text {
            text: "Text-to-Speech Engine"
            font.pixelSize: Theme.fontSizeNormal
            color: Theme.subText
        }
        
        RowLayout {
            Layout.fillWidth: true
            spacing: 0
            
            Button {
                text: "Azure TTS"
                Layout.fillWidth: true
                Layout.preferredHeight: 40
                
                contentItem: Text {
                    text: parent.text
                    font.pixelSize: Theme.fontSizeNormal
                    font.weight: Font.Medium
                    color: root.useAzure ? "#ffffff" : Theme.text
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                
                background: Rectangle {
                    radius: Theme.smallRadius
                    color: root.useAzure ? Theme.primary : Theme.surfaceHighlight
                    topRightRadius: 0
                    bottomRightRadius: 0
                }
                
                onClicked: root.useAzure = true
            }
            
            Button {
                text: "System TTS"
                Layout.fillWidth: true
                Layout.preferredHeight: 40
                
                contentItem: Text {
                    text: parent.text
                    font.pixelSize: Theme.fontSizeNormal
                    font.weight: Font.Medium
                    color: !root.useAzure ? "#ffffff" : Theme.text
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                
                background: Rectangle {
                    radius: Theme.smallRadius
                    color: !root.useAzure ? Theme.primary : Theme.surfaceHighlight
                    topLeftRadius: 0
                    bottomLeftRadius: 0
                }
                
                onClicked: root.useAzure = false
            }
        }
        
        // Azure Config (visible only when Azure selected)
        ColumnLayout {
            visible: root.useAzure
            Layout.fillWidth: true
            spacing: Theme.spacingMedium
            
            // Region / Endpoint
            TextField {
                Layout.fillWidth: true
                text: root.azureEndpoint
                placeholderText: "e.g., northeurope"
                onTextEdited: root.azureEndpoint = text
                color: Theme.text
                
                background: Rectangle {
                    color: Theme.background
                    radius: Theme.smallRadius
                    border.color: Theme.surfaceHighlight
                    border.width: 1
                }
                
                Label {
                    text: "Region / Endpoint"
                    font.pixelSize: Theme.fontSizeSmall
                    color: Theme.subText
                    anchors.bottom: parent.top
                    anchors.bottomMargin: 4
                }
            }
            
            Item { height: 12 }
            
            // Subscription Key
            TextField {
                Layout.fillWidth: true
                text: root.azureKey
                echoMode: TextInput.Normal
                placeholderText: "Your subscription key"
                onTextEdited: root.azureKey = text
                color: Theme.text
                wrapMode: TextInput.Wrap
                
                background: Rectangle {
                    color: Theme.background
                    radius: Theme.smallRadius
                    border.color: Theme.surfaceHighlight
                    border.width: 1
                }
                
                Label {
                    text: "Subscription Key"
                    font.pixelSize: Theme.fontSizeSmall
                    color: Theme.subText
                    anchors.bottom: parent.top
                    anchors.bottomMargin: 4
                }
            }
        }
        
        // Virtual Microphone
        RowLayout {
            Layout.fillWidth: true
            spacing: 12
            
            CheckBox {
                id: virtualMicCheck
                checked: root.useVirtualMic
                onCheckedChanged: root.useVirtualMic = checked
                
                indicator: Rectangle {
                    width: 20; height: 20
                    radius: 4
                    color: virtualMicCheck.checked ? Theme.primary : "transparent"
                    border.color: virtualMicCheck.checked ? Theme.primary : Theme.surfaceLight
                    border.width: 2
                    
                    Text {
                        visible: virtualMicCheck.checked
                        text: "✓"
                        color: "#ffffff"
                        anchors.centerIn: parent
                        font.pixelSize: 14
                        font.bold: true
                    }
                }
            }
            
            ColumnLayout {
                spacing: 2
                Layout.fillWidth: true
                
                Text {
                    text: "Use virtual microphone for calls"
                    font.pixelSize: Theme.fontSizeNormal
                    color: Theme.text
                }
                Text {
                    text: "Routes TTS audio to a virtual device you can pick as mic in Zoom/Meet."
                    font.pixelSize: Theme.fontSizeSmall
                    color: Theme.subText
                    wrapMode: Text.Wrap
                    Layout.fillWidth: true
                }
            }
        }
        
        // UI Scaling
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 8
            
            Text {
                text: "UI Scaling"
                font.pixelSize: Theme.fontSizeNormal
                font.bold: true
                color: Theme.text
            }
            
            Slider {
                id: scaleSlider
                Layout.fillWidth: true
                from: 0.8; to: 2.0
                value: root.uiScale
                stepSize: 0.1
                onMoved: root.uiScale = value
                
                background: Rectangle {
                    x: scaleSlider.leftPadding
                    y: scaleSlider.topPadding + scaleSlider.availableHeight / 2 - height / 2
                    width: scaleSlider.availableWidth
                    height: 12
                    radius: 6
                    color: Theme.surfaceHighlight
                    
                    Rectangle {
                        width: scaleSlider.visualPosition * parent.width
                        height: parent.height
                        radius: 6
                        color: Theme.primary
                    }
                }
                
                handle: Rectangle {
                    x: scaleSlider.leftPadding + scaleSlider.visualPosition * (scaleSlider.availableWidth - width)
                    y: scaleSlider.topPadding + scaleSlider.availableHeight / 2 - height / 2
                    width: 20; height: 20
                    radius: 10
                    color: Theme.primary
                }
            }
        }
        
        // Buttons
        RowLayout {
            Layout.fillWidth: true
            spacing: 12
            
            Item { Layout.fillWidth: true }
            
            Button {
                text: "Cancel"
                contentItem: Text {
                    text: parent.text
                    font.pixelSize: Theme.fontSizeNormal
                    color: Theme.subText
                    horizontalAlignment: Text.AlignHCenter
                }
                background: Item {}
                onClicked: root.close()
            }
            
            ModernButton {
                text: "Save"
                primary: true
                onClicked: {
                    root.saved();
                    root.close();
                }
            }
        }
    }
}

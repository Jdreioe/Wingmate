import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic
import "../components"

// Per-voice settings dialog
Popup {
    id: root
    
    property string baseUrl: ""
    property string voiceName: ""
    property string engineName: "Azure TTS"
    property string engineDescription: "Using Microsoft Azure Cognitive Services"
    property string language: ""
    property real pitchValue: 1.0
    property real rateValue: 1.0
    
    signal testVoiceRequested()
    signal changeEngineRequested()
    signal saved()
    
    anchors.centerIn: parent
    width: 480
    height: contentCol.implicitHeight + 60
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
        id: contentCol
        spacing: Theme.spacingLarge
        
        // Title
        Text {
            text: "Voice Settings - " + root.voiceName
            font.pixelSize: Theme.fontSizeHeader
            font.bold: true
            color: Theme.text
            Layout.fillWidth: true
        }
        
        // Engine Info Card
        Rectangle {
            Layout.fillWidth: true
            height: 56
            radius: Theme.smallRadius
            color: Theme.primary
            
            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 12
                spacing: 2
                
                Text {
                    text: "Current Engine: " + root.engineName
                    font.pixelSize: Theme.fontSizeNormal
                    font.bold: true
                    color: "#ffffff"
                }
                Text {
                    text: root.engineDescription
                    font.pixelSize: Theme.fontSizeSmall
                    color: Qt.rgba(1, 1, 1, 0.8)
                }
            }
        }
        
        // Language
        Text {
            text: "Select language: " + root.language
            font.pixelSize: Theme.fontSizeNormal
            color: Theme.text
        }
        
        // Pitch Slider
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4
            
            Text {
                text: "Pitch: " + root.pitchValue.toFixed(2)
                font.pixelSize: Theme.fontSizeNormal
                color: Theme.text
            }
            
            Slider {
                id: pitchSlider
                Layout.fillWidth: true
                from: 0.0; to: 2.0
                value: root.pitchValue
                stepSize: 0.01
                onMoved: root.pitchValue = value
                
                background: Rectangle {
                    x: pitchSlider.leftPadding
                    y: pitchSlider.topPadding + pitchSlider.availableHeight / 2 - height / 2
                    width: pitchSlider.availableWidth
                    height: 6
                    radius: 3
                    color: Theme.surfaceHighlight
                    
                    Rectangle {
                        width: pitchSlider.visualPosition * parent.width
                        height: parent.height
                        radius: 3
                        color: Theme.primary
                    }
                }
                
                handle: Rectangle {
                    x: pitchSlider.leftPadding + pitchSlider.visualPosition * (pitchSlider.availableWidth - width)
                    y: pitchSlider.topPadding + pitchSlider.availableHeight / 2 - height / 2
                    width: 18; height: 18
                    radius: 9
                    color: Theme.primaryLight
                }
            }
        }
        
        // Rate Slider
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4
            
            Text {
                text: "Rate: " + root.rateValue.toFixed(2)
                font.pixelSize: Theme.fontSizeNormal
                color: Theme.text
            }
            
            Slider {
                id: rateSlider
                Layout.fillWidth: true
                from: 0.0; to: 2.0
                value: root.rateValue
                stepSize: 0.01
                onMoved: root.rateValue = value
                
                background: Rectangle {
                    x: rateSlider.leftPadding
                    y: rateSlider.topPadding + rateSlider.availableHeight / 2 - height / 2
                    width: rateSlider.availableWidth
                    height: 6
                    radius: 3
                    color: Theme.surfaceHighlight
                    
                    Rectangle {
                        width: rateSlider.visualPosition * parent.width
                        height: parent.height
                        radius: 3
                        color: Theme.primary
                    }
                }
                
                handle: Rectangle {
                    x: rateSlider.leftPadding + rateSlider.visualPosition * (rateSlider.availableWidth - width)
                    y: rateSlider.topPadding + rateSlider.availableHeight / 2 - height / 2
                    width: 18; height: 18
                    radius: 9
                    color: Theme.primaryLight
                }
            }
        }
        
        // Action Buttons
        RowLayout {
            Layout.fillWidth: true
            spacing: 12
            
            ModernButton {
                text: "Test Voice"
                primary: true
                Layout.fillWidth: true
                onClicked: root.testVoiceRequested()
            }
            
            ModernButton {
                text: "↹ Change Engine"
                Layout.fillWidth: true
                onClicked: root.changeEngineRequested()
            }
        }
        
        // Footer Buttons
        RowLayout {
            Layout.fillWidth: true
            spacing: 12
            
            Item { Layout.fillWidth: true }
            
            Button {
                text: "Save"
                contentItem: Text {
                    text: parent.text
                    font.pixelSize: Theme.fontSizeNormal
                    color: Theme.accent
                    horizontalAlignment: Text.AlignHCenter
                }
                background: Item {}
                onClicked: {
                    root.saved();
                    root.close();
                }
            }
            
            Button {
                text: "Close"
                contentItem: Text {
                    text: parent.text
                    font.pixelSize: Theme.fontSizeNormal
                    color: Theme.subText
                    horizontalAlignment: Text.AlignHCenter
                }
                background: Item {}
                onClicked: root.close()
            }
        }
    }
}

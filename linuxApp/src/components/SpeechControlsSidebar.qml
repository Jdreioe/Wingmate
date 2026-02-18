import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic
import org.kde.kirigami as Kirigami

// Right-side speech controls panel
Rectangle {
    id: root
    
    property string baseUrl: ""
    property string engineName: "Azure (Premium)"
    property string voiceName: "Brian Multilingual"
    property real pitchValue: 1.0
    property real speedValue: 1.0
    property string readAsMode: "Characters"
    
    signal changeEngineRequested()
    signal selectVoiceRequested()
    signal applySettings()
    signal insertPause(real duration)
    signal setEmphasis(string level)
    
    width: 280
    color: Theme.surface
    
    // Left border
    Rectangle {
        width: 1
        height: parent.height
        color: Theme.surfaceHighlight
    }
    
    Flickable {
        anchors.fill: parent
        anchors.margins: 16
        contentHeight: contentCol.height
        clip: true
        
        ColumnLayout {
            id: contentCol
            width: parent.width
            spacing: Theme.spacingMedium
            
            // Title
            Text {
                text: "Speech Controls"
                font.pixelSize: Theme.fontSizeMedium
                font.bold: true
                color: Theme.accent
                Layout.fillWidth: true
            }
            
            // Engine Section
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                
                ColumnLayout {
                    spacing: 2
                    Layout.fillWidth: true
                    Text {
                        text: "Engine"
                        font.pixelSize: Theme.fontSizeSmall
                        color: Theme.subText
                    }
                    Text {
                        text: root.engineName
                        font.pixelSize: Theme.fontSizeNormal
                        font.bold: true
                        color: Theme.text
                    }
                }
                
                Button {
                    implicitWidth: 32
                    implicitHeight: 32
                    contentItem: Kirigami.Icon {
                        source: "exchange-positions"
                        implicitWidth: 18
                        implicitHeight: 18
                    }
                    background: Rectangle {
                        radius: 16
                        color: parent.hovered ? Theme.surfaceHighlight : "transparent"
                    }
                    onClicked: root.changeEngineRequested()
                }
            }
            
            // Voice name
            Text {
                text: "Voice: " + root.voiceName
                font.pixelSize: Theme.fontSizeSmall
                color: Theme.subText
                wrapMode: Text.Wrap
                Layout.fillWidth: true
                
                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onClicked: root.selectVoiceRequested()
                }
            }
            
            // Separator
            Rectangle { Layout.fillWidth: true; height: 1; color: Theme.surfaceHighlight }
            
            // Tone & Speed Section
            Text {
                text: "Tone & Speed"
                font.pixelSize: Theme.fontSizeNormal
                font.bold: true
                color: Theme.text
            }
            
            // Pitch slider
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                
                Text {
                    text: "Pitch"
                    font.pixelSize: Theme.fontSizeSmall
                    color: Theme.subText
                    Layout.preferredWidth: 40
                }
                
                Slider {
                    id: pitchSlider
                    Layout.fillWidth: true
                    from: 0.0; to: 2.0
                    value: root.pitchValue
                    stepSize: 0.1
                    
                    background: Rectangle {
                        x: pitchSlider.leftPadding
                        y: pitchSlider.topPadding + pitchSlider.availableHeight / 2 - height / 2
                        width: pitchSlider.availableWidth
                        height: 4
                        radius: 2
                        color: Theme.surfaceHighlight
                        
                        Rectangle {
                            width: pitchSlider.visualPosition * parent.width
                            height: parent.height
                            radius: 2
                            color: Theme.primary
                        }
                    }
                    
                    handle: Rectangle {
                        x: pitchSlider.leftPadding + pitchSlider.visualPosition * (pitchSlider.availableWidth - width)
                        y: pitchSlider.topPadding + pitchSlider.availableHeight / 2 - height / 2
                        width: 16; height: 16
                        radius: 8
                        color: Theme.primary
                        border.color: Theme.primaryLight
                        border.width: 2
                    }
                    
                    onMoved: root.pitchValue = value
                }
                
                Text {
                    text: root.pitchValue.toFixed(1)
                    font.pixelSize: Theme.fontSizeSmall
                    color: Theme.text
                    Layout.preferredWidth: 28
                    horizontalAlignment: Text.AlignRight
                }
            }
            
            // Speed slider
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                
                Text {
                    text: "Speed"
                    font.pixelSize: Theme.fontSizeSmall
                    color: Theme.subText
                    Layout.preferredWidth: 40
                }
                
                Slider {
                    id: speedSlider
                    Layout.fillWidth: true
                    from: 0.0; to: 2.0
                    value: root.speedValue
                    stepSize: 0.1
                    
                    background: Rectangle {
                        x: speedSlider.leftPadding
                        y: speedSlider.topPadding + speedSlider.availableHeight / 2 - height / 2
                        width: speedSlider.availableWidth
                        height: 4
                        radius: 2
                        color: Theme.surfaceHighlight
                        
                        Rectangle {
                            width: speedSlider.visualPosition * parent.width
                            height: parent.height
                            radius: 2
                            color: Theme.primary
                        }
                    }
                    
                    handle: Rectangle {
                        x: speedSlider.leftPadding + speedSlider.visualPosition * (speedSlider.availableWidth - width)
                        y: speedSlider.topPadding + speedSlider.availableHeight / 2 - height / 2
                        width: 16; height: 16
                        radius: 8
                        color: Theme.primary
                        border.color: Theme.primaryLight
                        border.width: 2
                    }
                    
                    onMoved: root.speedValue = value
                }
                
                Text {
                    text: root.speedValue.toFixed(1)
                    font.pixelSize: Theme.fontSizeSmall
                    color: Theme.text
                    Layout.preferredWidth: 28
                    horizontalAlignment: Text.AlignRight
                }
            }
            
            // Apply Settings button
            ModernButton {
                text: "Apply Settings"
                primary: true
                Layout.fillWidth: true
                onClicked: root.applySettings()
            }
            
            // Separator
            Rectangle { Layout.fillWidth: true; height: 1; color: Theme.surfaceHighlight }
            
            // Add Pause section
            Text {
                text: "Add Pause"
                font.pixelSize: Theme.fontSizeNormal
                font.bold: true
                color: Theme.text
            }
            
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                
                Chip {
                    text: "0.5s"
                    onClicked: root.insertPause(0.5)
                }
                Chip {
                    text: "1.0s"
                    onClicked: root.insertPause(1.0)
                }
                Chip {
                    text: "2.0s"
                    onClicked: root.insertPause(2.0)
                }
            }
            
            // Separator
            Rectangle { Layout.fillWidth: true; height: 1; color: Theme.surfaceHighlight }
            
            // Emphasis section
            Text {
                text: "Emphasis"
                font.pixelSize: Theme.fontSizeNormal
                font.bold: true
                color: Theme.text
            }
            
            Text {
                text: "Highlight the next word with:"
                font.pixelSize: Theme.fontSizeSmall
                color: Theme.subText
            }
            
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                
                Chip {
                    text: "Reduced"
                    onClicked: root.setEmphasis("reduced")
                }
                Chip {
                    text: "Moderate"
                    onClicked: root.setEmphasis("moderate")
                }
                Chip {
                    text: "Strong"
                    onClicked: root.setEmphasis("strong")
                }
            }
            
            // Separator
            Rectangle { Layout.fillWidth: true; height: 1; color: Theme.surfaceHighlight }
            
            // Read As section
            Text {
                text: "Read As..."
                font.pixelSize: Theme.fontSizeNormal
                font.bold: true
                color: Theme.text
            }
            
            ComboBox {
                id: readAsCombo
                Layout.fillWidth: true
                model: ["Characters", "Words", "Sentences"]
                currentIndex: {
                    var items = ["Characters", "Words", "Sentences"];
                    for (var i = 0; i < items.length; i++) {
                        if (items[i] === root.readAsMode) return i;
                    }
                    return 0;
                }
                onActivated: root.readAsMode = currentText
                
                background: Rectangle {
                    radius: height / 2
                    color: Theme.surfaceHighlight
                    border.color: Theme.surfaceLight
                    border.width: 1
                }
                
                contentItem: Text {
                    leftPadding: 12
                    text: readAsCombo.displayText
                    font.pixelSize: Theme.fontSizeSmall
                    color: Theme.text
                    verticalAlignment: Text.AlignVCenter
                }
            }
            
            // Info note
            RowLayout {
                Layout.fillWidth: true
                spacing: 6
                
                Rectangle {
                    width: 8; height: 8
                    radius: 4
                    color: Theme.accent
                }
                
                Text {
                    text: "Switch to Azure engines to hear the rhythm/quality changes."
                    font.pixelSize: Theme.fontSizeSmall - 1
                    color: Theme.subText
                    wrapMode: Text.Wrap
                    Layout.fillWidth: true
                    font.italic: true
                }
            }
        }
    }
}

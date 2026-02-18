import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic
import "../components"

Popup {
    id: root
    
    // Properties to interface with main app
    property string baseUrl: ""
    property var availableLanguages: []
    
    signal completed()
    signal configureVoiceRequested()
    
    anchors.centerIn: parent
    width: Math.min(600, parent.width - 40)
    height: Math.min(500, parent.height - 40)
    modal: true
    dim: true
    closePolicy: Popup.NoAutoClose
    
    background: Rectangle {
        color: Theme.background
        radius: Theme.largeRadius
        border.color: Theme.surfaceHighlight
        border.width: 1
    }
    
    Overlay.modal: Rectangle { color: Qt.rgba(0, 0, 0, 0.8) }
    
    // Slides
    SwipeView {
        id: swipeView
        anchors.fill: parent
        anchors.bottomMargin: 80
        interactive: false // Controlled by buttons
        
        // Slide 1: Welcome
        Item {
            ColumnLayout {
                anchors.centerIn: parent
                spacing: 24
                width: parent.width - 60
                
                Text {
                    text: "Welcome to Wingmate"
                    font.pixelSize: Theme.fontSizeTitle
                    font.bold: true
                    color: Theme.primary
                    horizontalAlignment: Text.AlignHCenter
                    Layout.fillWidth: true
                }
                
                Text {
                    text: "Your personal communication aid."
                    font.pixelSize: Theme.fontSizeLarge
                    color: Theme.text
                    horizontalAlignment: Text.AlignHCenter
                    Layout.fillWidth: true
                }
                
                Text {
                    text: "Let's get you set up in just a few steps."
                    font.pixelSize: Theme.fontSizeNormal
                    color: Theme.subText
                    horizontalAlignment: Text.AlignHCenter
                    Layout.fillWidth: true
                }
            }
        }
        
        // Slide 2: Voice Setup
        Item {
            ColumnLayout {
                anchors.centerIn: parent
                spacing: 24
                width: parent.width - 60
                
                Text {
                    text: "Voice Setup"
                    font.pixelSize: Theme.fontSizeLarge
                    font.bold: true
                    color: Theme.text
                    horizontalAlignment: Text.AlignHCenter
                    Layout.fillWidth: true
                }
                
                Text {
                    text: "Choose your primary language and voice engine."
                    font.pixelSize: Theme.fontSizeNormal
                    color: Theme.subText
                    horizontalAlignment: Text.AlignHCenter
                    Layout.fillWidth: true
                    wrapMode: Text.Wrap
                }
                
                Item { height: 20 }
                
                ModernButton {
                    text: "Configure Voice & Language"
                    Layout.alignment: Qt.AlignHCenter
                    onClicked: root.configureVoiceRequested()
                }
                
                Text {
                    text: "You can always change this later in Settings."
                    font.pixelSize: Theme.fontSizeSmall
                    color: Theme.subText
                    horizontalAlignment: Text.AlignHCenter
                    Layout.fillWidth: true
                    topPadding: 20
                }
            }
        }
        
        // Slide 3: All Set
        Item {
            ColumnLayout {
                anchors.centerIn: parent
                spacing: 24
                width: parent.width - 60
                
                Text {
                    text: "You're All Set!"
                    font.pixelSize: Theme.fontSizeLarge
                    font.bold: true
                    color: Theme.success
                    horizontalAlignment: Text.AlignHCenter
                    Layout.fillWidth: true
                }
                
                Text {
                    text: "Start adding phrases and categories to customize your experience."
                    font.pixelSize: Theme.fontSizeNormal
                    color: Theme.text
                    horizontalAlignment: Text.AlignHCenter
                    Layout.fillWidth: true
                    wrapMode: Text.Wrap
                }
                
                ModernButton {
                    text: "Get Started"
                    primary: true
                    Layout.alignment: Qt.AlignHCenter
                    Layout.topMargin: 20
                    onClicked: {
                        // Save completion
                        var xhr = new XMLHttpRequest();
                        xhr.onreadystatechange = function() {
                            if (xhr.readyState === XMLHttpRequest.DONE) {
                                root.completed();
                                root.close();
                                print("Welcome flow completed")
                            }
                        }

                        xhr.open("PUT", root.baseUrl + "/api/settings");
                        xhr.setRequestHeader("Content-Type", "application/json");
                        xhr.send(JSON.stringify({ welcomeFlowCompleted: true }));
                    }
                }
            }
        }
    }
    
    PageIndicator {
        id: indicator
        count: swipeView.count
        currentIndex: swipeView.currentIndex
        anchors.bottom: parent.bottom
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottomMargin: 20
        
        delegate: Rectangle {
            implicitWidth: 8
            implicitHeight: 8
            radius: width / 2
            color: index === indicator.currentIndex ? Theme.primary : Theme.surfaceHighlight
            
            Behavior on color { ColorAnimation { duration: 100 } }
        }
    }
    
    // Navigation Buttons (Next/Back) usually better than swipe for wizards
    RowLayout {
        anchors.bottom: parent.bottom
        anchors.right: parent.right
        anchors.margins: 20
        spacing: 12
        visible: swipeView.currentIndex < swipeView.count - 1
        
        Button {
            text: "Next"
            flat: true
            contentItem: Text {
                text: parent.text
                color: Theme.primary
                font.bold: true
            }
            background: Item {}
            onClicked: swipeView.currentIndex++
        }
    }
}

import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic
import org.kde.kirigami as Kirigami

// Bottom playback control bar
Rectangle {
    id: root
    
    signal playClicked()
    signal settingsClicked()
    signal pauseClicked()
    signal stopClicked()
    
    property bool isPlaying: false
    property bool isPaused: false
    
    color: Theme.surface
    height: 56
    
    // Top border line
    Rectangle {
        width: parent.width
        height: 1
        color: Theme.surfaceHighlight
    }
    
    RowLayout {
        anchors.centerIn: parent
        spacing: 16
        
        // Play Button
        Button {
            id: playBtn
            implicitWidth: 44
            implicitHeight: 44
            
            contentItem: Kirigami.Icon {
                source: "media-playback-start"
                implicitWidth: 20
                implicitHeight: 20
                opacity: playBtn.enabled ? 1.0 : 0.4
            }
            
            background: Rectangle {
                radius: 22
                color: {
                    if (playBtn.down) return Qt.darker(Theme.primary, 1.1);
                    if (playBtn.hovered) return Qt.lighter(Theme.primary, 1.1);
                    return "transparent";
                }
                border.color: Theme.surfaceHighlight
                border.width: playBtn.hovered ? 1 : 0
                Behavior on color { ColorAnimation { duration: 100 } }
            }
            
            onClicked: root.playClicked()
        }
        
        // Settings Gear
        Button {
            id: settingsBtn
            implicitWidth: 44
            implicitHeight: 44
            
            contentItem: Kirigami.Icon {
                source: "settings-configure"
                implicitWidth: 20
                implicitHeight: 20
            }
            
            background: Rectangle {
                radius: 22
                color: {
                    if (settingsBtn.down) return Theme.surfaceHighlight;
                    if (settingsBtn.hovered) return Theme.surfaceHighlight;
                    return "transparent";
                }
                Behavior on color { ColorAnimation { duration: 100 } }
            }
            
            onClicked: root.settingsClicked()
        }
        
        // Pause Button
        Button {
            id: pauseBtn
            implicitWidth: 44
            implicitHeight: 44
            
            contentItem: Kirigami.Icon {
                source: "media-playback-pause"
                implicitWidth: 20
                implicitHeight: 20
            }
            
            background: Rectangle {
                radius: 22
                color: {
                    if (pauseBtn.down) return Theme.surfaceHighlight;
                    if (pauseBtn.hovered) return Theme.surfaceHighlight;
                    return "transparent";
                }
                Behavior on color { ColorAnimation { duration: 100 } }
            }
            
            onClicked: root.pauseClicked()
        }
        
        // Stop Button
        Button {
            id: stopBtn
            implicitWidth: 44
            implicitHeight: 44
            
            contentItem: Kirigami.Icon {
                source: "media-playback-stop"
                implicitWidth: 20
                implicitHeight: 20
            }
            
            background: Rectangle {
                radius: 22
                color: {
                    if (stopBtn.down) return Theme.surfaceHighlight;
                    if (stopBtn.hovered) return Theme.surfaceHighlight;
                    return "transparent";
                }
                Behavior on color { ColorAnimation { duration: 100 } }
            }
            
            onClicked: root.stopClicked()
        }
    }
}

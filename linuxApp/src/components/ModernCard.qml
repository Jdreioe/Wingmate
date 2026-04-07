import QtQuick
import QtQuick.Layouts
import QtQuick.Effects

Item {
    id: root
    default property alias content: internalLayout.data
    property string title: ""
    property alias header: headerLoader.sourceComponent
    
    // Styling
    property int elevation: 2
    property bool glass: false
    
    implicitWidth: 300
    implicitHeight: mainLayout.implicitHeight + 32 // 16px margin top + bottom

    Rectangle {
        id: bg
        anchors.fill: parent
        radius: Theme.radius
        color: glass ? Qt.rgba(Theme.surface.r, Theme.surface.g, Theme.surface.b, 0.7) : Theme.surface
        border.color: Theme.surfaceHighlight
        border.width: 1
        
        layer.enabled: true
        layer.effect: MultiEffect {
            shadowEnabled: true
            shadowBlur: root.elevation * 4
            shadowVerticalOffset: root.elevation
            shadowColor: Qt.rgba(0,0,0,0.3)
            blurEnabled: root.glass
            blur: 32
        }
    }
    
    ColumnLayout {
        id: mainLayout
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 16
        spacing: 12
        
        // Header Title if simple string
        Text {
            visible: root.title !== ""
            text: root.title
            font.pixelSize: Theme.fontSizeHeader
            font.bold: true
            color: Theme.text
            Layout.fillWidth: true
        }
        
        // Custom Header
        Loader {
            id: headerLoader
            active: sourceComponent !== null
            visible: active
            Layout.fillWidth: true
        }
        
        // Content
        ColumnLayout {
            id: internalLayout
            Layout.fillWidth: true
            Layout.fillHeight: true
        }
    }
}

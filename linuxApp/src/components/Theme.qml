pragma Singleton
import QtQuick

QtObject {
    // Colors (Catppuccin Mocha inspired)
    readonly property color background: "#1E1E2E"
    readonly property color surface: "#313244"
    readonly property color surfaceHighlight: "#45475A"
    readonly property color text: "#CDD6F4"
    readonly property color subText: "#A6ADC8"
    readonly property color primary: "#89B4FA"
    readonly property color secondary: "#F5C2E7"
    readonly property color accent: "#CBA6F7"
    readonly property color error: "#F38BA8"
    readonly property color success: "#A6E3A1"

    // Dimensions
    readonly property int radius: 12
    readonly property int smallRadius: 8
    
    // Fonts
    readonly property string fontFamily: "Noto Sans"
    readonly property int fontSizeSmall: 12
    readonly property int fontSizeNormal: 14
    readonly property int fontSizeHeader: 24
    readonly property int fontSizeLarge: 32
}

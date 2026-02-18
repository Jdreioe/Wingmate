pragma Singleton
import QtQuick

// Theme singleton using Qt SystemPalette for true OS theme matching.
// Now backed by Kirigami.ApplicationWindow and org.kde.desktop style in main.rs.
Item {
    SystemPalette {
        id: activePalette
        colorGroup: SystemPalette.Active
    }

    // --- Colors (Dynamic from OS) ---
    readonly property color background: activePalette.window
    readonly property color surface: activePalette.base // Views usually use 'base' (white/dark grey)
    // Use proper alpha tinting for variations
    readonly property color surfaceHighlight: Qt.tint(activePalette.window, Qt.rgba(1, 1, 1, 0.1)) 
    readonly property color surfaceLight: Qt.tint(activePalette.window, Qt.rgba(1, 1, 1, 0.2))
    
    readonly property color text: activePalette.windowText
    readonly property color subText: activePalette.mid
    
    readonly property color primary: activePalette.highlight
    readonly property color primaryLight: Qt.lighter(activePalette.highlight, 1.2)
    readonly property color primaryDark: Qt.darker(activePalette.highlight, 1.2)
    
    readonly property color secondary: activePalette.highlight
    readonly property color accent: activePalette.highlight
    
    readonly property color error: "#ed8796" // Keep safe defaults for status colors if not in palette
    readonly property color success: "#a6da95"
    readonly property color warning: "#eed49f"

    // --- Dimensions ---
    readonly property int radius: 6
    readonly property int smallRadius: 4
    readonly property int largeRadius: 8

    // --- Fonts ---
    readonly property int fontSizeSmall: 11
    readonly property int fontSizeNormal: 13
    readonly property int fontSizeMedium: 15
    readonly property int fontSizeHeader: 22
    readonly property int fontSizeLarge: 28
    readonly property int fontSizeTitle: 32

    // --- Spacing ---
    readonly property int spacingSmall: 4
    readonly property int spacingNormal: 8
    readonly property int spacingMedium: 12
    readonly property int spacingLarge: 16
    readonly property int spacingXLarge: 24

    // --- Elevation / Shadow ---
    readonly property real shadowOpacity: 0.3
}

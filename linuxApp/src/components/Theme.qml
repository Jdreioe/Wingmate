pragma Singleton
import QtQuick
import org.kde.kirigami as Kirigami

// Theme singleton that proxies the user's OS/KDE theme (Breeze, Breeze Dark, etc.)
// via Kirigami.Theme attached properties.
Item {
    Kirigami.Theme.inherit: false

    // --- Colors (from OS theme, with contrast adjustments) ---
    readonly property color background: Kirigami.Theme.backgroundColor
    readonly property color surface: Qt.lighter(Kirigami.Theme.backgroundColor, 1.25)
    readonly property color surfaceHighlight: Qt.lighter(Kirigami.Theme.backgroundColor, 1.5)
    readonly property color surfaceLight: Qt.lighter(Kirigami.Theme.backgroundColor, 1.8)
    readonly property color text: Kirigami.Theme.textColor
    readonly property color subText: Kirigami.Theme.disabledTextColor
    readonly property color primary: Kirigami.Theme.highlightColor
    readonly property color primaryLight: Qt.lighter(Kirigami.Theme.highlightColor, 1.2)
    readonly property color primaryDark: Qt.darker(Kirigami.Theme.highlightColor, 1.2)
    readonly property color secondary: Kirigami.Theme.linkColor
    readonly property color accent: Kirigami.Theme.activeTextColor
    readonly property color error: Kirigami.Theme.negativeTextColor
    readonly property color success: Kirigami.Theme.positiveTextColor
    readonly property color warning: Kirigami.Theme.neutralTextColor

    // --- Dimensions ---
    readonly property int radius: 12
    readonly property int smallRadius: 8
    readonly property int largeRadius: 16

    // --- Fonts (use system default) ---
    readonly property int fontSizeSmall: 12
    readonly property int fontSizeNormal: 14
    readonly property int fontSizeMedium: 16
    readonly property int fontSizeHeader: 24
    readonly property int fontSizeLarge: 32
    readonly property int fontSizeTitle: 36

    // --- Spacing ---
    readonly property int spacingSmall: 4
    readonly property int spacingNormal: 8
    readonly property int spacingMedium: 12
    readonly property int spacingLarge: 16
    readonly property int spacingXLarge: 24

    // --- Elevation / Shadow ---
    readonly property real shadowOpacity: 0.3
}

import QtQuick
import QtQuick.Layouts
import QtQuick.Controls.Basic as Controls

/**
 * On-screen keyboard with swipe-to-type and n-gram prediction.
 * Designed for AAC (touch / eye-tracker) usage.
 */
Item {
    id: osk

    property string baseUrl: ""
    property bool shifted: false
    property bool capsLock: false
    property bool symbolMode: false
    property var predictions: []
    property bool predictionLoading: false
    property string currentText: ""      // Bound from parent: current input text
    property bool active: false          // Whether the OSK is currently shown
    property real keyboardScale: 1.0     // Scale multiplier for key sizes (0.5–2.0)
    property string language: "en-US"    // Primary language — drives keyboard layout

    // Current layout rows (computed from language)
    readonly property var _layout: layoutForLanguage(language)
    readonly property var _row1: _layout.row1
    readonly property var _row2: _layout.row2
    readonly property var _row3: _layout.row3

    signal keyPressed(string key)
    signal backspacePressed()
    signal enterPressed()
    signal spacePressed()
    signal predictionSelected(string word)

    implicitHeight: predBar.height + keyboardGrid.height + 8

    // ── Prediction bar ──
    PredictionBar {
        id: predBar
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        predictions: osk.predictions
        loading: osk.predictionLoading

        onWordSelected: (word) => osk.predictionSelected(word)
    }

    // ── Keyboard grid ──
    ColumnLayout {
        id: keyboardGrid
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: predBar.bottom
        anchors.topMargin: 4
        spacing: 4

        // Row properties — scaled by keyboardScale
        property real keyHeight: Math.round(48 * osk.keyboardScale)
        property real keySpacing: Math.round(4 * osk.keyboardScale)

        // ── Letter rows ──
        // Row 1: QWERTYUIOP / Numbers
        RowLayout {
            Layout.fillWidth: true
            spacing: keyboardGrid.keySpacing

            Repeater {
                model: osk.symbolMode
                    ? ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"]
                    : osk._row1

                KeyButton {
                    Layout.fillWidth: true
                    Layout.preferredHeight: keyboardGrid.keyHeight
                    text: (osk.shifted || osk.capsLock) && !osk.symbolMode
                        ? modelData.toUpperCase()
                        : modelData
                    onClicked: emitKey(text)
                }
            }
        }

        // Row 2: ASDFGHJKL / Symbols
        RowLayout {
            Layout.fillWidth: true
            spacing: keyboardGrid.keySpacing

            Item { Layout.preferredWidth: 16; visible: osk._row2.length < osk._row1.length } // left indent

            Repeater {
                model: osk.symbolMode
                    ? ["@", "#", "$", "%", "&", "-", "+", "(", ")"]
                    : osk._row2

                KeyButton {
                    Layout.fillWidth: true
                    Layout.preferredHeight: keyboardGrid.keyHeight
                    text: (osk.shifted || osk.capsLock) && !osk.symbolMode
                        ? modelData.toUpperCase()
                        : modelData
                    onClicked: emitKey(text)
                }
            }

            Item { Layout.preferredWidth: 16; visible: osk._row2.length < osk._row1.length } // right indent
        }

        // Row 3: Shift + ZXCVBNM + Backspace / More symbols
        RowLayout {
            Layout.fillWidth: true
            spacing: keyboardGrid.keySpacing

            // Shift key
            KeyButton {
                Layout.preferredWidth: 56
                Layout.preferredHeight: keyboardGrid.keyHeight
                text: osk.capsLock ? "⇪" : "⇧"
                highlighted: osk.shifted || osk.capsLock
                onClicked: {
                    if (osk.capsLock) {
                        osk.capsLock = false;
                        osk.shifted = false;
                    } else if (osk.shifted) {
                        osk.capsLock = true;
                    } else {
                        osk.shifted = true;
                    }
                }
            }

            Repeater {
                model: osk.symbolMode
                    ? ["*", "\"", "'", ":", ";", "!", "?"]
                    : osk._row3

                KeyButton {
                    Layout.fillWidth: true
                    Layout.preferredHeight: keyboardGrid.keyHeight
                    text: (osk.shifted || osk.capsLock) && !osk.symbolMode
                        ? modelData.toUpperCase()
                        : modelData
                    onClicked: emitKey(text)
                }
            }

            // Backspace key
            KeyButton {
                Layout.preferredWidth: 56
                Layout.preferredHeight: keyboardGrid.keyHeight
                text: "⌫"
                isRepeat: true
                onClicked: osk.backspacePressed()
            }
        }

        // Row 4: Symbol toggle + comma + space + period + enter
        RowLayout {
            Layout.fillWidth: true
            spacing: keyboardGrid.keySpacing

            KeyButton {
                Layout.preferredWidth: 72
                Layout.preferredHeight: keyboardGrid.keyHeight
                text: osk.symbolMode ? "ABC" : "?123"
                onClicked: osk.symbolMode = !osk.symbolMode
            }

            KeyButton {
                Layout.preferredWidth: 48
                Layout.preferredHeight: keyboardGrid.keyHeight
                text: ","
                onClicked: emitKey(",")
            }

            // Spacebar (also swipe target)
            KeyButton {
                id: spaceBar
                Layout.fillWidth: true
                Layout.preferredHeight: keyboardGrid.keyHeight
                text: "Space"
                isSpace: true
                onClicked: osk.spacePressed()
            }

            KeyButton {
                Layout.preferredWidth: 48
                Layout.preferredHeight: keyboardGrid.keyHeight
                text: "."
                onClicked: emitKey(".")
            }

            KeyButton {
                Layout.preferredWidth: 72
                Layout.preferredHeight: keyboardGrid.keyHeight
                text: "↵"
                highlighted: true
                onClicked: osk.enterPressed()
            }
        }
    }

    // ── Swipe detection overlay ──
    // Covers the entire letter area to track swipe gestures
    Item {
        id: swipeOverlay
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: predBar.bottom
        anchors.topMargin: 4
        height: keyboardGrid.height

        property bool swiping: false
        property var swipePath: []          // [{x, y, time}]
        property var swipeKeySequence: []   // Keys hit along swipe
        property point lastPoint: Qt.point(0, 0)

        // Visual trail
        Canvas {
            id: swipeCanvas
            anchors.fill: parent
            visible: swipeOverlay.swiping
            z: 100

            onPaint: {
                var ctx = getContext("2d");
                ctx.clearRect(0, 0, width, height);
                if (swipeOverlay.swipePath.length < 2) return;

                ctx.strokeStyle = Qt.rgba(
                    Theme.primary.r, Theme.primary.g, Theme.primary.b, 0.6);
                ctx.lineWidth = 3;
                ctx.lineCap = "round";
                ctx.lineJoin = "round";
                ctx.beginPath();
                ctx.moveTo(swipeOverlay.swipePath[0].x, swipeOverlay.swipePath[0].y);
                for (var i = 1; i < swipeOverlay.swipePath.length; i++) {
                    ctx.lineTo(swipeOverlay.swipePath[i].x, swipeOverlay.swipePath[i].y);
                }
                ctx.stroke();
            }
        }

        MultiPointTouchArea {
            anchors.fill: parent
            mouseEnabled: true
            minimumTouchPoints: 1
            maximumTouchPoints: 1
            z: 50

            touchPoints: [
                TouchPoint { id: tp }
            ]

            onPressed: {
                swipeOverlay.swiping = true;
                swipeOverlay.swipePath = [{x: tp.x, y: tp.y, time: Date.now()}];
                swipeOverlay.swipeKeySequence = [];
                var key = keyAtPosition(tp.x, tp.y);
                if (key) swipeOverlay.swipeKeySequence.push(key);
                swipeOverlay.lastPoint = Qt.point(tp.x, tp.y);
            }

            onUpdated: {
                if (!swipeOverlay.swiping) return;
                var dx = tp.x - swipeOverlay.lastPoint.x;
                var dy = tp.y - swipeOverlay.lastPoint.y;
                var dist = Math.sqrt(dx*dx + dy*dy);

                // Only record if moved enough
                if (dist > 5) {
                    swipeOverlay.swipePath.push({x: tp.x, y: tp.y, time: Date.now()});
                    swipeOverlay.lastPoint = Qt.point(tp.x, tp.y);

                    var key = keyAtPosition(tp.x, tp.y);
                    if (key && key !== swipeOverlay.swipeKeySequence[swipeOverlay.swipeKeySequence.length - 1]) {
                        swipeOverlay.swipeKeySequence.push(key);
                    }

                    swipeCanvas.requestPaint();
                }
            }

            onReleased: {
                if (!swipeOverlay.swiping) return;
                swipeOverlay.swiping = false;
                swipeCanvas.requestPaint();

                var totalPath = calculatePathLength(swipeOverlay.swipePath);

                // Deduplicate to count distinct keys crossed
                var uniqueKeys = [swipeOverlay.swipeKeySequence[0]];
                for (var ui = 1; ui < swipeOverlay.swipeKeySequence.length; ui++) {
                    if (swipeOverlay.swipeKeySequence[ui] !== swipeOverlay.swipeKeySequence[ui - 1])
                        uniqueKeys.push(swipeOverlay.swipeKeySequence[ui]);
                }

                // Treat as tap only if we crossed 1 or fewer distinct keys,
                // or the total path is very short (a small wiggle on one key)
                var isTap = (uniqueKeys.length <= 1) || (totalPath < 30);

                if (isTap) {
                    var key = keyAtPosition(tp.x, tp.y);
                    if (key) {
                        var displayKey = (osk.shifted || osk.capsLock) ? key.toUpperCase() : key;
                        emitKey(displayKey);
                    }
                } else {
                    // Swipe completed — resolve word from key sequence
                    resolveSwipeWord(swipeOverlay.swipeKeySequence);
                }

                // Clear trail
                swipeOverlay.swipePath = [];
            }
        }
    }

    // ── Helper functions ──

    function emitKey(key) {
        osk.keyPressed(key);
        // Auto-unshift after one keystroke (unless capsLock)
        if (osk.shifted && !osk.capsLock) {
            osk.shifted = false;
        }
    }

    // Map pixel position → key letter (only for letter rows, not modifiers)
    function keyAtPosition(x, y) {
        // Row geometry — approximate. Each row starts below the prediction bar.
        var rowHeight = keyboardGrid.keyHeight + keyboardGrid.keySpacing;
        var row = Math.floor(y / rowHeight);

        var rows;
        if (osk.symbolMode) {
            rows = [
                ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"],
                ["@", "#", "$", "%", "&", "-", "+", "(", ")"],
                ["*", "\"", "'", ":", ";", "!", "?"]
            ];
        } else {
            rows = [osk._row1, osk._row2, osk._row3];
        }

        if (row < 0 || row >= rows.length) return null;

        var keys = rows[row];
        var totalWidth = osk.width;

        // Account for indents on rows 2 and 3
        var leftOffset = 0;
        var availableWidth = totalWidth;
        if (row === 1 && osk._row2.length < osk._row1.length) {
            // Row 2 is indented only when it has fewer keys than row 1
            leftOffset = 16 + keyboardGrid.keySpacing;
            availableWidth = totalWidth - 32 - 2 * keyboardGrid.keySpacing;
        } else if (row === 2) {
            leftOffset = 56 + keyboardGrid.keySpacing; // shift key width
            availableWidth = totalWidth - 112 - 2 * keyboardGrid.keySpacing; // shift + backspace
        }

        var adjustedX = x - leftOffset;
        if (adjustedX < 0 || adjustedX > availableWidth) return null;

        var keyWidth = availableWidth / keys.length;
        var keyIndex = Math.floor(adjustedX / keyWidth);
        if (keyIndex < 0 || keyIndex >= keys.length) return null;

        return keys[keyIndex];
    }

    function calculatePathLength(path) {
        var total = 0;
        for (var i = 1; i < path.length; i++) {
            var dx = path[i].x - path[i-1].x;
            var dy = path[i].y - path[i-1].y;
            total += Math.sqrt(dx*dx + dy*dy);
        }
        return total;
    }

    // Resolve swipe key sequence to a word using prediction service
    function resolveSwipeWord(keySequence) {
        if (keySequence.length === 0) return;

        // Deduplicate consecutive keys (finger lingers over same key)
        var deduped = [keySequence[0]];
        for (var k = 1; k < keySequence.length; k++) {
            if (keySequence[k] !== keySequence[k - 1])
                deduped.push(keySequence[k]);
        }

        var pattern = deduped.join("");
        var firstChar = pattern.charAt(0);
        var lastChar = pattern.charAt(pattern.length - 1);
        console.log("[OSK] Swipe pattern (deduped): " + pattern + " (" + deduped.length + " keys)");

        // For short swipes (≤5 distinct keys), the deduped pattern likely IS the
        // intended word (e.g. h→e→j = "hej"). Use it directly as fallback.
        var shortWordFallback = (deduped.length <= 5) ? pattern : null;

        // Ask the prediction backend for candidate words starting with the first letter
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) {
                var words = [];
                if (xhr.status === 200) {
                    try {
                        var result = JSON.parse(xhr.responseText);
                        words = result.words || [];
                    } catch(e) {}
                }

                // Score all candidates against the swipe path
                var bestWord = findBestSwipeMatch(deduped, words);

                if (bestWord) {
                    osk.predictionSelected(bestWord);
                } else if (words.length > 0) {
                    // No confident match — show candidates in prediction bar
                    // Also include the raw pattern as a candidate if short enough
                    var suggestions = words.slice(0, 5);
                    if (shortWordFallback && suggestions.indexOf(shortWordFallback) === -1) {
                        suggestions.unshift(shortWordFallback);
                    }
                    osk.predictions = suggestions;
                } else if (shortWordFallback) {
                    // No API results at all — for short words, insert directly
                    console.log("[OSK] Swipe: using direct pattern: " + shortWordFallback);
                    osk.predictionSelected(shortWordFallback);
                } else {
                    console.log("[OSK] Swipe: no candidates for long pattern: " + pattern);
                }
            }
        };
        xhr.open("POST", osk.baseUrl + "/api/predict");
        xhr.setRequestHeader("Content-Type", "application/json");
        // Send just the first letter as the word-in-progress so the prediction
        // service returns words starting with that letter
        xhr.send(JSON.stringify({
            context: osk.currentText + firstChar,
            maxWords: 30,
            maxLetters: 0
        }));
    }

    function findBestSwipeMatch(swipeKeys, candidates) {
        if (candidates.length === 0 || swipeKeys.length === 0) return null;

        var firstChar = swipeKeys[0].toLowerCase();
        var lastChar = swipeKeys[swipeKeys.length - 1].toLowerCase();

        var scored = [];

        for (var i = 0; i < candidates.length; i++) {
            var word = candidates[i].toLowerCase();
            if (word.length < 2) continue;

            // Must start with first swiped key
            if (word.charAt(0) !== firstChar) continue;

            // Last char should match (but allow off-by-one for sloppy swipes)
            var lastWordChar = word.charAt(word.length - 1);
            var lastOk = (lastWordChar === lastChar);
            if (!lastOk && word.length > 2) {
                // Check if the second-to-last swipe key matches
                var penultimate = swipeKeys.length >= 2
                    ? swipeKeys[swipeKeys.length - 2].toLowerCase() : "";
                lastOk = (lastWordChar === penultimate);
            }
            if (!lastOk) continue;

            // Score: check how many of the word's letters appear in order
            // in the swipe key sequence (word drives, swipe keys are the haystack)
            var matchCount = 0;
            var swipeIdx = 0;
            for (var w = 0; w < word.length && swipeIdx < swipeKeys.length; w++) {
                var wChar = word.charAt(w);
                for (var s = swipeIdx; s < swipeKeys.length; s++) {
                    if (swipeKeys[s].toLowerCase() === wChar) {
                        matchCount++;
                        swipeIdx = s + 1;
                        break;
                    }
                }
            }

            // Coverage: fraction of the word's letters found in the swipe path
            var coverage = matchCount / word.length;

            // Reject if less than 60% of the word is covered by the swipe
            if (coverage < 0.6) continue;

            // Bonus for word length being reasonable (not too short or too long)
            // A typical swipe crosses ~2-3x as many keys as the word length
            var lengthRatio = swipeKeys.length / word.length;
            var lengthScore = (lengthRatio >= 1.0 && lengthRatio <= 4.0) ? 1.0 : 0.5;

            var score = coverage * 10.0 * lengthScore;

            scored.push({word: candidates[i], score: score, coverage: coverage});
        }

        if (scored.length === 0) return null;

        scored.sort(function(a, b) { return b.score - a.score; });

        // Only auto-insert if confidence is high enough
        if (scored[0].coverage >= 0.75) {
            return scored[0].word;
        }

        // Otherwise show top candidates in prediction bar for user to pick
        osk.predictions = scored.slice(0, 5).map(function(s) { return s.word; });
        return null;
    }

    // ── Prediction fetching ──

    property var _predictionTimer: Timer {
        interval: 150
        repeat: false
        onTriggered: fetchPredictions()
    }

    function requestPredictions() {
        _predictionTimer.restart();
    }

    function fetchPredictions() {
        if (!osk.baseUrl) return;

        osk.predictionLoading = true;
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) {
                osk.predictionLoading = false;
                if (xhr.status === 200) {
                    var result = JSON.parse(xhr.responseText);
                    osk.predictions = result.words || [];
                } else {
                    osk.predictions = [];
                }
            }
        };
        xhr.open("POST", osk.baseUrl + "/api/predict");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({
            context: osk.currentText,
            maxWords: 5,
            maxLetters: 0
        }));
    }

    // Watch currentText changes for predictions
    onCurrentTextChanged: {
        if (active && currentText.length > 0) {
            requestPredictions();
        } else {
            predictions = [];
        }
    }

    // Train prediction model on first activation
    property bool _trained: false
    onActiveChanged: {
        if (active && !_trained) {
            _trained = true;
            trainPredictionModel();
        }
    }

    function trainPredictionModel() {
        var xhr = new XMLHttpRequest();
        xhr.open("POST", osk.baseUrl + "/api/predict/train");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send("{}");
    }

    // ── Keyboard layout definitions per language ──
    // Returns { row1: [...], row2: [...], row3: [...] } for the base QWERTY layer.
    function layoutForLanguage(lang) {
        var code = lang.toLowerCase();

        // Nordic: Norwegian Bokmål / Nynorsk, Danish
        if (code.startsWith("nb") || code.startsWith("nn") || code.startsWith("no") || code.startsWith("da")) {
            return {
                row1: ["q","w","e","r","t","y","u","i","o","p","å"],
                row2: ["a","s","d","f","g","h","j","k","l","ø","æ"],
                row3: ["z","x","c","v","b","n","m"]
            };
        }

        // Swedish, Finnish (use Swedish keyboard)
        if (code.startsWith("sv") || code.startsWith("fi")) {
            return {
                row1: ["q","w","e","r","t","y","u","i","o","p","å"],
                row2: ["a","s","d","f","g","h","j","k","l","ö","ä"],
                row3: ["z","x","c","v","b","n","m"]
            };
        }

        // Icelandic
        if (code.startsWith("is")) {
            return {
                row1: ["q","w","e","r","t","y","u","i","o","p","ð"],
                row2: ["a","s","d","f","g","h","j","k","l","æ","þ"],
                row3: ["z","x","c","v","b","n","m"]
            };
        }

        // German (QWERTZ)
        if (code.startsWith("de")) {
            return {
                row1: ["q","w","e","r","t","z","u","i","o","p","ü"],
                row2: ["a","s","d","f","g","h","j","k","l","ö","ä"],
                row3: ["y","x","c","v","b","n","m","ß"]
            };
        }

        // French (AZERTY)
        if (code.startsWith("fr")) {
            return {
                row1: ["a","z","e","r","t","y","u","i","o","p"],
                row2: ["q","s","d","f","g","h","j","k","l","m"],
                row3: ["w","x","c","v","b","n","é","è","à"]
            };
        }

        // Spanish
        if (code.startsWith("es")) {
            return {
                row1: ["q","w","e","r","t","y","u","i","o","p"],
                row2: ["a","s","d","f","g","h","j","k","l","ñ"],
                row3: ["z","x","c","v","b","n","m"]
            };
        }

        // Portuguese
        if (code.startsWith("pt")) {
            return {
                row1: ["q","w","e","r","t","y","u","i","o","p"],
                row2: ["a","s","d","f","g","h","j","k","l","ç"],
                row3: ["z","x","c","v","b","n","m"]
            };
        }

        // Italian
        if (code.startsWith("it")) {
            return {
                row1: ["q","w","e","r","t","y","u","i","o","p"],
                row2: ["a","s","d","f","g","h","j","k","l","è"],
                row3: ["z","x","c","v","b","n","m"]
            };
        }

        // Dutch
        if (code.startsWith("nl")) {
            return {
                row1: ["q","w","e","r","t","y","u","i","o","p"],
                row2: ["a","s","d","f","g","h","j","k","l"],
                row3: ["z","x","c","v","b","n","m"]
            };
        }

        // Polish
        if (code.startsWith("pl")) {
            return {
                row1: ["q","w","e","r","t","y","u","i","o","p"],
                row2: ["a","s","d","f","g","h","j","k","l","ł"],
                row3: ["z","x","c","v","b","n","m","ż","ź"]
            };
        }

        // Turkish (QWERTY-TR)
        if (code.startsWith("tr")) {
            return {
                row1: ["q","w","e","r","t","y","u","ı","o","p","ğ","ü"],
                row2: ["a","s","d","f","g","h","j","k","l","ş","i"],
                row3: ["z","x","c","v","b","n","m","ö","ç"]
            };
        }

        // Default: English QWERTY
        return {
            row1: ["q","w","e","r","t","y","u","i","o","p"],
            row2: ["a","s","d","f","g","h","j","k","l"],
            row3: ["z","x","c","v","b","n","m"]
        };
    }

    // ── Inner key button component ──
    component KeyButton: Controls.AbstractButton {
        id: keyBtn

        property bool highlighted: false
        property bool isSpace: false
        property bool isRepeat: false

        implicitHeight: Math.round(48 * osk.keyboardScale)
        implicitWidth: Math.round(40 * osk.keyboardScale)

        autoRepeat: isRepeat
        autoRepeatInterval: 80
        autoRepeatDelay: 400

        contentItem: Text {
            text: keyBtn.text
            font.pixelSize: Math.round((keyBtn.isSpace ? Theme.fontSizeSmall : Theme.fontSizeMedium) * osk.keyboardScale)
            font.weight: Font.Medium
            color: keyBtn.highlighted
                ? "#ffffff"
                : (keyBtn.down ? Theme.primary : Theme.text)
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
        }

        background: Rectangle {
            radius: Theme.smallRadius
            color: {
                if (keyBtn.highlighted)
                    return keyBtn.down ? Theme.primaryDark : Theme.primary;
                if (keyBtn.down)
                    return Theme.surfaceLight;
                if (keyBtn.hovered)
                    return Theme.surfaceHighlight;
                return Theme.surface;
            }
            border.color: Theme.surfaceHighlight
            border.width: 1

            Behavior on color { ColorAnimation { duration: 80 } }
        }
    }
}

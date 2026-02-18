import QtQuick
import QtQuick.Controls as Controls
import QtQuick.Layouts
import "components"
import "dialogs" as Dialogs
import "pages" as Pages

Window {
    id: root
    
    title: "Wingmate - Communication Aid"
    
    width: 1280
    height: 800
    visible: true
    
    color: Theme.background
    
    // API URL exposed from Rust / C++
    property string baseUrl: typeof apiUrl !== 'undefined' ? apiUrl : "http://localhost:8765"
    
    // Data Models
    ListModel { id: phrasesModel }
    ListModel { id: categoriesModel }
    property var allVoices: []
    property var availableLanguages: []
    property string currentVoice: "default"
    property string currentLanguage: "en-US"
    property bool useSystemTts: false
    property real speechRate: 1.0
    property string selectedCategoryId: ""
    property bool speechControlsVisible: true
    
    // --- Logic ---
    
    function loadPhrases() {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE && xhr.status === 200) {
                var phrases = JSON.parse(xhr.responseText);
                phrasesModel.clear();
                for (var i = 0; i < phrases.length; i++) {
                    phrasesModel.append(phrases[i]);
                }
            }
        }
        xhr.open("GET", baseUrl + "/api/phrases");
        xhr.send();
    }
    
    function loadCategories() {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE && xhr.status === 200) {
                var categories = JSON.parse(xhr.responseText);
                categoriesModel.clear();
                for (var i = 0; i < categories.length; i++) {
                    categoriesModel.append(categories[i]);
                }
            }
        }
        xhr.open("GET", baseUrl + "/api/categories");
        xhr.send();
    }
    
    function loadVoices() {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE && xhr.status === 200) {
                var loaded = JSON.parse(xhr.responseText);
                allVoices = loaded;
                
                var langSet = {};
                for (var i = 0; i < loaded.length; i++) {
                    var code = loaded[i].primaryLanguage;
                    if (code) langSet[code] = true;
                }
                availableLanguages = Object.keys(langSet).sort();
            }
        }
        xhr.open("GET", baseUrl + "/api/voices");
        xhr.send();
    }
    
    function loadSettings() {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE && xhr.status === 200) {
                var settings = JSON.parse(xhr.responseText);
                if (settings.language) currentLanguage = settings.language;
                if (settings.voice) currentVoice = settings.voice;
                if (settings.speechRate) speechRate = settings.speechRate;
                if (settings.useSystemTts !== undefined) useSystemTts = settings.useSystemTts;
            }
        }
        xhr.open("GET", baseUrl + "/api/settings");
        xhr.send();
    }
    
    function speak(text) {
        print("QML: Speaking: " + text);
        var xhr = new XMLHttpRequest();
        xhr.open("POST", baseUrl + "/api/speak");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ text: text }));
    }
    
    function stopSpeech() {
        var xhr = new XMLHttpRequest();
        xhr.open("POST", baseUrl + "/api/speak/stop");
        xhr.send();
    }
    
    function addPhrase(text) {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) loadPhrases();
        }
        xhr.open("POST", baseUrl + "/api/phrases");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ text: text }));
    }
    
    function addCategory(name) {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) loadCategories();
        }
        xhr.open("POST", baseUrl + "/api/categories");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ name: name }));
    }
    
    function selectCategory(catId) {
        selectedCategoryId = catId;
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) loadPhrases();
        }
        xhr.open("POST", baseUrl + "/api/categories/select");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ categoryId: catId }));
    }
    
    function updateVoice(voice) {
        var xhr = new XMLHttpRequest();
        xhr.open("PUT", baseUrl + "/api/settings/voice");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ voice: voice }));
        currentVoice = voice;
    }
    
    function updateSpeechRate(rate) {
        var xhr = new XMLHttpRequest();
        xhr.open("PUT", baseUrl + "/api/settings/rate");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ rate: rate }));
        speechRate = rate;
    }
    
    function appendTextToInput(text) {
        speechInput.text += text;
        speechInput.cursorPosition = speechInput.text.length;
    }
    
    Component.onCompleted: {
        print("QML: App Started. BaseURL: " + baseUrl);
        loadPhrases();
        loadCategories();
        loadVoices();
        loadSettings();
    }
    
    // --- Layout ---
    
    RowLayout {
        anchors.fill: parent
        spacing: 0
        
        // Left Sidebar (Navigation)
        Sidebar {
            id: sidebar
            Layout.fillHeight: true
            onNavigationRequested: (page) => {
                if (page === "qrc:/pages/SettingsPage.qml") {
                    mainStack.replace(settingsComponent);
                } else if (page === "qrc:/pages/PronunciationDictionaryPage.qml") {
                    mainStack.replace(pronunciationComponent);
                } else {
                    mainStack.replace(homeComponent);
                }
            }
        }
        
        // Main Content Area
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 0
            
            Controls.StackView {
                id: mainStack
                Layout.fillWidth: true
                Layout.fillHeight: true
                
                initialItem: homeComponent
                
                replaceEnter: Transition {
                    PropertyAnimation { property: "opacity"; from: 0; to: 1; duration: 200 }
                }
                replaceExit: Transition {
                    PropertyAnimation { property: "opacity"; from: 1; to: 0; duration: 150 }
                }
            }
            
            // Bottom Bar
            BottomBar {
                Layout.fillWidth: true
                
                onPlayClicked: {
                    if (speechInput.text.length > 0) speak(speechInput.text);
                }
                onSettingsClicked: speechSettingsDialog.open()
                onPauseClicked: {} // TODO: implement pause
                onStopClicked: stopSpeech()
            }
        }
        
        // Right Speech Controls Sidebar
        SpeechControlsSidebar {
            Layout.fillHeight: true
            visible: root.speechControlsVisible
            baseUrl: root.baseUrl
            voiceName: root.currentVoice
            engineName: root.useSystemTts ? "System (Local)" : "Azure (Premium)"
            speedValue: root.speechRate
            
            onChangeEngineRequested: speechSettingsDialog.open()
            onSelectVoiceRequested: voiceSelectionDialog.open()
            onApplySettings: updateSpeechRate(speedValue)
            onInsertPause: (duration) => appendTextToInput(" [pause " + duration + "s] ")
            onSetEmphasis: (level) => appendTextToInput(" [" + level + "] ")
        }
    }
    
    // --- Page Components ---
    
    Component {
        id: homeComponent
        
        Item {
            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 20
                spacing: 16
                
                // Text Input Area
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 120
                    radius: Theme.radius
                    color: Theme.surface
                    border.color: speechInput.activeFocus ? Theme.primary : Theme.surfaceHighlight
                    border.width: speechInput.activeFocus ? 2 : 1
                    
                    Behavior on border.color { ColorAnimation { duration: 150 } }
                    
                    TextInput {
                        id: speechInput
                        anchors.fill: parent
                        anchors.margins: 16
                        font.pixelSize: 20
                        color: Theme.text
                        selectByMouse: true
                        wrapMode: TextInput.Wrap
                        verticalAlignment: TextInput.AlignTop
                        
                        Text {
                            text: "Enter text to speak..."
                            color: Theme.subText
                            font: parent.font
                            visible: !parent.text && !parent.activeFocus
                            anchors.fill: parent
                        }
                        
                        Keys.onReturnPressed: {
                            if (text.length > 0) speak(text);
                        }
                    }
                }
                
                // Quick syllable chips row
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 8
                    
                    Repeater {
                        model: ["og", "at", "er", "ur", "i", "på", "en", "et", "vi", "ja", "nei"]
                        
                        Chip {
                            text: modelData
                            onClicked: appendTextToInput(modelData + " ")
                        }
                    }
                    
                    Item { Layout.fillWidth: true }
                }
                
                // Category tabs
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 8
                    
                    Chip {
                        text: "All"
                        selected: selectedCategoryId === ""
                        onClicked: selectCategory("")
                    }
                    
                    Repeater {
                        model: categoriesModel
                        
                        Chip {
                            text: model.name || "Unknown"
                            selected: selectedCategoryId === model.id
                            onClicked: selectCategory(model.id)
                        }
                    }
                    
                    // Add category chip
                    Chip {
                        text: "+ Add"
                        onClicked: addCategoryDialog.open()
                    }
                    
                    Item { Layout.fillWidth: true }
                }
                
                // Phrase Grid
                Controls.ScrollView {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    clip: true
                    
                    GridView {
                        id: phraseGrid
                        width: parent.width
                        cellWidth: Math.max(150, width / Math.floor(width / 180))
                        cellHeight: 100
                        
                        model: phrasesModel
                        
                        delegate: Item {
                            width: phraseGrid.cellWidth
                            height: phraseGrid.cellHeight
                            
                            PhraseButton {
                                anchors.fill: parent
                                anchors.margins: 4
                                phraseText: model.text || ""
                                phraseImage: model.imageUrl || ""
                                
                                onClicked: speak(phraseText)
                            }
                        }
                        
                        // Footer / Add button
                        footer: Item {
                            width: phraseGrid.width
                            height: phraseGrid.cellHeight
                            
                            Controls.Button {
                                anchors.fill: parent
                                anchors.margins: 4
                                
                                contentItem: ColumnLayout {
                                    spacing: 4
                                    Text {
                                        text: "+"
                                        font.pixelSize: 28
                                        font.bold: true
                                        color: Theme.subText
                                        horizontalAlignment: Text.AlignHCenter
                                        Layout.fillWidth: true
                                    }
                                    Text {
                                        text: "Add Phrase"
                                        font.pixelSize: Theme.fontSizeSmall
                                        color: Theme.subText
                                        horizontalAlignment: Text.AlignHCenter
                                        Layout.fillWidth: true
                                    }
                                }
                                
                                background: Rectangle {
                                    radius: Theme.radius
                                    color: parent.hovered ? Theme.surfaceHighlight : Theme.surface
                                    border.color: Theme.surfaceHighlight
                                    border.width: 1
                                }
                                
                                onClicked: {
                                    if (speechInput.text.length > 0) {
                                        addPhrase(speechInput.text);
                                    } else {
                                        addPhraseDialog.open();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    Component {
        id: settingsComponent
        Pages.SettingsPage {
            baseUrl: root.baseUrl
        }
    }
    
    Component {
        id: pronunciationComponent
        Pages.PronunciationDictionaryPage {
            baseUrl: root.baseUrl
            onBackRequested: mainStack.replace(homeComponent)
            onAddEntryRequested: mainStack.replace(addPronunciationEntryComponent)
        }
    }
    
    Component {
        id: addPronunciationEntryComponent
        Pages.AddPronunciationEntryPage {
            baseUrl: root.baseUrl
            onBackRequested: mainStack.replace(pronunciationComponent)
            onEntryAdded: {} // reload happens on back via Component.onCompleted
        }
    }
    
    // --- Dialogs ---
    
    // Add Category Dialog
    Controls.Dialog {
        id: addCategoryDialog
        title: "Add Category"
        anchors.centerIn: parent
        width: 360
        modal: true
        
        background: Rectangle {
            color: Theme.surface
            radius: Theme.radius
            border.color: Theme.surfaceHighlight
        }
        
        contentItem: ColumnLayout {
            spacing: 12
            
            Controls.TextField {
                id: newCategoryField
                Layout.fillWidth: true
                placeholderText: "Category name"
                color: Theme.text
                background: Rectangle {
                    color: Theme.background
                    radius: Theme.smallRadius
                    border.color: Theme.surfaceHighlight
                }
            }
        }
        
        footer: RowLayout {
            spacing: 8
            Item { Layout.fillWidth: true }
            Controls.Button {
                text: "Cancel"
                contentItem: Text { text: parent.text; color: Theme.subText; font.pixelSize: Theme.fontSizeNormal }
                background: Item {}
                onClicked: addCategoryDialog.close()
            }
            ModernButton {
                text: "Add"
                primary: true
                enabled: newCategoryField.text.length > 0
                onClicked: {
                    addCategory(newCategoryField.text);
                    newCategoryField.text = "";
                    addCategoryDialog.close();
                }
            }
        }
    }
    
    // Add Phrase Dialog
    Controls.Dialog {
        id: addPhraseDialog
        title: "Add Quick Phrase"
        anchors.centerIn: parent
        width: 360
        modal: true
        
        background: Rectangle {
            color: Theme.surface
            radius: Theme.radius
            border.color: Theme.surfaceHighlight
        }
        
        contentItem: ColumnLayout {
            spacing: 12
            
            Controls.TextField {
                id: newPhraseField
                Layout.fillWidth: true
                placeholderText: "Phrase text"
                color: Theme.text
                background: Rectangle {
                    color: Theme.background
                    radius: Theme.smallRadius
                    border.color: Theme.surfaceHighlight
                }
            }
        }
        
        footer: RowLayout {
            spacing: 8
            Item { Layout.fillWidth: true }
            Controls.Button {
                text: "Cancel"
                contentItem: Text { text: parent.text; color: Theme.subText; font.pixelSize: Theme.fontSizeNormal }
                background: Item {}
                onClicked: addPhraseDialog.close()
            }
            ModernButton {
                text: "Add"
                primary: true
                enabled: newPhraseField.text.length > 0
                onClicked: {
                    addPhrase(newPhraseField.text);
                    newPhraseField.text = "";
                    addPhraseDialog.close();
                }
            }
        }
    }
    
    // Speech Settings Dialog
    Dialogs.SpeechSettingsDialog {
        id: speechSettingsDialog
        baseUrl: root.baseUrl
        useAzure: !root.useSystemTts
        
        Component.onCompleted: {
            // Load azure config
            var xhr = new XMLHttpRequest();
            xhr.onreadystatechange = function() {
                if (xhr.readyState === XMLHttpRequest.DONE && xhr.status === 200) {
                    var config = JSON.parse(xhr.responseText);
                    if (config.endpoint) speechSettingsDialog.azureEndpoint = config.endpoint;
                    if (config.key) speechSettingsDialog.azureKey = config.key;
                }
            }
            xhr.open("GET", root.baseUrl + "/api/azure-config");
            xhr.send();
        }
        
        onSaved: {
            // Save TTS mode
            var xhr = new XMLHttpRequest();
            xhr.open("PUT", root.baseUrl + "/api/settings/systemtts");
            xhr.setRequestHeader("Content-Type", "application/json");
            xhr.send(JSON.stringify({ useSystemTts: !useAzure }));
            root.useSystemTts = !useAzure;
            
            // Save Azure config
            if (useAzure) {
                var xhr2 = new XMLHttpRequest();
                xhr2.onreadystatechange = function() {
                    if (xhr2.readyState === XMLHttpRequest.DONE) loadVoices();
                }
                xhr2.open("POST", root.baseUrl + "/api/azure-config");
                xhr2.setRequestHeader("Content-Type", "application/json");
                xhr2.send(JSON.stringify({ endpoint: azureEndpoint, key: azureKey }));
            }
        }
    }
    
    // Voice Selection Dialog
    Dialogs.VoiceSelectionDialog {
        id: voiceSelectionDialog
        baseUrl: root.baseUrl
        voices: root.allVoices
        availableLanguages: root.availableLanguages
        
        onVoiceSelected: (voiceName) => {
            updateVoice(voiceName);
            close();
        }
        
        onVoiceSettingsRequested: (voice) => {
            voiceSettingsDialog.voiceName = voice.displayName || voice.name;
            voiceSettingsDialog.language = voice.primaryLanguage || "";
            voiceSettingsDialog.open();
        }
    }
    
    // Voice Settings Dialog
    Dialogs.VoiceSettingsDialog {
        id: voiceSettingsDialog
        baseUrl: root.baseUrl
        
        onTestVoiceRequested: speak("Hello, this is a voice test.")
        onSaved: {
            updateSpeechRate(rateValue);
        }
    }
}

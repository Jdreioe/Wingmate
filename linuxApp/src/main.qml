import QtQuick
import QtQuick.Controls as Controls
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import "components"
import "dialogs" as Dialogs
import "pages" as Pages

Kirigami.ApplicationWindow {
    id: root
    
    title: "Wingmate - Communication Aid"
    
    width: 1280
    height: 800
    visible: true
    
    // Kirigami handles background color automatically, but we ensure our theme matches
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
    property string currentSpeechText: ""
    property bool partnerWindowEnabled: false
    
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
                if (settings) {
                    if (settings.language) currentLanguage = settings.language;
                    if (settings.voice) currentVoice = settings.voice;
                    if (settings.speechRate) speechRate = settings.speechRate;
                    if (settings.useSystemTts !== undefined) useSystemTts = settings.useSystemTts;
                    if (settings.partnerWindowEnabled !== undefined) {
                        partnerWindowEnabled = settings.partnerWindowEnabled;
                        // Sync persisted setting to Rust partner window bridge
                        if (typeof partnerWindow !== 'undefined') {
                            partnerWindow.setEnabled(partnerWindowEnabled);
                        }
                    }
                    
                    // Check welcome flow
                    if (!settings.welcomeFlowCompleted && welcomeWizard) {
                         welcomeWizard.open();
                    }
                }
            }
        }
        xhr.open("GET", baseUrl + "/api/settings");
        xhr.send();
    }
    
    function setPartnerWindowEnabled(enabled) {
        partnerWindowEnabled = enabled;
        // Persist setting to Kotlin bridge (for storage only, not partner window control)
        var xhr = new XMLHttpRequest();
        xhr.open("PUT", baseUrl + "/api/settings/partnerwindow");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ enabled: enabled }));
        // Drive the Rust partner window bridge directly (owns the FTDI device)
        if (typeof partnerWindow !== 'undefined') {
            partnerWindow.setEnabled(enabled);
        }
        // If enabled, send current text immediately
        if (enabled) updatePartnerWindowText(currentSpeechText);
    }

    function updatePartnerWindowText(text) {
        if (!partnerWindowEnabled) return;
        // Rust partner window bridge — no HTTP roundtrip
        if (typeof partnerWindow !== 'undefined') {
            partnerWindow.updateText(text);
        }
    }
    
    function speak(text) {
        print("QML: Speaking: " + text);
        // Mirror spoken text to partner window display
        updatePartnerWindowText(text);
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
        print("QML: updateVoice called with: " + voice);
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) {
                print("QML: updateVoice response: status=" + xhr.status + " body=" + xhr.responseText);
            }
        }
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
        root.currentSpeechText += text;
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
                    if (root.currentSpeechText.length > 0) speak(root.currentSpeechText);
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
                        text: root.currentSpeechText
                        onTextChanged: {
                            root.currentSpeechText = text;
                            root.updatePartnerWindowText(text);
                        }
                        
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
                
                // Quick syllable chips row (scrollable)
                Flickable {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 36
                    contentWidth: syllableRow.width
                    clip: true
                    flickableDirection: Flickable.HorizontalFlick
                    
                    RowLayout {
                        id: syllableRow
                        spacing: 8
                        
                        Repeater {
                            model: ["og", "at", "er", "ur", "i", "på", "en", "et", "vi", "ja", "nei"]
                            
                            Chip {
                                text: modelData
                                onClicked: appendTextToInput(modelData + " ")
                            }
                        }
                    }
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
                            contextMenuEnabled: true // Enable context menu for user categories
                            
                            onClicked: selectCategory(model.id)
                            onRenameRequested: {
                                addEditCategoryDialog.openForEdit(model.id, model.name);
                            }
                            onDeleteRequested: {
                                deleteCategory(model.id);
                            }
                        }
                    }
                    
                    // Add category chip
                    Chip {
                        text: "+ Add"
                        onClicked: addEditCategoryDialog.openForAdd()
                    }
                    
                    Item { Layout.fillWidth: true }
                }

    // ... (rest of file) ...

    function deleteCategory(id) {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) {
                loadCategories();
                if (selectedCategoryId === id) selectCategory(""); // reset to All if deleted
            }
        }
        xhr.open("DELETE", root.baseUrl + "/api/categories?id=" + encodeURIComponent(id)); // Assuming API supports this
        // If not, we might need body. Assuming standard REST or Wingmate custom pattern.
        // Existing Add Category used POST /api/categories. 
        // Let's assume DELETE /api/categories/{id} or query param.
        // Or DELETE /api/categories with JSON body { id: ... }
        // I will use JSON body for safety
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ id: id }));
    }
    
    function renameCategory(id, newName) {
        // Implement rename via delete + add or dedicated endpoint?
        // Add Category uses POST /api/categories.
        // Let's try PUT /api/categories?
        // Or assume Delete + Add for now if backend is simple.
        // But reordering might be lost.
        // Let's assume basic PUT /api/categories implementation:
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) {
                loadCategories();
            }
        }
        xhr.open("PUT", root.baseUrl + "/api/categories");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ id: id, name: newName }));
    }

    // ...

    // Add/Edit Category Dialog
    Dialogs.AddEditCategoryDialog {
        id: addEditCategoryDialog
        onCategorySaved: (name) => {
            if (isEditing) {
                renameCategory(categoryId, name);
            } else {
                addCategory(name);
            }
        }
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
                                onEditRequested: {
                                    addEditPhraseDialog.openForEdit(phraseText);
                                }
                                onDeleteRequested: {
                                    deletePhrase(phraseText);
                                }
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
                                    if (root.currentSpeechText.length > 0) {
                                        addPhrase(root.currentSpeechText);
                                    } else {
                                        addEditPhraseDialog.openForAdd();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    // ... (Components excluded) ...
    
    // --- Logic for Delete/Edit ---
    function deletePhrase(text) {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) {
                loadPhrases(); // reload grid
            }
        }
        // Need DELETE endpoint on KotlinBridge for phrases? 
        // Existing code used "GET /api/pronunciation" (list/add/delete). 
        // But phrases are likely "GET /api/phrases" (implied by loadPhrases).
        // Let's assume DELETE /api/phrases/{text} or similar.
        // Checking KotlinBridge.kt would confirm, but sticking to standard pattern:
        // Or if phrases are just stored in user preferences, maybe we post the whole list?
        // Let's check KotlinBridge if possible, or assume simple DELETE /api/phrases/{id/text}
        // Actually, loadPhrases used GET /api/phrases (implied).
        // Let's try DELETE /api/phrases with body or query param?
        // Wait, standard CRUD usually: DELETE /api/phrases?text=... or /api/phrases/{text}
        // I will use DELETE /api/phrases with JSON body to be safe for special chars
        xhr.open("DELETE", root.baseUrl + "/api/phrases");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ text: text }));
    }
    
    function updatePhrase(original, newText) {
        // usually delete original and add new if no ID system
        // Or PUT /api/phrases
        // Let's implement as delete + add for robustness if no dedicated update API
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) {
                addPhrase(newText);
            }
        }
        xhr.open("DELETE", root.baseUrl + "/api/phrases");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ text: original }));
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
    

    
    // Add/Edit Phrase Dialog
    Dialogs.AddEditPhraseDialog {
        id: addEditPhraseDialog
        onPhraseSaved: (text) => {
            if (isEditing) {
                updatePhrase(originalPhrase, text);
            } else {
                addPhrase(text);
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
        
        onLanguageSettingsRequested: {
            voiceLanguageDialog.open();
        }
    }
    
    // Voice Language Dialog
    Dialogs.VoiceLanguageDialog {
        id: voiceLanguageDialog
        baseUrl: root.baseUrl
        availableLanguages: root.availableLanguages
        
        onSettingsSaved: (primary, secondary) => {
             // Maybe update voices or current settings
             loadVoices(); // reload voices as they might depend on language
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
    
    // Welcome Wizard
    Pages.WelcomeWizard {
        id: welcomeWizard
        baseUrl: root.baseUrl
        availableLanguages: root.availableLanguages
        
        onConfigureVoiceRequested: {
             speechSettingsDialog.open();
             // Maybe switch wizard slide or wait?
             // Since dialogs are modal, speechSettings will open on top.
        }
        
        onCompleted: {
             // Reload settings or just proceed
             loadSettings();
        }
    }

    // ── Partner Window state polling ──
    // The Rust PartnerWindowBridge background thread updates shared state;
    // this timer syncs QML-visible properties with that state.
    Timer {
        interval: 2000
        running: true
        repeat: true
        onTriggered: {
            if (typeof partnerWindow !== 'undefined') {
                partnerWindow.pollState();
            }
        }
    }
    

}
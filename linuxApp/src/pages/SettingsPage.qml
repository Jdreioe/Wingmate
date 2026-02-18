import QtQuick
import QtQuick.Controls as Controls
import QtQuick.Layouts
import "../components" // Relative path to components if this file is in pages/

Item {
    id: settingsPage
    
    // Properties
    property string baseUrl: typeof apiUrl !== 'undefined' ? apiUrl : "http://localhost:8765"
    
    // Settings data
    property string currentLanguage: "en-US"
    property string currentVoice: "default"
    property real speechRate: 1.0
    property bool useSystemTts: false
    property bool partnerWindowEnabled: false
    property real fontScale: 1.0
    
    // Azure data
    property string azureEndpoint: ""
    property string azureKey: ""
    property var allVoices: []
    property var availableVoices: [{"displayName": "Default", "name": "default"}]
    property var availableLanguages: [{"text": "English (US)", "value": "en-US"}]
    
    Component.onCompleted: {
        loadSettings();
        loadAzureConfig();
        loadVoices();
    }
    
    // --- Logic ---
    function loadVoices() {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE && xhr.status === 200) {
                var loaded = JSON.parse(xhr.responseText);
                if (loaded && loaded.length > 0) {
                    allVoices = loaded;
                    
                    // Extract unique languages
                    var langSet = {};
                    for (var i = 0; i < loaded.length; i++) {
                        var code = loaded[i].primaryLanguage;
                        if (code) langSet[code] = true;
                    }
                    
                    var langArray = Object.keys(langSet).sort();
                    var langModel = [];
                    for (var j = 0; j < langArray.length; j++) {
                        langModel.push({ "text": langArray[j], "value": langArray[j] });
                    }
                    
                    if (langModel.length > 0) {
                        availableLanguages = langModel;
                    }
                    
                    filterVoices();
                }
            }
        }
        xhr.open("GET", baseUrl + "/api/voices");
        xhr.send();
    }
    


    // Trigger filter when language changes
    onCurrentLanguageChanged: filterVoices()
    
    function saveAzureConfig() {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE && xhr.status === 200) loadVoices();
        }
        xhr.open("POST", baseUrl + "/api/azure-config");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ endpoint: azureEndpoint, key: azureKey }));
    }
    
    property bool settingsLoaded: false
    
    // ...
    
    function filterVoices() {
        if (!allVoices || allVoices.length === 0) return;
        
        console.log("SettingsPage: Filtering voices for language: " + currentLanguage);
        
        var filtered = [];
        for (var i = 0; i < allVoices.length; i++) {
            if (allVoices[i].primaryLanguage === currentLanguage) {
                filtered.push(allVoices[i]);
            }
        }
        
        console.log("SettingsPage: Found " + filtered.length + " voices for " + currentLanguage);
        
        // Sort by display name
        filtered.sort(function(a, b) {
            return a.displayName.localeCompare(b.displayName);
        });
        
        availableVoices = filtered;
        
        // Check if currentVoice is in filtered
        var found = false;
        for (var k = 0; k < filtered.length; k++) {
            if (filtered[k].name === currentVoice) {
                found = true;
                break;
            }
        }
        
        if (!found && filtered.length > 0) {
            // Only update the LOCAL property — do NOT call updateVoice() here
            // since that sends a PUT to the backend and causes race conditions.
            // The user must explicitly select a voice to save it.
            console.log("SettingsPage: Voice " + currentVoice + " not in filtered list. Setting local to " + filtered[0].name);
            currentVoice = filtered[0].name;
        }
    }
    
    // ...
    
    function loadSettings() {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE && xhr.status === 200) {
                var settings = JSON.parse(xhr.responseText);
                // Load language first so filtering happens correctly later
                if (settings.language) {
                    currentLanguage = settings.language === "en" ? "en-US" : settings.language;
                }
                if (settings.voice) currentVoice = settings.voice;
                if (settings.speechRate) speechRate = settings.speechRate;
                if (settings.useSystemTts !== undefined) useSystemTts = settings.useSystemTts;
                if (settings.partnerWindowEnabled !== undefined) partnerWindowEnabled = settings.partnerWindowEnabled;
                if (settings.fontSizeScale) fontScale = settings.fontSizeScale;
                
                settingsLoaded = true;
                console.log("SettingsPage: Settings loaded. Voice=" + currentVoice);
                
                // If we have voices loaded but filter wasn't run for this language yet (race condition), run it now
                if (allVoices.length > 0) filterVoices();
            }
        }
        xhr.open("GET", baseUrl + "/api/settings");
        xhr.send();
    }
    
    function loadAzureConfig() {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE && xhr.status === 200) {
                var config = JSON.parse(xhr.responseText);
                if (config.endpoint) azureEndpoint = config.endpoint;
                if (config.key) azureKey = config.key;
            }
        }
        xhr.open("GET", baseUrl + "/api/azure-config");
        xhr.send();
    }
    
    function updateLanguage(lang) {
        var xhr = new XMLHttpRequest();
        xhr.open("PUT", baseUrl + "/api/settings/language");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ language: lang }));
        currentLanguage = lang;
    }
    
    function updateVoice(voice) {
        console.log("Saving voice: " + voice);
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
    
    function updateSystemTts(enabled) {
        var xhr = new XMLHttpRequest();
        xhr.open("PUT", baseUrl + "/api/settings/systemtts");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ useSystemTts: enabled }));
        useSystemTts = enabled;
    }
    
    function updatePartnerWindow(enabled) {
        var xhr = new XMLHttpRequest();
        xhr.open("PUT", baseUrl + "/api/settings/partnerwindow");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ enabled: enabled }));
        partnerWindowEnabled = enabled;
        // Also update main app state if possible, or let it sync next time
        if (root && root.setPartnerWindowEnabled) {
            root.setPartnerWindowEnabled(enabled);
        }
    }
    
    // --- Layout ---
    
    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 24
        spacing: 24
        
        // Header
        Text {
            text: "Settings"
            font.pixelSize: Theme.fontSizeHeader
            font.bold: true
            color: Theme.text
        }
        
        Controls.ScrollView {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            
            ColumnLayout {
                width: parent.width
                spacing: 16
                
                // Voice Settings
                ModernCard {
                    Layout.fillWidth: true
                    title: "Voice Settings"
                    
                    content: ColumnLayout {
                        spacing: 12
                        
                        // Language
                        RowLayout {
                            Layout.fillWidth: true
                            Text {
                                text: "Language"
                                color: Theme.text
                                Layout.preferredWidth: 150
                            }
                            Controls.ComboBox {
                                Layout.fillWidth: true
                                model: availableLanguages
                                textRole: "text"
                                valueRole: "value"
                                currentIndex: {
                                    for (var i = 0; i < model.length; i++) {
                                        if (model[i].value === currentLanguage) return i;
                                    }
                                    return 0;
                                }
                                onActivated: updateLanguage(currentValue)
                            }
                        }
                        
                        // Voice
                        RowLayout {
                            Layout.fillWidth: true
                            Text {
                                text: "Voice"
                                color: Theme.text
                                Layout.preferredWidth: 150
                            }
                            Controls.ComboBox {
                                Layout.fillWidth: true
                                model: availableVoices
                                textRole: "displayName"
                                valueRole: "name"
                                currentIndex: {
                                    for (var i = 0; i < model.length; i++) {
                                        if (model[i].name === currentVoice) return i;
                                    }
                                    return 0;
                                }
                                onActivated: updateVoice(currentValue)
                            }
                        }
                        
                        // Speech Rate
                        RowLayout {
                            Layout.fillWidth: true
                            Text {
                                text: "Speech Rate"
                                color: Theme.text
                                Layout.preferredWidth: 150
                            }
                            Controls.Slider {
                                Layout.fillWidth: true
                                from: 0.5; to: 2.0
                                value: speechRate
                                stepSize: 0.1
                                onMoved: updateSpeechRate(value)
                            }
                            Text {
                                text: speechRate.toFixed(1) + "x"
                                color: Theme.text
                                Layout.preferredWidth: 40
                            }
                        }
                        
                        // TTS Mode
                        Controls.CheckBox {
                            text: "Use Local TTS (Piper/eSpeak)"
                            checked: useSystemTts
                            onClicked: updateSystemTts(checked)
                            
                            contentItem: Text {
                                text: parent.text
                                color: Theme.text
                                leftPadding: parent.indicator.width + parent.spacing
                                verticalAlignment: Text.AlignVCenter
                            }
                        }
                        
                        // Partner Window Mode
                        Controls.CheckBox {
                            text: "Enable Partner Window (Requires Device)"
                            checked: partnerWindowEnabled
                            onClicked: updatePartnerWindow(checked)
                            
                            contentItem: Text {
                                text: parent.text
                                color: Theme.text
                                leftPadding: parent.indicator.width + parent.spacing
                                verticalAlignment: Text.AlignVCenter
                            }
                        }
                    }
                }
                
                // Azure Config
                ModernCard {
                    Layout.fillWidth: true
                    title: "Azure Speech"
                    visible: !useSystemTts
                    
                    content: ColumnLayout {
                        spacing: 12
                        
                        Text {
                            text: "Requires an Azure Cognitive Services API Key"
                            color: Theme.subText
                            font.pixelSize: Theme.fontSizeSmall
                        }
                        
                        RowLayout {
                            Layout.fillWidth: true
                            Text {
                                text: "Region"
                                color: Theme.text
                                Layout.preferredWidth: 150
                            }
                            Controls.TextField {
                                Layout.fillWidth: true
                                text: azureEndpoint
                                placeholderText: "e.g. eastus"
                                onTextEdited: azureEndpoint = text
                                color: Theme.text
                                background: Rectangle {
                                    color: Theme.surfaceHighlight
                                    radius: 4
                                }
                            }
                        }
                        
                        RowLayout {
                            Layout.fillWidth: true
                            Text {
                                text: "API Key"
                                color: Theme.text
                                Layout.preferredWidth: 150
                            }
                            Controls.TextField {
                                Layout.fillWidth: true
                                text: azureKey
                                echoMode: TextInput.Password
                                placeholderText: "Azure Key"
                                onTextEdited: azureKey = text
                                color: Theme.text
                                background: Rectangle {
                                    color: Theme.surfaceHighlight
                                    radius: 4
                                }
                            }
                        }
                        
                        ModernButton {
                            text: "Save Configuration"
                            Layout.alignment: Qt.AlignRight
                            onClicked: saveAzureConfig()
                        }
                    }
                }
                
                // About
                ModernCard {
                    Layout.fillWidth: true
                    title: "About Wingmate"
                    
                    content: ColumnLayout {
                        spacing: 8
                        
                        Text {
                            text: "Wingmate is a Free and Open Source Software (FOSS) project."
                            color: Theme.text
                            wrapMode: Text.WordWrap
                            Layout.fillWidth: true
                        }
                        
                        Text {
                            text: "Version 1.0.0 (Linux/KDE)"
                            color: Theme.subText
                        }
                        
                        RowLayout {
                            Layout.fillWidth: true
                            ModernButton {
                                text: "Website"
                                onClicked: Qt.openUrlExternally("https://github.com/jdreioe/wingmate")
                            }
                            ModernButton {
                                text: "Report Issue"
                                onClicked: Qt.openUrlExternally("https://github.com/jdreioe/wingmate/issues")
                            }
                        }
                    }
                }
            }
        }
    }
}

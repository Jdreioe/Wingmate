import QtQuick
import QtQuick.Controls as Controls
import QtQuick.Layouts
import "components"

Window {
    id: root
    
    title: "Wingmate - Communication Aid"
    
    width: 1280
    height: 800
    visible: true
    
    color: Theme.background
    
    // API URL exposed from C++
    property string baseUrl: typeof apiUrl !== 'undefined' ? apiUrl : "http://localhost:8765"
    
    // Data Models
    ListModel { id: phrasesModel }
    ListModel { id: categoriesModel }
    
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
    
    function speak(text) {
        print("QML: Sending speak request for: " + text);
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) {
                print("QML: Speak request completed. Status: " + xhr.status);
                if (xhr.status !== 200) {
                    print("QML: Speak request failed: " + xhr.responseText);
                }
            }
        }
        xhr.open("POST", baseUrl + "/api/speak");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ text: text }));
    }
    
    function addPhrase(text) {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) {
                loadPhrases();
            }
        }
        xhr.open("POST", baseUrl + "/api/phrases");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ text: text }));
    }
    
    function selectCategory(catId) {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function() {
            if (xhr.readyState === XMLHttpRequest.DONE) {
                loadPhrases();
            }
        }
        xhr.open("POST", baseUrl + "/api/categories/select");
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.send(JSON.stringify({ categoryId: catId }));
    }
    
    Component.onCompleted: {
        print("QML: App Started. BaseURL: " + baseUrl);
        loadPhrases();
        loadCategories();
    }
    
    // --- Layout ---
    
    RowLayout {
        anchors.fill: parent
        spacing: 0
        
        // Sidebar
        Sidebar {
            Layout.fillHeight: true
            onNavigationRequested: (page) => {
                // If page is same as current, do nothing or reset
                // Since Settings is an external file, we replace logic
                if (page !== "qrc:/main.qml") {
                    mainStack.replace(page)
                } else {
                    mainStack.replace(homeComponent)
                }
            }
        }
        
        // Main Content Area
        Controls.StackView {
            id: mainStack
            Layout.fillWidth: true
            Layout.fillHeight: true
            
            initialItem: homeComponent
            
            pushEnter: Transition {
                PropertyAnimation { property: "opacity"; from: 0; to: 1; duration: 200 }
                NumberAnimation { property: "y"; from: 20; to: 0; duration: 200; easing.type: Easing.OutQuad }
            }
            pushExit: Transition {
                PropertyAnimation { property: "opacity"; from: 1; to: 0; duration: 150 }
            }
            replaceEnter: Transition {
                PropertyAnimation { property: "opacity"; from: 0; to: 1; duration: 200 }
            }
            replaceExit: Transition {
                PropertyAnimation { property: "opacity"; from: 1; to: 0; duration: 150 }
            }
        }
    }
    
    // --- Pages ---
    
    Component {
        id: homeComponent
        
        Item {
            
            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 24
                spacing: 24
                
                // Top Bar / Header
                RowLayout {
                    Layout.fillWidth: true
                    
                    Text {
                        text: "Speak"
                        font.pixelSize: Theme.fontSizeHeader
                        font.bold: true
                        color: Theme.text
                        Layout.fillWidth: true
                    }
                    
                    ModernButton {
                        text: "Refresh"
                        iconName: "view-refresh" // standard icon
                        onClicked: {
                            loadPhrases();
                            loadCategories();
                        }
                    }
                }
                
                // Input Area
                ModernCard {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 180
                    elevation: 4
                    glass: true
                    
                    content: ColumnLayout {
                        spacing: 16
                        
                        // Text Input
                        Rectangle {
                            Layout.fillWidth: true
                            Layout.fillHeight: true
                            color: Qt.darker(Theme.background, 1.2)
                            radius: Theme.smallRadius
                            border.color: speechInput.activeFocus ? Theme.primary : "transparent"
                            border.width: 1
                            
                            TextInput {
                                id: speechInput
                                anchors.fill: parent
                                anchors.margins: 16
                                font.pixelSize: 24
                                color: Theme.text
                                selectByMouse: true
                                wrapMode: TextInput.Wrap
                                text: ""
                                verticalAlignment: TextInput.AlignTop
                                
                                property string placeholderText: "Type something to say..."
                                Text {
                                    text: parent.placeholderText
                                    color: Theme.subText
                                    font: parent.font
                                    visible: !parent.text && !parent.activeFocus
                                    anchors.fill: parent
                                }
                                
                                Keys.onReturnPressed: {
                                    if (text.length > 0) speak(text)
                                }
                            }
                        }
                        
                        // Actions
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            
                            ModernButton {
                                text: "Speak"
                                primary: true
                                Layout.preferredWidth: 120
                                onClicked: {
                                    if (speechInput.text.length > 0) speak(speechInput.text)
                                }
                            }
                            
                            ModernButton {
                                text: "Clear"
                                onClicked: speechInput.text = ""
                            }
                            
                            Item { Layout.fillWidth: true }
                            
                            ModernButton {
                                text: "Save Phrase"
                                onClicked: {
                                    if (speechInput.text.length > 0) addPhrase(speechInput.text)
                                }
                            }
                        }
                    }
                }
                
                // Categories
                Item {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 50
                    visible: categoriesModel.count > 0
                    
                    ListView {
                        anchors.fill: parent
                        orientation: ListView.Horizontal
                        spacing: 12
                        clip: true
                        
                        model: categoriesModel
                        delegate: ModernButton {
                            text: model.name || "Unknown"
                            onClicked: selectCategory(model.id)
                        }
                    }
                }
                
                // Saved Phrases
                Text {
                    text: "Quick Phrases"
                    font.pixelSize: Theme.fontSizeHeader/1.5
                    color: Theme.text
                    font.bold: true
                }
                
                Controls.ScrollView {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    clip: true
                    
                    GridView {
                        id: phraseGrid
                        width: parent.width
                        cellWidth: width / Math.floor(width / 200)
                        cellHeight: 120
                        model: phrasesModel.count > 0 ? phrasesModel : ["Hello", "Thank you", "Yes", "No", "Help", "Water"]
                        
                        delegate: Item {
                            width: phraseGrid.cellWidth
                            height: phraseGrid.cellHeight
                            
                            PhraseButton {
                                anchors.fill: parent
                                anchors.margins: 6
                                
                                phraseText: typeof modelData === "string" ? modelData : (model.text || "")
                                phraseImage: typeof modelData === "string" ? "" : (model.imageUrl || "")
                                
                                onClicked: {
                                    speak(phraseText)
                                    // Optional: animate click
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

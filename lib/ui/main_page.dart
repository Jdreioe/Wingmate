import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/ui/fetch_voices_page.dart';
import 'package:wingmate/services/tts/azure_text_to_speech.dart';
import 'package:wingmate/dialogs/profile_dialog.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/utils/app_database.dart';
import 'package:wingmate/utils/said_text_item.dart';
import 'package:wingmate/utils/speech_service_config.dart';
import 'package:wingmate/ui/save_message_dialog.dart';
import 'dart:convert';
import 'package:wingmate/utils/said_text_dao.dart';

void handleApiResponse(String response) {
  debugPrint('API Response: $response');
  try {
    final jsonResponse = jsonDecode(response);
    // Process the JSON response
  } catch (e) {
    debugPrint('Error parsing JSON: $e');
  }
}
class MainPage extends StatefulWidget {
  final String speechServiceEndpoint;
  final String speechServiceKey;
  final Future<void> Function(String endpoint, String key) onSaveSettings;

  const MainPage({
    Key? key,
    required this.speechServiceEndpoint,
    required this.speechServiceKey,
    required this.onSaveSettings,
  }) : super(key: key);

  @override
  _MainPageState createState() => _MainPageState();
}

class _MainPageState extends State<MainPage> {
  // Controller for user-entered text and list to display said texts.
  final TextEditingController _messageController = TextEditingController();
  final List<String> _saidTextItems = [];
  bool isPlaying = false;
  
  AzureTts? azureTts;
  final SaidTextDao _saidTextDao = SaidTextDao(AppDatabase());

  @override
  void initState() {
    super.initState();
    _initializeAzureTts();
    _loadSaidTextItems();
    Hive.box('settings').watch(key: 'isPlaying').listen((event) {
      debugPrint('isPlaying changed to: ${event.value}');
      if (!isPlaying) {
        debugPrint("Reloading saidTextItems");
        _loadSaidTextItems();
      }

      
      setState(() {
        isPlaying = event.value as bool;
      });
    });
  }

  // Creates a new instance of AzureTts with the stored config and voice.
  Future<void> _initializeAzureTts() async {
    final settingsBox = Hive.box('settings');
    final voiceBox = Hive.box('selectedVoice');
    final config = settingsBox.get('config') as SpeechServiceConfig?;
    final voice = voiceBox.get('voice') as VoiceAdapter?;

    if (config != null) {
      setState(() {
        azureTts = AzureTts(
          subscriptionKey: config.key,
          region: config.endpoint,
          settingsBox: settingsBox,
          voiceBox: voiceBox,
          messageController: _messageController,
          context: context,
        );
      });
    }
  }

  Future<void> _loadSaidTextItems() async {
    final items = await _saidTextDao.getAll();
    setState(() {
      _saidTextItems.clear();
      _saidTextItems.addAll(items.map((item) => item.saidText ?? ''));
    });
  }

  // Adds the typed message to a local list for display.
  void _addMessage() {
    setState(() {
      _saidTextItems.add(_messageController.text);
    });
  }

  // Toggles between playing and pausing the TTS audio.
  void _togglePlayPause() async {
    debugPrint('_togglePlayPause triggered. Current isPlaying: $isPlaying');

    if (_messageController.text.isNotEmpty && azureTts != null) {
      if (!isPlaying) {

        
        await azureTts!.generateSSML(_messageController.text);
      } else {
        debugPrint('Pausing playback...');
        await azureTts!.pause();
        isPlaying = false;
      }
    }
  }

  void _showSaveMessageDialog() {
    showDialog(
      context: context,
      builder: (context) {
        return SaveMessageDialog(
          onSave: (message, category) async {
            // Handle saving the message and category
            debugPrint('Message: $message, Category: $category');
            final saidTextItem = SaidTextItem(
              saidText: message,
              date: DateTime.now().millisecondsSinceEpoch,
              voiceName: azureTts!.voiceBox.get('currentVoice').name,
              createdAt: DateTime.now().millisecondsSinceEpoch,
            );
            await azureTts!.generateSSMLForItem(saidTextItem);
          },
        );
      },
    );
  }

  @override
  void dispose() {
    _messageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Builds the main UI with an AppBar and text input area.
    return Scaffold(
      appBar: AppBar(
        title: const Text('Wingman'),
        centerTitle: true,
        leading: IconButton(
          icon: const Icon(Icons.person),
          onPressed: () {
            showProfileDialog(
              context,
              widget.speechServiceEndpoint,
              widget.speechServiceKey,
              widget.onSaveSettings,
            );
          },
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => FetchVoicesPage(
                    endpoint: widget.speechServiceEndpoint,
                    subscriptionKey: widget.speechServiceKey,
                  ),
                ),
              );
            },
          ),
          IconButton(
            icon: const Icon(Icons.save),
            onPressed: _showSaveMessageDialog,
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: ListView.builder(
              itemCount: _saidTextItems.length,
              itemBuilder: (context, index) {
                final item = _saidTextItems[index];
                final isCategory = item.contains('Category:');
                return ListTile(
                  leading: Icon(isCategory ? Icons.folder : Icons.speaker_phone),
                  title: Text(item),
                );
              },
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(8.0),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _messageController,
                    minLines: 1,
                    maxLines: 5,
                    decoration: InputDecoration(
                      labelText: 'Enter your message',
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(5.0),
                      ),
                      focusedBorder: const OutlineInputBorder(
                        borderSide: BorderSide(color: Colors.blue, width: 2.0),
                      ),
                      enabledBorder: OutlineInputBorder(
                        borderSide: BorderSide(color: Colors.grey, width: 1.0),
                      ),
                    ),
                  ),
                ),
                IconButton(
                  icon: Icon(isPlaying ? Icons.pause : Icons.play_arrow),
                  onPressed: _togglePlayPause,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

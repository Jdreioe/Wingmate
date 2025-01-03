import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/screens/fetch_voices_page.dart';
import 'package:wingmate/services/azure_text_to_speech.dart';
import 'package:wingmate/dialogs/profile_dialog.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/utils/speech_service_config.dart';

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

  @override
  void initState() {
    super.initState();
    // Initialize the Azure TTS service when the MainPage is ready.
    _initializeAzureTts();
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
    } else {
      debugPrint('API key or endpoint not found in Hive box');
    }
  }

  // Adds the typed message to a local list for display.
  void _addMessage() {
    setState(() {
      _saidTextItems.add(_messageController.text);
    });
  }

  // Toggles between playing and pausing the TTS audio.
  void _togglePlayPause() async {
    if (_messageController.text.isNotEmpty && azureTts != null) {
      setState(() {
        isPlaying = !isPlaying;
      });
      if (isPlaying) {
        await azureTts!.speakText(_messageController.text);
      } else {
        await azureTts!.pause();
        setState(() {
          isPlaying = false; // Ensure the icon is set to play when paused
        });
      }
    } else {
      debugPrint('AzureTts is not initialized or message is empty');
    }
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
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: ListView.builder(
              itemCount: _saidTextItems.length,
              itemBuilder: (context, index) {
                return ListTile(
                  title: Text(_saidTextItems[index]),
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

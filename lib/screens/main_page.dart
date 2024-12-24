import 'package:flutter/material.dart';
import 'package:wingmancrossplatform/models/voice_model.dart';
import 'package:wingmancrossplatform/screens/fetch_voices_page.dart';
import 'package:wingmancrossplatform/services/azure_text_to_speech.dart';
import 'package:wingmancrossplatform/screens/profile_dialog.dart';
import 'package:hive/hive.dart';
import 'package:wingmancrossplatform/utils/speech_service_config.dart';

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
  final TextEditingController _messageController = TextEditingController();
  final List<String> _saidTextItems = [];
  bool isPlaying = false;

  AzureTts? azureTts;

  @override
  void initState() {
    super.initState();
    _initializeAzureTts();
  }

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
        );
      });
    } else {
      debugPrint('API key or endpoint not found in Hive box');
    }
  }

  void _addMessage() {
    setState(() {
      _saidTextItems.add(_messageController.text);
      _messageController.clear();
    });
  }

  void _togglePlayPause() async {
    if (_messageController.text.isNotEmpty && azureTts != null) {
      setState(() {
        isPlaying = !isPlaying;
      });
      if (isPlaying) {
        await azureTts!.speakText(_messageController.text);
      } else {
        // Handle pause functionality if needed
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
                  icon: const Icon(Icons.send),
                  onPressed: _addMessage,
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

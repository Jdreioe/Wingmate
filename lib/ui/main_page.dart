import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:math' as math;

import 'package:wingmate/ui/fetch_voices_page.dart';
import 'package:wingmate/services/tts/azure_text_to_speech.dart';
import 'package:wingmate/dialogs/profile_dialog.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/utils/app_database.dart';
import 'package:wingmate/utils/said_text_item.dart';
import 'package:wingmate/utils/speech_service_config.dart';
import 'package:wingmate/ui/save_message_dialog.dart';

import 'package:wingmate/utils/said_text_dao.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';

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
  final FocusNode _messageFocusNode = FocusNode();
  final List<SaidTextItem> _saidTextItems = []; // store items instead of just text
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

      
      setState(() {
        isPlaying = event.value as bool;
      });
      if (!isPlaying) {
        debugPrint("Reloading saidTextItems");
        _loadSaidTextItems();
      }

    });
  }

  // Creates a new instance of AzureTts with the stored config and voice.
  Future<void> _initializeAzureTts() async {
    final settingsBox = Hive.box('settings');
    final voiceBox = Hive.box('selectedVoice');
    final config = settingsBox.get('config') as SpeechServiceConfig?;

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
    final selectedVoice = azureTts!.voiceBox.get('currentVoice').name;
    final selectedLanguage = azureTts!.voiceBox.get('currentVoice').selectedLanguage;
    final pitch = azureTts!.voiceBox.get('currentVoice').pitch;
    final speed = azureTts!.voiceBox.get('currentVoice').rate;
    
    final items = await _saidTextDao.getFilteredItems(selectedLanguage, speed, selectedVoice, pitch);
    setState(() {
          _saidTextItems.clear();
    _saidTextItems.addAll(items.map((item) {
      item.saidText = convertToUserFriendlyTags(item.saidText!);
      return item;
    }).toList());
  });

  }

String convertToUserFriendlyTags(String text) {

  final newText = text
      .replaceAll('<lang xml:lang="en-US">', '<en>')
      .replaceAll('</lang>', '</en>')
      .replaceAll('<break time="2s"/>', "<2s>");
      
  
  int newOffset = newText.indexOf("<en>") >= 0
      ? newText.indexOf("<en>") + "<en>".length
      : 0;

  _messageController.text = newText;
  _messageController.selection = TextSelection.collapsed(offset: newOffset);

  return newText;
  
}
void _onTextChanged(String text) {
  setState(() {
    final oldValue = _messageController.value;
    final newText = convertToUserFriendlyTags(text);
    final newOffset = math.min(oldValue.selection.baseOffset, newText.length);
    final newExtentOffset = math.min(oldValue.selection.extentOffset, newText.length);
    _messageController.value = TextEditingValue(
      text: newText,
      selection: TextSelection(baseOffset: newOffset, extentOffset: newExtentOffset),
    );
  });
}

String convertToXmlTags(String text) {
  return text
      .replaceAll('<en>', '<lang xml:lang="en-US">')
      .replaceAll('</en>', '</lang>')
      .replaceAll("<2s>", '<break time="2s"/>');

}

  // Toggles between playing and pausing the TTS audio.
void _togglePlayPause() async {
  debugPrint('_togglePlayPause triggered. Current isPlaying: $isPlaying');

  if (_messageController.text.isNotEmpty && azureTts != null) {
    if (!isPlaying) {
      String userFriendlyText = _messageController.text;
      String xmlText = convertToXmlTags(userFriendlyText);
      await azureTts!.generateSSML(xmlText);
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
          String xmlMessage = convertToXmlTags(message);
          final saidTextItem = SaidTextItem(
            saidText: xmlMessage,
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
  Future<bool> _deleteSaidTextItem(int index) async {

    final items = await _saidTextDao.getAll();
    if (items.isNotEmpty && index < items.length) {
      final result = await _saidTextDao.delete(items[index].id!);
      if (result > 0) {
        setState(() {
          _saidTextItems.removeAt(index);
        });
        return true;
      }
    }
    return false;
  }

  Future<void> _reorderItems(int oldIndex, int newIndex) async {
    setState(() {
      if (newIndex > oldIndex) {
        newIndex -= 1;
      }
      final item = _saidTextItems.removeAt(oldIndex);
      _saidTextItems.insert(newIndex, item);
    });
    // update positions in the DB
    for (int i = 0; i < _saidTextItems.length; i++) {
      _saidTextItems[i].position = i;
      await _saidTextDao.updateItem(_saidTextItems[i]);
    }
  }

  // Adds the selected XML tag to the message field at the current cursor position.
  void _addXmlTag(String tag) {

  final text = _messageController.text;
  final selection = _messageController.selection;
  final userFriendlyTag = convertToUserFriendlyTags(tag);

  String newText;
  if (text.isEmpty) {
    newText = userFriendlyTag;
  } else {
    newText = text.replaceRange(selection.start, selection.end, userFriendlyTag);
  }

  int newOffset = selection.start + userFriendlyTag.indexOf('>') + 1;
  if (newOffset > newText.length) {
    newOffset = newText.length;
  }

  _messageController.value = TextEditingValue(
    text: newText,
    selection: TextSelection.collapsed(offset: newOffset),
  );

  _messageFocusNode.requestFocus();
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
        title: Text(AppLocalizations.of(context)?.appTitle ?? 'Title'),
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
            icon: const Icon(Icons.add),
            onPressed: _showSaveMessageDialog,
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: ReorderableListView.builder(
              onReorder: _reorderItems,
              itemCount: _saidTextItems.length,
              itemBuilder: (context, index) {
                final item = _saidTextItems[index];
                final dateString = DateTime.fromMillisecondsSinceEpoch(item.date ?? 0)
                    .toLocal()
                    .toString()
                    .substring(0, 16) // e.g. "YYYY-MM-DD HH:MM"
                    .replaceRange(0, 5, item.date == null ? '' : ''); // minor format tweak
                return Dismissible(
                  key: ValueKey(item.saidText! + DateTime.now().toString()), // Unique key
                  direction: DismissDirection.endToStart,
                  background: Container(
                    alignment: Alignment.centerRight,
                    padding: const EdgeInsets.symmetric(horizontal: 40),
                    color: Colors.red,
                    child: const Icon(
                      Icons.delete,
                      color: Colors.white,
                    ),
                  ),
                  confirmDismiss: (direction) async {
                    return await _deleteSaidTextItem(index);
                  },
                  child: ListTile(
                    key: ValueKey(item),
                    leading: Icon(item.saidText!.contains('Category:') ? Icons.folder : Icons.speaker_phone),
                    title: Text(item.saidText ?? ''),
                    subtitle: Text(dateString),
                    onTap: item.audioFilePath != null
                        ? () async {
                            await azureTts?.playText(
                              DeviceFileSource(item.audioFilePath!),
                            );
                          }
                        : null,
                  ),
                );
              },
            ),
          ),
                    // Add XML shortcuts row
          Padding(
            padding: const EdgeInsets.all(8.0),
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  ElevatedButton(
                    onPressed: () => _addXmlTag('<lang xml:lang="en-US"> </lang>'),
                    child: Text('English'),
                  ),
                  SizedBox(width: 8),
                  ElevatedButton(
                    onPressed: () => _addXmlTag('<break time="2s"/>'),
                    child: Text('2 sec break mid sentence'),
                  ),
                  // Add more buttons for other XML tags as needed
                ],
              ),
            ),
          ),

          Padding(
            padding: const EdgeInsets.only(bottom: 16.0, left: 16.0, right: 16.0), // Add bottom padding here

            

            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _messageController,
                    focusNode: _messageFocusNode,
                    minLines: 1,
                    maxLines: 5,
                    decoration: InputDecoration(
                      labelText: 'Enter text',
                      
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(5.0),
                      ),

                      
                      focusedBorder: const OutlineInputBorder(
                        borderSide: BorderSide(color: Colors.blue, width: 2.0),
                      ),
                      enabledBorder: const OutlineInputBorder(
                        borderSide: BorderSide(color: Colors.grey, width: 1.0),
                      ),
                    ),
                    onChanged: (text) {
                      setState(() {
  final oldValue = _messageController.value;
  final newText = convertToUserFriendlyTags(text);
  final newOffset = math.min(oldValue.selection.baseOffset, newText.length);
  final newExtentOffset = math.min(oldValue.selection.extentOffset, newText.length);
  _messageController.value = TextEditingValue(
    text: newText,
    selection: TextSelection(baseOffset: newOffset, extentOffset: newExtentOffset),
  );
});
                    },

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


import 'dart:convert';
import 'dart:io';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:math' as math;

import 'package:wingmate/ui/fetch_voices_page.dart';
import 'package:wingmate/services/tts/azure_text_to_speech.dart';
import 'package:wingmate/dialogs/profile_dialog.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/ui/folder_page.dart';
import 'package:wingmate/utils/app_database.dart';
import 'package:wingmate/utils/said_text_item.dart';
import 'package:wingmate/utils/speech_service_config.dart';
import 'package:wingmate/ui/save_message_dialog.dart';
import 'package:wingmate/utils/speech_item.dart';
import 'package:wingmate/utils/speech_item_dao.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:wingmate/ui/history_page.dart';
import 'package:share_plus/share_plus.dart';

import '../utils/said_text_dao.dart';

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
  final List<dynamic> _items = []; // Store both folders and speech items
  bool isPlaying = false;
  bool isSomeFolderSelected = false;
  int currentFolderId = -1;

  AzureTts? azureTts;
  final SaidTextDao _saidTextDao = SaidTextDao(AppDatabase());
  final SpeechItemDao _speechItemDao = SpeechItemDao(AppDatabase());
  

  @override
  void initState() {
    super.initState();
    _initializeAzureTts();
    _loadItems();
    Hive.box('settings').watch(key: 'isPlaying').listen((event) {
      debugPrint('isPlaying changed to: ${event.value}');

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

  Future<void> _loadItems() async {
    final items = await _speechItemDao.getAllRootItems();
    setState(() {
      _items.clear();
      _items.add('History'); // Add History folder
      _items.addAll(items);
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
    final message = _messageController.text;
    showDialog(
      context: context,
      builder: (context) {
        return SaveMessageDialog(
          initialMessage: message,
          onSave: (message, category, categoryChecked) async {
            // Handle saving the message and category
            debugPrint('Message: $message, Category: $category');
            final speechItem = SpeechItem(
              name: category,
              text: message,
              isFolder: categoryChecked,
              parentId: isSomeFolderSelected ? currentFolderId : null,
              createdAt: DateTime.now().millisecondsSinceEpoch,
            );
            final id = await _speechItemDao.insertItem(speechItem);
            speechItem.id = id;
            await azureTts!.generateSSMLForItem(speechItem);
            setState(() {
              _items.add(speechItem);
            });
          },
        );
      },
    );
  }

  Future<bool> _deleteItem(int index) async {
    final item = _items[index];
    if (item is SpeechItem) {
      final result = await _speechItemDao.deleteItem(item.id!);
      if (result > 0) {
        if (item.filePath != null) {
          final file = File(item.filePath!);
          if (await file.exists()) {
            await file.delete();
          }
        }
        setState(() {
          _items.removeAt(index);
        });
        return true;
      }
    }
    return false;
  }

  Future<void> _reorderItems(int oldIndex, int newIndex) async {
    if (_items[oldIndex] == 'History' || _items[newIndex] == 'History') {
      return; // Do not allow reordering the History folder
    }
    setState(() {
      if (newIndex > oldIndex) {
        newIndex -= 1;
      }
      final item = _items.removeAt(oldIndex);
      _items.insert(newIndex, item);
    });
    // update positions in the DB
    for (int i = 0; i < _items.length; i++) {
      if (_items[i] is SpeechItem) {
        (_items[i] as SpeechItem).position = i;
        await _speechItemDao.updateItem(_items[i] as SpeechItem);
      }
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
      newText =
          text.replaceRange(selection.start, selection.end, userFriendlyTag);
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

  void selectFolder(int folderId, String folderName) async {
    setState(() {
      isSomeFolderSelected = true;
      currentFolderId = folderId;
      _items.clear();
    });

    if (folderId == -1) {
      _loadItems();
      return;
    }

    final items = await _speechItemDao.getAllItemsInFolder(folderId);
    setState(() {
      _items.addAll(items);
    });
  }

  void selectRootFolder() async {
    setState(() {
      isSomeFolderSelected = false;
      currentFolderId = -1;
      _items.clear();
    });

    final rootItems = await _speechItemDao.getAllRootItems();
    setState(() {
      _items.add('History');
      _items.addAll(rootItems);
    });
  }

  Future<void> _shareFile(String filePath) async {
    try {
      await Share.shareXFiles([XFile(filePath)]);
    } catch (e) {
      final file = File(filePath);
      final bytes = await file.readAsBytes();
      await Clipboard.setData(ClipboardData(text: base64Encode(bytes)));
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('File copied to clipboard')),
      );
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
        title: Text(isSomeFolderSelected ? 'Folder' : (AppLocalizations.of(context)?.appTitle ?? 'Title')),
        centerTitle: true,
        leading: IconButton(
          icon: Icon(isSomeFolderSelected ? Icons.arrow_back : Icons.person),
          onPressed: () {
            if (isSomeFolderSelected) {
              if (currentFolderId == -1) {
                selectRootFolder();
              } else {
                _speechItemDao.getItemById(currentFolderId).then((currentFolder) {
                  if (currentFolder != null && currentFolder.parentId != null) {
                    selectFolder(currentFolder.parentId!, currentFolder.name!);
                  } else {
                    selectRootFolder();
                  }
                });
              }
            } else {
              showProfileDialog(
                context,
                widget.speechServiceEndpoint,
                widget.speechServiceKey,
                widget.onSaveSettings,
              );
            }
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
            child: ReorderableListView(
              onReorder: _reorderItems,
              children: _items.map((item) {
                if (item is String && item == 'History') {
                  return ListTile(
                    key: ValueKey(item),
                    leading: Icon(Icons.folder),
                    title: Text(item),
                    onTap: () {
                      Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (context) => HistoryPage(),
                        ),
                      );
                    },
                  );
                } else if (item is SpeechItem) {
                  return Dismissible(
                    key: ValueKey(item.id),
                    direction: DismissDirection.horizontal,
                    background: Container(
                      alignment: Alignment.centerLeft,
                      padding: const EdgeInsets.symmetric(horizontal: 40),
                      color: Colors.blue,
                      child: const Icon(
                        Icons.share,
                        color: Colors.white,
                      ),
                    ),
                    secondaryBackground: Container(
                      alignment: Alignment.centerRight,
                      padding: const EdgeInsets.symmetric(horizontal: 40),
                      color: Colors.red,
                      child: const Icon(
                        Icons.delete,
                        color: Colors.white,
                      ),
                    ),
                    confirmDismiss: (direction) async {
                      if (direction == DismissDirection.startToEnd) {
                        if (item.filePath != null) {
                          await _shareFile(item.filePath!);
                        }
                        return false;
                      } else {
                        return await _deleteItem(_items.indexOf(item));
                      }
                    },
                    child: ListTile(
                      key: ValueKey(item),
                      leading: Icon(item.isFolder! ? Icons.folder : Icons.speaker_phone),
                      title: Text(item.name ?? ''),
                      subtitle: item.isFolder! ? null : Text(item.text ?? ''),
                      onTap: item.isFolder!
                          ? () {
                              selectFolder(item.id!, item.name!);
                            }
                          : () async {
                              if (item.text != null) {
                                final speechItem = await _speechItemDao.getItemByText(item.text!);
                                if (speechItem?.filePath != null) {
                                  await azureTts!.playText(DeviceFileSource(speechItem!.filePath!));
                                }
                              }
                            },
                    ),
                  );
                } else {
                  return Container();
                }
              }).toList(),
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
                    onPressed: () =>
                        _addXmlTag('<lang xml:lang="en-US"> </lang>'),
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
            padding: const EdgeInsets.only(
                bottom: 16.0,
                left: 16.0,
                right: 16.0), // Add bottom padding here

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
                  textInputAction:
                      TextInputAction.done, // Set the text input action
                  keyboardType: TextInputType.text, // Set the keyboard type
                )),
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

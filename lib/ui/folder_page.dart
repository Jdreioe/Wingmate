import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/cupertino.dart'; // Import for Cupertino widgets
import 'package:wingmate/utils/speech_item_dao.dart';
import 'package:wingmate/utils/app_database.dart';
import 'package:wingmate/utils/speech_item.dart';
import 'package:wingmate/services/tts/azure_text_to_speech.dart';
import 'package:hive/hive.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:wingmate/utils/said_text_dao.dart';
import 'package:wingmate/utils/speech_service_config.dart';
import 'dart:io';
import 'package:share_plus/share_plus.dart';
import 'package:flutter/services.dart';

class FolderPage extends StatefulWidget {
  final int folderId;

  const FolderPage({Key? key, required this.folderId}) : super(key: key);

  @override
  _FolderPageState createState() => _FolderPageState();
}

class _FolderPageState extends State<FolderPage> {
  final SpeechItemDao _speechItemDao = SpeechItemDao(AppDatabase());
  final List<SpeechItem> _speechItems = [];
  final player = AudioPlayer();
  late AzureTts azureTts;
  final SaidTextDao _saidTextDao = SaidTextDao(AppDatabase());

  @override
  void initState() {
    super.initState();
    _initializeAzureTts();
    _loadSpeechItems();
  }

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
          messageController: TextEditingController(),
          context: context,
        );
      });
    }
  }

  Future<void> _loadSpeechItems() async {
    final items = await _speechItemDao.getAllItemsInFolder(widget.folderId);
    setState(() {
      _speechItems.clear();
      _speechItems.addAll(items);
    });
  }

  Future<bool> _deleteSpeechItem(int index) async {
    final item = _speechItems[index];
    final result = await _speechItemDao.deleteItem(item.id!);
    if (result > 0) {
      if (item.filePath != null) {
        final file = File(item.filePath!);
        if (await file.exists()) {
          await file.delete();
        }
      }
      setState(() {
        _speechItems.removeAt(index);
      });
      return true;
    }
    return false;
  }

  Future<void> _shareFile(String filePath) async {
    try {
      await Share.shareXFiles([XFile(filePath)]);
    } catch (e) {
      final file = File(filePath);
      final bytes = await file.readAsBytes();
      await Clipboard.setData(ClipboardData(text: base64Encode(bytes)));
      // Show snackbar using platform-aware way.
      if (Platform.isIOS) {
        showCupertinoDialog(
          context: context,
          builder: (context) => CupertinoAlertDialog(
            content: const Text('File copied to clipboard'),
            actions: [
              CupertinoDialogAction(
                child: const Text('OK'),
                onPressed: () => Navigator.of(context).pop(),
              ),
            ],
          ),
        );
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('File copied to clipboard')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    // Use CupertinoPageScaffold for iOS
    if (Platform.isIOS) {
      return CupertinoPageScaffold(
        navigationBar: CupertinoNavigationBar(
          middle: const Text('Folder'),
        ),
        child: ListView.builder(
          itemCount: _speechItems.length,
          itemBuilder: (context, index) {
            final item = _speechItems[index];
            return CupertinoSlidingSegmentedControl<int>(
              groupValue: 0, // Dummy value, not used for actual control in this case
              onValueChanged: (int? value) {
                // This callback is required, but we'll handle actions in GestureDetector
              },
              children: {
                0: GestureDetector( // Wrap the entire list item with GestureDetector
                  onHorizontalDragEnd: (details) async {
                    // ручная обработка свайпа.
                    if (details.primaryVelocity! > 0) { // Свайп вправо (Share)
                      if (item.filePath != null) {
                        await _shareFile(item.filePath!);
                      }
                    } else if (details.primaryVelocity! < 0) { // Свайп влево (Delete)
                      await _deleteSpeechItem(index);
                    }
                  },
                  child: Container(
                    color: CupertinoColors.white, // Or any background you prefer
                    child: CupertinoListTile(
                      key: ValueKey(item),
                      leading: Icon(item.isFolder! ? CupertinoIcons.folder : CupertinoIcons.speaker_2),
                      title: Text(item.name ?? ''),
                      subtitle: item.isFolder! ? null : Text(item.text ?? ''),
                      onTap: item.isFolder!
                          ? () {
                              Navigator.push(
                                context,
                                CupertinoPageRoute( // Use CupertinoPageRoute
                                  builder: (context) => FolderPage(folderId: item.id!),
                                ),
                              );
                            }
                          : () async {
                              if (item.text != null) {
                                final saidTextItem = await _saidTextDao.getItemByText(item.text!);
                                if (saidTextItem?.audioFilePath != null) {
                                  await azureTts.playText(DeviceFileSource(saidTextItem!.audioFilePath!));
                                }
                              }
                            },
                    ),
                  ),
                ),
              },
            );
          },
        ),
      );
    }
    // Use Scaffold for Android
    return Scaffold(
      appBar: AppBar(
        title: const Text('Folder'),
      ),
      body: ListView.builder(
        itemCount: _speechItems.length,
        itemBuilder: (context, index) {
          final item = _speechItems[index];
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
                return await _deleteSpeechItem(index);
              }
            },
            child: ListTile(
              key: ValueKey(item),
              leading: Icon(item.isFolder! ? Icons.folder : Icons.speaker_phone),
              title: Text(item.name ?? ''),
              subtitle: item.isFolder! ? null : Text(item.text ?? ''),
              onTap: item.isFolder!
                  ? () {
                      Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (context) => FolderPage(folderId: item.id!),
                        ),
                      );
                    }
                  : () async {
                      if (item.text != null) {
                        final saidTextItem = await _saidTextDao.getItemByText(item.text!);
                        if (saidTextItem?.audioFilePath != null) {
                          await azureTts.playText(DeviceFileSource(saidTextItem!.audioFilePath!));
                        }
                      }
                    },
            ),
          );
        },
      ),
    );
  }
}


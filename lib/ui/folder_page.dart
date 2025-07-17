import 'dart:convert';
import 'package:flutter/material.dart';
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
      // Use SnackBar for all platforms
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('File copied to clipboard')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    // Consolidated into a single Material Scaffold for all platforms
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
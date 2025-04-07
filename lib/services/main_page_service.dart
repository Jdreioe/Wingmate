import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:http/http.dart' as http;
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/services/tts/azure_text_to_speech.dart';
import 'package:wingmate/utils/app_database.dart';
import 'package:wingmate/utils/said_text_dao.dart';
import 'package:wingmate/utils/speech_item.dart';
import 'package:wingmate/utils/speech_item_dao.dart';
import 'package:wingmate/utils/speech_service_config.dart';
import 'package:wingmate/utils/subscription_manager.dart';

class MainPageService {
  final TextEditingController messageController;
  final FocusNode messageFocusNode;
  final SpeechItemDao speechItemDao;
  final SaidTextDao saidTextDao;
  final SubscriptionManager subscriptionManager;
  final Box<dynamic> settingsBox;
  final Box<dynamic> voiceBox;
  final BuildContext context;
  final String speechServiceEndpoint;
  final String speechServiceKey;
  final Future<void> Function(String endpoint, String key) onSaveSettings;

  AzureTts? azureTts;
  AudioPlayer? audioPlayer;
  bool isPlaying = false;
  bool isSomeFolderSelected = false;
  int currentFolderId = -1;
  String previousText = '';
  List<dynamic> items = [];

  MainPageService({
    required this.messageController,
    required this.messageFocusNode,
    required this.speechItemDao,
    required this.saidTextDao,
    required this.subscriptionManager,
    required this.settingsBox,
    required this.voiceBox,
    required this.context,
    required this.speechServiceEndpoint,
    required this.speechServiceKey,
    required this.onSaveSettings,
  });

  Future<void> initialize() async {
    await _initializeAzureTts();
    await _loadItems();
    _watchIsPlaying();
  }

  Future<void> _initializeAzureTts() async {
    final config = settingsBox.get('config') as SpeechServiceConfig?;
    if (config != null) {
      azureTts = AzureTts(
        subscriptionKey: config.key,
        region: config.endpoint,
        settingsBox: settingsBox,
        voiceBox: voiceBox,
        messageController: messageController,
        context: context,
      );
    }
  }

  void _watchIsPlaying() {
    settingsBox.watch(key: 'isPlaying').listen((event) {
      isPlaying = event.value as bool;
    });
  }

  Future<void> _loadItems() async {
    final loadedItems = await speechItemDao.getAllRootItems();
    items.clear();
    items.add('History');
    items.addAll(loadedItems);
  }

  String convertToUserFriendlyTags(String text) {
    final newText = text
        .replaceAll('<lang xml:lang="en-US">', '<en>')
        .replaceAll('</lang>', '</en>')
        .replaceAll('<break time="2s"/>', "<2s>");
    int newOffset = newText.indexOf("<en>") >= 0
        ? newText.indexOf("<en>") + "<en>".length
        : 0;
    messageController.text = newText;
    messageController.selection = TextSelection.collapsed(offset: newOffset);
    return newText;
  }

  String convertToXmlTags(String text) {
    return text
        .replaceAll('<en>', '<lang xml:lang="en-US">')
        .replaceAll('</en>', '</lang>')
        .replaceAll("<2s>", '<break time="2s"/>');
  }

  Future<void> togglePlayPause() async {
    if (messageController.text.isNotEmpty && azureTts != null) {
      if (!isPlaying) {
        String userFriendlyText = messageController.text;
        String xmlText = convertToXmlTags(userFriendlyText);
        await azureTts!.generateSSML(xmlText);
      } else {
        await azureTts!.pause();
        isPlaying = false;
      }
    }
  }

  Future<void> handleSaveMessage(String message, String category, bool categoryChecked) async {
    final speechItem = SpeechItem(
      name: category,
      text: message,
      isFolder: categoryChecked,
      parentId: isSomeFolderSelected ? currentFolderId : null,
      createdAt: DateTime.now().millisecondsSinceEpoch,
    );
    final id = await speechItemDao.insertItem(speechItem);
    speechItem.id = id;
    await azureTts!.generateSSMLForItem(speechItem);
    items.add(speechItem);
  }

  Future<bool> deleteItem(int index) async {
    final item = items[index];
    if (item is SpeechItem) {
      final result = await speechItemDao.deleteItem(item.id!);
      if (result > 0) {
        if (item.filePath != null) {
          final file = File(item.filePath!);
          if (await file.exists()) {
            await file.delete();
          }
        }
        items.removeAt(index);
        return true;
      }
    }
    return false;
  }

  Future<void> reorderItems(int oldIndex, int newIndex) async {
    if (items[oldIndex] == 'History' || items[newIndex] == 'History') {
      return;
    }
    if (newIndex > oldIndex) {
      newIndex -= 1;
    }
    final item = items.removeAt(oldIndex);
    items.insert(newIndex, item);
    
    for (int i = 0; i < items.length; i++) {
      if (items[i] is SpeechItem) {
        (items[i] as SpeechItem).position = i;
        await speechItemDao.updateItem(items[i] as SpeechItem);
      }
    }
  }

  void addXmlTag(String tag) {
    final text = messageController.text;
    final selection = messageController.selection;
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

    messageController.value = TextEditingValue(
      text: newText,
      selection: TextSelection.collapsed(offset: newOffset),
    );

    messageFocusNode.requestFocus();
  }

  void selectFolder(int folderId, String folderName) async {
    isSomeFolderSelected = true;
    currentFolderId = folderId;
    items.clear();

    if (folderId == -1) {
      await _loadItems();
      return;
    }

    final folderItems = await speechItemDao.getAllItemsInFolder(folderId);
    items.addAll(folderItems);
  }

  void selectRootFolder() async {
    isSomeFolderSelected = false;
    currentFolderId = -1;
    items.clear();

    final rootItems = await speechItemDao.getAllRootItems();
    items.add('History');
    items.addAll(rootItems);
  }

  Future<void> playSpeechItem(SpeechItem item) async {
    if (item.text != null) {
      final speechItem = await speechItemDao.getItemByText(item.text!);
      if (speechItem?.filePath != null) {
        await azureTts!.playText(DeviceFileSource(speechItem!.filePath!));
      }
    }
  }

  void dispose() {
    messageController.dispose();
    messageFocusNode.dispose();
    audioPlayer?.dispose();
  }
} 
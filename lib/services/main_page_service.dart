import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:http/http.dart' as http;
import 'package:wingmate/data/phrase_item_dao.dart';
import 'package:wingmate/models/voice_model.dart';
import 'package:wingmate/services/tts/azure_text_to_speech.dart';
import 'package:wingmate/data/app_database.dart';
import 'package:wingmate/data/said_text_dao.dart';
import 'package:wingmate/data/phrase_item.dart';
import 'package:wingmate/data/phrase_item_dao.dart';
import 'package:wingmate/data/category_dao.dart';
import 'package:wingmate/data/category_item.dart';
import 'package:wingmate/config/speech_service_config.dart';
import 'package:wingmate/services/subscription_manager.dart';
import 'package:wingmate/data/ui_settings.dart';

class MainPageService {
  final TextEditingController messageController;
  final FocusNode messageFocusNode;
  final PhraseItemDao phraseItemDao;
  final SaidTextDao saidTextDao;
  final CategoryDao categoryDao; // New: CategoryDao
  final SubscriptionManager subscriptionManager;
  final Box<dynamic> settingsBox;
  final Box<dynamic> voiceBox;
  final BuildContext context;
  final String speechServiceEndpoint;
  final String speechServiceKey;
  final Future<void> Function(String endpoint, String key, UiSettings uiSettings) onSaveSettings;
  final UiSettings uiSettings;

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
    required this.phraseItemDao,
    required this.saidTextDao,
    required this.categoryDao,
    required this.subscriptionManager,
    required this.settingsBox,
    required this.voiceBox,
    required this.context,
    required this.speechServiceEndpoint,
    required this.speechServiceKey,
    required this.onSaveSettings,
    required this.uiSettings,
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
        context: context,
        saidTextDao: saidTextDao,
      );
    }
  }

  void _watchIsPlaying() {
    settingsBox.watch(key: 'isPlaying').listen((event) {
      isPlaying = event.value as bool;
    });
  }

  Future<void> _loadItems() async {
    final loadedItems = await phraseItemDao.getAllRootItems();
    items.clear();
    items.add('History');
    items.addAll(loadedItems);
  }

  Future<void> togglePlayPause() async {
    if (messageController.text.isNotEmpty && azureTts != null) {
      if (!isPlaying) {
        await azureTts!.speakText(messageController.text);
      } else {
        await azureTts!.pause();
        isPlaying = false;
      }
    }
  }

  Future<void> handleSaveMessage(String message, String category, bool categoryChecked) async {
    final phraseItem = PhraseItem(
      name: category,
      text: message,
      isCategory: categoryChecked,
      parentId: isSomeFolderSelected ? currentFolderId : null,
      createdAt: DateTime.now().millisecondsSinceEpoch,
    );
    final id = await phraseItemDao.insert(phraseItem);
    phraseItem.id = id;
    phraseItem.filePath = await azureTts!.generateAndCacheAudioForItem(phraseItem);
    await phraseItemDao.updateItem(phraseItem);
    items.add(phraseItem);
  }

  Future<void> loadPhraseItems() async {
    await _loadItems();
  }

  Future<bool> deleteItem(int index) async {
    final item = items[index];
    if (item is PhraseItem) {
      final result = await phraseItemDao.deleteItem(item.id!);
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
      if (items[i] is PhraseItem) {
        (items[i] as PhraseItem).position = i;
        await phraseItemDao.updateItem(items[i] as PhraseItem);
      }
    }
  }

  void addXmlTag(String tag) {
    final text = messageController.text;
    final selection = messageController.selection;

    String newText;
    if (text.isEmpty) {
      newText = tag;
    } else {
      newText = text.replaceRange(selection.start, selection.end, tag);
    }

    int newOffset = selection.start + tag.length;
    if (newOffset > newText.length) {
      newOffset = newText.length;
    }

    messageController.value = TextEditingValue(
      text: newText,
      selection: TextSelection.collapsed(offset: newOffset),
    );

    messageFocusNode.requestFocus();
  }

  void selectFolder(int categoryId, String folderName) async {
    isSomeFolderSelected = true;
    currentFolderId = categoryId;
    items.clear();

    if (categoryId == -1) {
      await _loadItems();
      return;
    }

    final folderItems = await phraseItemDao.getAllItemsInCategory(categoryId);
    items.addAll(folderItems);
  }

  void selectRootFolder() async {
    isSomeFolderSelected = false;
    currentFolderId = -1;
    items.clear();

    final rootItems = await phraseItemDao.getAllRootItems();
    items.add('History');
    items.addAll(rootItems);
  }

  Future<void> playSpeechItem(PhraseItem item) async {
    if (item.text != null) {
      // The speakText method will handle caching and playback
      await azureTts!.speakText(item.text!);
    }
  }

  void dispose() {
    messageController.dispose();
    messageFocusNode.dispose();
    audioPlayer?.dispose();
  }
} 
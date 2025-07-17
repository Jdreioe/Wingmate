import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:wingmate/models/voice_model.dart';
import 'dart:math' as math;

import 'package:wingmate/ui/fetch_voices_page.dart';
import 'package:wingmate/services/tts/azure_text_to_speech.dart';
import 'package:wingmate/dialogs/profile_dialog.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/utils/app_database.dart';

import 'package:wingmate/utils/speech_service_config.dart';
import 'package:wingmate/ui/save_message_dialog.dart';
import 'package:wingmate/utils/speech_item.dart';
import 'package:wingmate/utils/speech_item_dao.dart';
import 'package:wingmate/ui/history_page.dart';
import 'package:share_plus/share_plus.dart';

import '../utils/said_text_dao.dart';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/foundation.dart'
    show defaultTargetPlatform, TargetPlatform;
import 'package:wingmate/utils/subscription_manager.dart';
import 'package:wingmate/utils/full_screen_text_view.dart';
import 'package:http/http.dart' as http;

import '../services/main_page_service.dart';
import '../widgets/xml_shortcuts_row.dart';
import '../widgets/message_input_row.dart';
import '../widgets/speech_item_list_tile.dart';

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
  late final MainPageService _service;
  bool _isSubscribed = false;
  late final SubscriptionManager _subscriptionManager;

  @override
  void initState() {
    super.initState();
    _initializeService();
    if (_isMobilePlatform()) {
      _subscriptionManager = SubscriptionManager(
        onSubscriptionStatusChanged: (isSubscribed) async {
          setState(() {
            _isSubscribed = isSubscribed;
          });
          if (isSubscribed) {
            await _fetchAzureSubscriptionDetails();
          }
        },
      );
      _subscriptionManager.initialize();
    }
  }

  void _initializeService() {
    _service = MainPageService(
      messageController: TextEditingController(),
      messageFocusNode: FocusNode(),
      speechItemDao: SpeechItemDao(AppDatabase()),
      saidTextDao: SaidTextDao(AppDatabase()),
      subscriptionManager: SubscriptionManager(
        onSubscriptionStatusChanged: (isSubscribed) {},
      ),
      settingsBox: Hive.box('settings'),
      voiceBox: Hive.box('selectedVoice'),
      context: context,
      speechServiceEndpoint: widget.speechServiceEndpoint,
      speechServiceKey: widget.speechServiceKey,
      onSaveSettings: widget.onSaveSettings,
    );
    _service.initialize();
  }

  bool _isMobilePlatform() {
    return !kIsWeb &&
        (defaultTargetPlatform == TargetPlatform.iOS ||
            defaultTargetPlatform == TargetPlatform.android);
  }

  Future<void> _fetchAzureSubscriptionDetails() async {
    final response = await http.post(
      Uri.parse('https://your-server-url/verify-subscription'),
      body: jsonEncode({'purchaseToken': 'your-purchase-token'}),
      headers: {'Content-Type': 'application/json'},
    );

    if (response.statusCode == 200) {
      final data = jsonDecode(response.body);
      final subscriptionKey = data['subscriptionKey'];
      final region = data['region'];
      await widget.onSaveSettings(region, subscriptionKey);
    } else {
      debugPrint('Failed to fetch subscription details');
    }
  }

  @override
  void dispose() {
    _service.dispose();
    _subscriptionManager.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Platform.isIOS
        ? _buildCupertinoScaffold()
        : _buildMaterialScaffold();
  }

  Widget _buildCupertinoScaffold() {
    return CupertinoPageScaffold(
      navigationBar: CupertinoNavigationBar(
        middle: Text(_service.isSomeFolderSelected
            ? 'Folder' : "Wingmate"
),
        leading: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: _handleLeadingIconPressed,
          child: Icon(_service.isSomeFolderSelected
              ? CupertinoIcons.back
              : CupertinoIcons.person),
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: _buildCupertinoAppBarActions(),
        ),
      ),
      child: SafeArea(
        child: Column(
          children: [
            Expanded(child: _buildCupertinoReorderableListView()),
            XmlShortcutsRow(
              onAddTag: _service.addXmlTag,
              isCupertino: true,
            ),
            MessageInputRow(
              controller: _service.messageController,
              focusNode: _service.messageFocusNode,
              onClear: () => _service.messageController.clear(),
              onPlayPause: _service.togglePlayPause,
              isPlaying: _service.isPlaying,
              isCupertino: true,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMaterialScaffold() {
    return Scaffold(
      appBar: AppBar(
        title: Text(_service.isSomeFolderSelected
            ? 'Folder'
            : "Wingmate"),
        centerTitle: true,
        leading: IconButton(
          icon: Icon(_service.isSomeFolderSelected
              ? Icons.arrow_back
              : Icons.person),
          onPressed: _handleLeadingIconPressed,
        ),
        actions: _buildMaterialAppBarActions(),
      ),
      body: Column(
        children: [
          Expanded(child: _buildReorderableListView()),
          XmlShortcutsRow(
            onAddTag: _service.addXmlTag,
          ),
          MessageInputRow(
            controller: _service.messageController,
            focusNode: _service.messageFocusNode,
            onClear: () => _service.messageController.clear(),
            onPlayPause: _service.togglePlayPause,
            isPlaying: _service.isPlaying,
          ),
        ],
      ),
    );
  }

  List<Widget> _buildCupertinoAppBarActions() {
    return [
      if (!_isSubscribed && _isMobilePlatform())
        CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: () => _subscriptionManager.showSubscriptionDialog(context),
          child: const Icon(CupertinoIcons.lock),
        ),
      CupertinoButton(
        padding: EdgeInsets.zero,
        onPressed: () {
          Navigator.push(
            context,
            CupertinoPageRoute(
              builder: (context) => FetchVoicesPage(
                endpoint: widget.speechServiceEndpoint,
                subscriptionKey: widget.speechServiceKey,
              ),
            ),
          );
        },
        child: const Icon(CupertinoIcons.settings),
      ),
      CupertinoButton(
        padding: EdgeInsets.zero,
        onPressed: () => showSaveMessageDialog(
            context, _service.messageController.text, _service.handleSaveMessage),
        child: const Icon(CupertinoIcons.add),
      ),
      CupertinoButton(
        padding: EdgeInsets.zero,
        onPressed: () => showFullScreenText(context, _service.messageController.text),
        child: const Icon(CupertinoIcons.fullscreen),
      ),
    ];
  }

  List<Widget> _buildMaterialAppBarActions() {
    return [
      if (!_isSubscribed && _isMobilePlatform())
        IconButton(
          icon: const Icon(Icons.lock),
          onPressed: () => _subscriptionManager.showSubscriptionDialog(context),
        ),
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
        onPressed: () => showSaveMessageDialog(
            context, _service.messageController.text, _service.handleSaveMessage),
      ),
      IconButton(
        icon: const Icon(Icons.fullscreen),
        onPressed: () => showFullScreenText(context, _service.messageController.text),
      ),
    ];
  }

  void _handleLeadingIconPressed() {
    if (_service.isSomeFolderSelected) {
      if (_service.currentFolderId == -1) {
        _service.selectRootFolder();
      } else {
        _service.speechItemDao.getItemById(_service.currentFolderId).then((currentFolder) {
          if (currentFolder != null && currentFolder.parentId != null) {
            _service.selectFolder(currentFolder.parentId!, currentFolder.name!);
          } else {
            _service.selectRootFolder();
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
  }

  Widget _buildCupertinoReorderableListView() {
    return ReorderableListView.builder(
      onReorder: _service.reorderItems,
      itemBuilder: (context, index) => Dismissible(
        key: ValueKey(_service.items[index]),
        direction: DismissDirection.horizontal,
        background: _buildDismissibleBackground(
            Alignment.centerLeft, Colors.blue, CupertinoIcons.share),
        secondaryBackground: _buildDismissibleBackground(
            Alignment.centerRight, Colors.red, CupertinoIcons.delete),
        confirmDismiss: (direction) => _handleDismiss(direction, _service.items[index]),
        child: SpeechItemListTile(
          item: _service.items[index],
          isCupertino: true,
          onTap: () => _handleItemTap(_service.items[index]),
        ),
      ),
      itemCount: _service.items.length,
    );
  }

  Widget _buildReorderableListView() {
    return ReorderableListView(
      onReorder: _service.reorderItems,
      children: _service.items.map((item) => Dismissible(
        key: ValueKey(item),
        direction: DismissDirection.horizontal,
        background: _buildDismissibleBackground(
            Alignment.centerLeft, Colors.blue, Icons.share),
        secondaryBackground: _buildDismissibleBackground(
            Alignment.centerRight, Colors.red, Icons.delete),
        confirmDismiss: (direction) => _handleDismiss(direction, item),
        child: SpeechItemListTile(
          item: item,
          onTap: () => _handleItemTap(item),
        ),
      )).toList(),
    );
  }

  Container _buildDismissibleBackground(
      Alignment alignment, Color color, IconData icon) {
    return Container(
      alignment: alignment,
      padding: const EdgeInsets.symmetric(horizontal: 40),
      color: color,
      child: Icon(icon, color: Colors.white),
    );
  }

  Future<bool> _handleDismiss(DismissDirection direction, dynamic item) async {
    if (direction == DismissDirection.startToEnd) {
      if (item is SpeechItem && item.filePath != null) {
        await _shareFile(item.filePath!);
      }
      return false;
    } else {
      final index = _service.items.indexOf(item);
      return await _service.deleteItem(index);
    }
  }

  Future<void> _shareFile(String filePath) async {
    try {
      await Share.shareXFiles([XFile(filePath)]);
    } catch (e) {
      final file = File(filePath);
      final bytes = await file.readAsBytes();
      await Clipboard.setData(ClipboardData(text: base64Encode(bytes)));
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('File copied to clipboard')),
      );
    }
  }

  void _handleItemTap(dynamic item) {
    if (item is String && item == 'History') {
      Navigator.push(
        context,
        Platform.isIOS
            ? CupertinoPageRoute(builder: (context) => HistoryPage())
            : MaterialPageRoute(builder: (context) => HistoryPage()),
      );
    } else if (item is SpeechItem) {
      if (item.isFolder!) {
        _service.selectFolder(item.id!, item.name!);
      } else {
        _service.playSpeechItem(item);
      }
    }
  }
}

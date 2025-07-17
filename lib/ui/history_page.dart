import 'package:flutter/material.dart';
import 'package:wingmate/utils/said_text_dao.dart';
import 'package:wingmate/utils/app_database.dart';
import 'package:wingmate/utils/said_text_item.dart';
import 'package:audioplayers/audioplayers.dart';
import 'dart:io';
import 'package:share_plus/share_plus.dart';
import 'package:flutter/services.dart';
import 'dart:convert';
import 'package:flutter/foundation.dart' show kIsWeb;

class HistoryPage extends StatefulWidget {
  @override
  _HistoryPageState createState() => _HistoryPageState();
}

class _HistoryPageState extends State<HistoryPage> {
  final SaidTextDao _saidTextDao = SaidTextDao(AppDatabase());
  final List<SaidTextItem> _saidTextItems = [];
  final player = AudioPlayer();

  @override
  void initState() {
    super.initState();
    _loadSaidTextItems();
  }

  Future<void> _loadSaidTextItems() async {
    final items = await _saidTextDao.getAll();
    setState(() {
      _saidTextItems.clear();
      _saidTextItems.addAll(items);
      _saidTextItems.sort((a, b) => (b.date ?? 0).compareTo(a.date ?? 0));
    });
  }

  Future<bool> _deleteSaidTextItem(int index) async {
    final item = _saidTextItems[index];
    final result = await _saidTextDao.delete(item.id!);
    if (result > 0) {
      if (item.audioFilePath != null) {
        final file = File(item.audioFilePath!);
        if (await file.exists()) {
          await file.delete();
        }
      }
      setState(() {
        _saidTextItems.removeAt(index);
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
      // Use SnackBar for all platforms when share fails
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('File copied to clipboard')),
      );
    }
  }

  String convertToUserFriendlyTags(String text) {
    final newText = text
        .replaceAll('<lang xml:lang="en-US">', '<en>')
        .replaceAll('</lang>', '</en>')
        .replaceAll('<break time="2s"/>', "<2s>");
    return newText;
  }

  @override
  Widget build(BuildContext context) {
    // Only one Scaffold is needed for all platforms
    return Scaffold(
      appBar: AppBar(
        title: const Text('History'),
      ),
      body: _buildBodyContent(),
    );
  }

  Widget _buildBodyContent() {
    return ListView.builder(
      itemCount: _saidTextItems.length,
      itemBuilder: (context, index) {
        final item = _saidTextItems[index];
        final dateString = DateTime.fromMillisecondsSinceEpoch(item.date ?? 0)
            .toLocal()
            .toString()
            .substring(0, 16); // e.g. "YYYY-MM-DD HH:MM"

        // Use Dismissible for all platforms
        return Dismissible(
          key: ValueKey(item.id!),
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
              if (item.audioFilePath != null) {
                await _shareFile(item.audioFilePath!);
              }
              return false;
            } else {
              return await _deleteSaidTextItem(index);
            }
          },
          child: ListTile(
            key: ValueKey(item),
            leading: const Icon(Icons.speaker_phone),
            title: Text(convertToUserFriendlyTags(item.saidText ?? '')),
            subtitle: Text(dateString),
            onTap: item.audioFilePath != null
                ? () async {
                    await player.play(DeviceFileSource(item.audioFilePath!));
                  }
                : null,
          ),
        );
      },
    );
  }
}
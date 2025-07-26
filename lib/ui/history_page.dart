import 'dart:io';
import 'package:flutter/material.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:hive/hive.dart';
import 'package:intl/intl.dart';
import 'package:wingmate/services/tts/azure_text_to_speech.dart';
import 'package:wingmate/data/app_database.dart';
import 'package:wingmate/data/said_text_dao.dart';
import 'package:wingmate/data/said_text_item.dart';
import 'package:wingmate/config/speech_service_config.dart';

class HistoryPage extends StatefulWidget {
  const HistoryPage({Key? key}) : super(key: key);

  @override
  _HistoryPageState createState() => _HistoryPageState();
}

class _HistoryPageState extends State<HistoryPage> {
  final SaidTextDao _saidTextDao = SaidTextDao(AppDatabase());
  late final AzureTts _azureTts;
  List<SaidTextItem> _historyItems = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _initializeAndLoad();
  }

  Future<void> _initializeAndLoad() async {
    // This assumes boxes are already opened in main.dart
    final settingsBox = Hive.box('settings');
    final voiceBox = Hive.box('selectedVoice');
    final config = settingsBox.get('config') as SpeechServiceConfig?;

    _azureTts = AzureTts(
      subscriptionKey: config?.key ?? '',
      region: config?.endpoint ?? '',
      settingsBox: settingsBox,
      voiceBox: voiceBox,
      context: context,
      saidTextDao: _saidTextDao,
    );
    
    await _loadHistory();
  }

  Future<void> _loadHistory() async {
    if (!mounted) return;
    setState(() => _isLoading = true);
    final items = await _saidTextDao.getAll();
    // Sort by date descending to show most recent first
    items.sort((a, b) => (b.date ?? 0).compareTo(a.date ?? 0));
    if (mounted) {
      setState(() {
        _historyItems = items;
        _isLoading = false;
      });
    }
  }

  Future<void> _deleteHistoryItem(int index) async {
    final item = _historyItems[index];
    if (item.id != null) {
      await _saidTextDao.delete(item.id!);
      if (item.audioFilePath != null) {
        final file = File(item.audioFilePath!);
        if (await file.exists()) {
          try {
            await file.delete();
          } catch (e) {
            debugPrint("Error deleting file: $e");
          }
        }
      }
      // The widget is removed by Dismissible, we just update our data source
      _historyItems.removeAt(index);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('History'),
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _historyItems.isEmpty
              ? const Center(child: Text('No history yet.'))
              : ListView.builder(
                  itemCount: _historyItems.length,
                  itemBuilder: (context, index) {
                    final item = _historyItems[index];
                    return Dismissible(
                      key: ValueKey(item.id),
                      direction: DismissDirection.endToStart,
                      onDismissed: (direction) {
                        _deleteHistoryItem(index);
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text('"${item.saidText}" deleted.')),
                        );
                      },
                      background: Container(
                        color: Colors.red,
                        alignment: Alignment.centerRight,
                        padding: const EdgeInsets.symmetric(horizontal: 20),
                        child: const Icon(Icons.delete, color: Colors.white),
                      ),
                      child: ListTile(
                        title: Text(item.saidText ?? 'No text'),
                        subtitle: Text(
                          DateFormat.yMMMd().add_jm().format(
                                DateTime.fromMillisecondsSinceEpoch(item.date ?? 0),
                              ),
                        ),
                        trailing: IconButton(
                          icon: const Icon(Icons.play_arrow),
                          onPressed: () {
                            if (item.audioFilePath != null && File(item.audioFilePath!).existsSync()) {
                              _azureTts.play(DeviceFileSource(item.audioFilePath!));
                            } else {
                              ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(content: Text('Audio file not found.')),
                              );
                            }
                          },
                        ),
                      ),
                    );
                  },
                ),
    );
  }
}
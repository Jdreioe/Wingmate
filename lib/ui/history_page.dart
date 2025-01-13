import 'package:flutter/material.dart';
import 'package:wingmate/utils/said_text_dao.dart';
import 'package:wingmate/utils/app_database.dart';
import 'package:wingmate/utils/said_text_item.dart';
import 'package:audioplayers/audioplayers.dart';

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
    });
  }

  Future<bool> _deleteSaidTextItem(int index) async {
    final result = await _saidTextDao.delete(_saidTextItems[index].id!);
    if (result > 0) {
      setState(() {
        _saidTextItems.removeAt(index);
      });
      return true;
    }
    return false;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('History'),
      ),
      body: ListView.builder(
        itemCount: _saidTextItems.length,
        itemBuilder: (context, index) {
          final item = _saidTextItems[index];
          final dateString =
              DateTime.fromMillisecondsSinceEpoch(item.date ?? 0)
                  .toLocal()
                  .toString()
                  .substring(0, 16); // e.g. "YYYY-MM-DD HH:MM"
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
              leading: Icon(Icons.speaker_phone),
              title: Text(item.saidText ?? ''),
              subtitle: Text(dateString),
              onTap: item.audioFilePath != null
                  ? () async {
                      await player.play(DeviceFileSource(item.audioFilePath!));
                    }
                  : null,
            ),
          );
        },
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:wingmate/utils/speech_item_dao.dart';
import 'package:wingmate/utils/app_database.dart';
import 'package:wingmate/utils/speech_item.dart';
import 'package:audioplayers/audioplayers.dart';

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

  @override
  void initState() {
    super.initState();
    _loadSpeechItems();
  }

  Future<void> _loadSpeechItems() async {
    final items = await _speechItemDao.getAllItemsInFolder(widget.folderId);
    setState(() {
      _speechItems.clear();
      _speechItems.addAll(items);
    });
  }

  Future<bool> _deleteSpeechItem(int index) async {
    final result = await _speechItemDao.deleteItem(_speechItems[index].id!);
    if (result > 0) {
      setState(() {
        _speechItems.removeAt(index);
      });
      return true;
    }
    return false;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('Folder'),
      ),
      body: ListView.builder(
        itemCount: _speechItems.length,
        itemBuilder: (context, index) {
          final item = _speechItems[index];
          return Dismissible(
            key: ValueKey(item.id),
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
              return await _deleteSpeechItem(index);
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
                  : null,
            ),
          );
        },
      ),
    );
  }
}

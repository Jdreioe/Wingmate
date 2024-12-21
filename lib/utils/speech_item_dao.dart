import 'package:sqflite/sqflite.dart';
import 'app_database.dart'; // Import your database helper
import 'speech_item.dart'; // Import your model

class SpeechItemDao {
  final AppDatabase _database;

  SpeechItemDao(this._database);

  Future<List<SpeechItem>> getAllItemsInFolder(int folderId) async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db
        .query('SpeechItem', where: 'parentId = ?', whereArgs: [folderId]);
    return List.generate(maps.length, (i) {
      return SpeechItem.fromMap(maps[i]);
    });
  }

  Future<List<SpeechItem>> getAllRootItems() async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps =
        await db.query('SpeechItem', where: 'parentId IS NULL');
    return List.generate(maps.length, (i) {
      return SpeechItem.fromMap(maps[i]);
    });
  }

  Future<List<SpeechItem>> getAll() async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query('SpeechItem');
    return List.generate(maps.length, (i) {
      return SpeechItem.fromMap(maps[i]);
    });
  }

  Future<int> insertItem(SpeechItem speechItem) async {
    final db = await _database.database;
    return await db.insert('SpeechItem', speechItem.toMap());
  }
  // Add other methods as needed (insert, update, delete, etc.)
}

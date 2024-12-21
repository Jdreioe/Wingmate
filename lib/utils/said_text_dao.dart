import 'package:sqflite/sqflite.dart';
import 'app_database.dart';
import 'said_text_item.dart';

class SaidTextDao {
  final AppDatabase _database;

  SaidTextDao(this._database);
  Future<SaidTextItem?> getByText(String text) async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query('SaidTextItem',
        where: 'saidText = ?', whereArgs: [text], limit: 1);
    if (maps.isNotEmpty) {
      return SaidTextItem.fromMap(maps.first);
    }
    return null;
  }

  Future<int> insertHistorik(SaidTextItem saidTextItem) async {
    final db = await _database.database;
    return await db.insert('SaidTextItem', saidTextItem.toMap());
  }

  Future<List<SaidTextItem>> getAll() async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query('SaidTextItem');
    return List.generate(maps.length, (i) {
      return SaidTextItem.fromMap(maps[i]);
    });
  }
  // ... other methods
}

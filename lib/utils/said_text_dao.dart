import 'dart:async';

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

  Future<int> delete(int id) async {
    final db = await _database.database;
    return await db.delete(
      'SaidTextItem',
      where: 'id = ?',
      whereArgs: [id],
    );
  }

  Future<int> updateItem(SaidTextItem item) async {
    final db = await _database.database;
    return await db.update(
      'SaidTextItem',
      item.toMap(),
      where: 'id = ?',
      whereArgs: [item.id],
    );
  }

  Future<List<SaidTextItem>> getAll() async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query(
      'SaidTextItem',
      orderBy: 'position ASC', // sort by position
    );
    return List.generate(maps.length, (i) {
      return SaidTextItem.fromMap(maps[i]);
    });
  }
    Future<List<SaidTextItem>> getFilteredItems(String selectedLanguage, double speed, String selectedVoice, double pitch) async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query(
      'SaidTextItem',
      where: 'voiceName = ? AND speed = ? AND pitch = ?',
      whereArgs: [selectedVoice, speed, pitch],
      orderBy: 'position ASC', // sort by position
    );
    return List.generate(maps.length, (i) {
      return SaidTextItem.fromMap(maps[i]);
    });
  }
  Future<SaidTextItem?> getItemByText(String text) async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query(
      'SaidTextItem',
      where: 'saidText = ?',
      whereArgs: [text],
      limit: 1,
    );
    if (maps.isNotEmpty) {
      return SaidTextItem.fromMap(maps.first);
    }
    return null;
  }

  Future<List<String>> getAllSaidTexts() async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query(
      'SaidTextItem',
      columns: ['saidText'],
      orderBy: 'position ASC', // sort by position
    );
    return maps.map((map) => map['saidText'] as String).toList();
  }

  // ... other methods
}

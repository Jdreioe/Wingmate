import 'package:sqflite/sqflite.dart';
import 'package:wingmate/data/app_database.dart';
import 'package:wingmate/data/ui_settings.dart';

class UiSettingsDao {
  final AppDatabase _appDatabase;

  UiSettingsDao(this._appDatabase);

  Future<void> insert(UiSettings settings) async {
    final db = await _appDatabase.database;
    final id = await db.insert(
      'ui_settings',
      settings.toMap(),
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
    settings.id = id;
  }

  Future<List<UiSettings>> getAll() async {
    final db = await _appDatabase.database;
    final List<Map<String, dynamic>> maps = await db.query('ui_settings');
    return List.generate(maps.length, (i) {
      return UiSettings.fromMap(maps[i]);
    });
  }

  Future<UiSettings?> getByName(String name) async {
    final db = await _appDatabase.database;
    final List<Map<String, dynamic>> maps = await db.query(
      'ui_settings',
      where: 'name = ?',
      whereArgs: [name],
    );
    if (maps.isNotEmpty) {
      return UiSettings.fromMap(maps.first);
    }
    return null;
  }

  Future<void> update(UiSettings settings) async {
    final db = await _appDatabase.database;
    await db.update(
      'ui_settings',
      settings.toMap(),
      where: 'id = ?',
      whereArgs: [settings.id],
    );
  }

  Future<void> delete(int id) async {
    final db = await _appDatabase.database;
    await db.delete(
      'ui_settings',
      where: 'id = ?',
      whereArgs: [id],
    );
  }

  Future<void> deleteByName(String name) async {
    final db = await _appDatabase.database;
    await db.delete(
      'ui_settings',
      where: 'name = ?',
      whereArgs: [name],
    );
  }
}
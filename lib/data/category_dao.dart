import 'package:sqflite/sqflite.dart';
import 'package:wingmate/data/app_database.dart';
import 'package:wingmate/data/category_item.dart';

class CategoryDao {
  final AppDatabase _database;

  CategoryDao(this._database);

  Future<int> insert(CategoryItem category) async {
    final db = await _database.database;
    return await db.insert('CategoryItem', category.toMap(),
        conflictAlgorithm: ConflictAlgorithm.replace);
  }

  Future<List<CategoryItem>> getAll() async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query('CategoryItem');
    return List.generate(maps.length, (i) {
      return CategoryItem.fromMap(maps[i]);
    });
  }

  Future<int> update(CategoryItem category) async {
    final db = await _database.database;
    return await db.update(
      'CategoryItem',
      category.toMap(),
      where: 'id = ?',
      whereArgs: [category.id],
    );
  }

  Future<int> delete(int id) async {
    final db = await _database.database;
    return await db.delete(
      'CategoryItem',
      where: 'id = ?',
      whereArgs: [id],
    );
  }

  Future<CategoryItem?> getById(int id) async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query(
      'CategoryItem',
      where: 'id = ?',
      whereArgs: [id],
    );
    if (maps.isNotEmpty) {
      return CategoryItem.fromMap(maps.first);
    }
    return null;
  }
}
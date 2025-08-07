
import 'package:sqflite/sqflite.dart';
import 'package:wingmate/infrastructure/data/app_database.dart';
import 'package:wingmate/domain/entities/category.dart';
import 'package:wingmate/infrastructure/models/category_item.dart';
import 'package:wingmate/domain/repositories/category_repository.dart';

class CategoryItemDao implements CategoryRepository {

  // For BLoC/UI use: returns infrastructure model
  Future<List<CategoryItem>> getCategoryItems() async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query('CategoryItem');
    return List.generate(maps.length, (i) {
      return CategoryItem.fromMap(maps[i]);
    });
  }
  final AppDatabase _database;

  CategoryItemDao(this._database);

  Future<List<Category>> getCategories() async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query('CategoryItem');
    return List.generate(maps.length, (i) {
      return CategoryItem.fromMap(maps[i]).toDomain();
    });
  }

  Future<Category?> getCategoryById(int id) async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query(
      'CategoryItem',
      where: 'id = ?',
      whereArgs: [id],
    );
    if (maps.isNotEmpty) {
      return CategoryItem.fromMap(maps.first).toDomain();
    }
    return null;
  }

  Future<void> saveCategory(Category category) async {
    final db = await _database.database;
    final categoryItem = CategoryItem.fromDomain(category);
    if (categoryItem.id == null) {
      await db.insert('CategoryItem', categoryItem.toMap());
    } else {
      await db.update(
        'CategoryItem',
        categoryItem.toMap(),
        where: 'id = ?',
        whereArgs: [categoryItem.id],
      );
    }
  }

  Future<void> deleteCategory(int id) async {
    final db = await _database.database;
    await db.delete(
      'CategoryItem',
      where: 'id = ?',
      whereArgs: [id],
    );
  }

  Future<void> addCategory(Category category) async {
    final db = await _database.database;
    final categoryItem = CategoryItem.fromDomain(category);
    await db.insert('CategoryItem', categoryItem.toMap());
  }
}

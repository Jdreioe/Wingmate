import 'app_database.dart'; // Import your database helper
import 'phrase_item.dart'; // Import your model

class PhraseItemDao {
  final AppDatabase _database;

  PhraseItemDao(this._database);

  Future<List<PhraseItem>> getAllItemsInCategory(int categoryId) async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db
        .query('PhraseItem', where: 'parentId = ?', whereArgs: [categoryId]);
    return List.generate(maps.length, (i) {
      return PhraseItem.fromMap(maps[i]);
    });
  }

  Future<List<PhraseItem>> getAllRootItems() async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps =
    await db.query('PhraseItem', where: 'parentId IS NULL');
    return List.generate(maps.length, (i) {
      return PhraseItem.fromMap(maps[i]);
    });
  }

  Future<List<PhraseItem>> getAll() async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query('PhraseItem');
    return List.generate(maps.length, (i) {
      return PhraseItem.fromMap(maps[i]);
    });
  }

  Future<List<PhraseItem>> getAllCategories() async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query(
      'PhraseItem',
      where: 'isCategory = ?',
      whereArgs: [1], // 1 for true
    );
    return List.generate(maps.length, (i) {
      return PhraseItem.fromMap(maps[i]);
    });
  }

  Future<int> insert(PhraseItem phraseItem) async {
    final db = await _database.database;
    return await db.insert('PhraseItem', phraseItem.toMap());
  }

  Future<int> deleteItem(int id) async {
    final db = await _database.database;
    return await db.delete('PhraseItem', where: 'id = ?', whereArgs: [id]);
  }

  Future<int> updateItem(PhraseItem phraseItem) async {
    final db = await _database.database;
    return await db.update(
      'PhraseItem',
      phraseItem.toMap(),
      where: 'id = ?',
      whereArgs: [phraseItem.id],
    );
  }

  Future<PhraseItem?> getPhraseItemById(int id) async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps =
    await db.query('PhraseItem', where: 'id = ?', whereArgs: [id]);
    if (maps.isNotEmpty) {
      return PhraseItem.fromMap(maps.first);
    }
    return null;
  }

  Future<PhraseItem?> getPhraseItemByText(String text) async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps =
    await db.query('PhraseItem', where: 'text = ?', whereArgs: [text]);
    if (maps.isNotEmpty) {
      return PhraseItem.fromMap(maps.first);
    }
    return null;
  }

  // New method to update positions of multiple items
  Future<void> updateItemPositions(List<PhraseItem> items) async {
    final db = await _database.database;
    final batch = db.batch();
    for (final item in items) {
      batch.update(
        'PhraseItem',
        {'position': item.position},
        where: 'id = ?',
        whereArgs: [item.id],
      );
    }
    await batch.commit(noResult: true);
  }


}


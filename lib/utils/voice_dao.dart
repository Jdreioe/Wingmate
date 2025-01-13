import 'app_database.dart';
import 'voice_item.dart';

class VoiceDao {
  final AppDatabase _database;

  VoiceDao(this._database);

  Future<List<VoiceItem>> getAllVoices(int expirationTime) async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query('VoiceItem',
        where: 'createdAt >= ?', whereArgs: [expirationTime]);
    return List.generate(maps.length, (i) {
      return VoiceItem.fromMap(maps[i]);
    });
  }

  Future<int> insert(VoiceItem voiceItem) async {
    final db = await _database.database;
    return await db.insert('VoiceItem', voiceItem.toMap());
  }

  Future<void> deleteAll() async {
    final db = await _database.database;
    await db.delete('VoiceItem');
  }
  Future<void> deleteVoice(int id) async {
    final db = await _database.database;
    await db.delete('VoiceItem', where: 'id = ?', whereArgs: [id]);
  }
  // ... other methods
}

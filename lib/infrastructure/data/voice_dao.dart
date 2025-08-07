import 'package:hive/hive.dart';
import 'package:wingmate/infrastructure/data/app_database.dart';
import 'package:wingmate/infrastructure/models/voice_item.dart';
import 'package:wingmate/domain/models/voice_model.dart' as hive_model;
import 'package:wingmate/domain/entities/voice.dart';

import 'package:wingmate/domain/repositories/voice_repository.dart';

class VoiceDao implements VoiceRepository {
  final AppDatabase _database;

  VoiceDao(this._database);

  Future<List<Voice>> getVoices() async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query('VoiceItem');
    return List.generate(maps.length, (i) {
      return VoiceItem.fromMap(maps[i]).toDomain();
    });
  }

  Future<Voice?> getVoiceById(int id) async {
    final db = await _database.database;
    final List<Map<String, dynamic>> maps = await db.query(
      'VoiceItem',
      where: 'id = ?',
      whereArgs: [id],
    );
    if (maps.isNotEmpty) {
      return VoiceItem.fromMap(maps.first).toDomain();
    }
    return null;
  }

  Future<void> deleteAll() async {
    final db = await _database.database;
    await db.delete('VoiceItem');
  }
  Future<void> deleteVoice(int id) async {
    final db = await _database.database;
    await db.delete('VoiceItem', where: 'id = ?', whereArgs: [id]);
  }

  @override
  Future<void> saveVoice(Voice voice) async {
    final db = await _database.database;
    final voiceItem = VoiceItem.fromDomain(voice);
    if (voiceItem.id == null) {
      await db.insert('VoiceItem', voiceItem.toMap());
    } else {
      await db.update(
        'VoiceItem',
        voiceItem.toMap(),
        where: 'id = ?',
        whereArgs: [voiceItem.id],
      );
    }
  }

  @override
  Future<Voice?> getSelectedVoice() async {
    final box = await Hive.openBox('selectedVoice');
    final hiveVoice = box.get('currentVoice') as hive_model.Voice?;
    return hiveVoice?.toDomain();
  }

  @override
  Future<void> saveSelectedVoice(Voice voice) async {
    final box = await Hive.openBox('selectedVoice');
    // Convert domain Voice to Hive Voice model
    final hiveVoice = hive_model.Voice(
      name: voice.name ?? '',
      supportedLanguages: voice.supportedLanguages ?? [],
      primaryLanguage: voice.primaryLanguage ?? '',
      pitch: voice.pitch ?? 0.0,
      rate: voice.rate ?? 1.0,
      pitchForSSML: voice.pitchForSSML ?? '',
      rateForSSML: voice.rateForSSML ?? '',
    );
    await box.put('currentVoice', hiveVoice);
  }
}
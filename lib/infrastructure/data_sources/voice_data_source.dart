import 'package:wingmate/domain/entities/voice.dart';
import 'package:wingmate/infrastructure/data/voice_dao.dart';
import 'package:wingmate/infrastructure/models/voice_item.dart';

abstract class VoiceDataSource {
  Future<List<Voice>> getVoices();
  Future<Voice?> getVoiceById(int id);
  Future<void> saveVoice(Voice voice);
  Future<void> deleteVoice(int id);
}

class VoiceLocalDataSource implements VoiceDataSource {
  final VoiceDao voiceDao;

  VoiceLocalDataSource(this.voiceDao);

  @override
  Future<List<Voice>> getVoices() async {
    // Assuming a long expiration time to get all voices, or adjust as needed
    final voiceItems = await voiceDao.getAllVoices(0);
    return voiceItems.map((item) => item.toDomain()).toList();
  }

  @override
  Future<Voice?> getVoiceById(int id) async {
    // VoiceDao does not have a direct getById, so we'll filter from all voices
    final voiceItems = await voiceDao.getAllVoices(0);
    final voiceItem = voiceItems.firstWhere((item) => item.id == id, orElse: () => null as VoiceItem);
    return voiceItem?.toDomain();
  }

  @override
  Future<void> saveVoice(Voice voice) async {
    final voiceItem = VoiceItem.fromDomain(voice);
    // VoiceDao only has insert, not update. Assuming new voices are always inserted.
    await voiceDao.insert(voiceItem);
  }

  @override
  Future<void> deleteVoice(int id) async {
    await voiceDao.deleteVoice(id);
  }
}

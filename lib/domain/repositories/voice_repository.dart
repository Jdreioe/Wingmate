import 'package:wingmate/domain/entities/voice.dart';

abstract class VoiceRepository {
  Future<List<Voice>> getVoices();
  Future<Voice?> getVoiceById(int id);
  Future<void> saveVoice(Voice voice);
  Future<void> deleteVoice(int id);
  Future<Voice?> getSelectedVoice();
  Future<void> saveSelectedVoice(Voice voice);
}

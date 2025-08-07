import 'package:wingmate/domain/entities/voice.dart' as domain_models;

abstract class SpeechService {
  Future<void> speak(String text, {domain_models.Voice? voice, double? pitch, double? rate});
  Future<List<domain_models.Voice>> getAvailableVoices();
  Future<void> stopSpeaking();
}

import 'package:wingmate/domain/models/voice_model.dart';
import 'package:wingmate/domain/services/speech_service.dart';
import 'package:wingmate/infrastructure/services/tts/azure_text_to_speech.dart';

class AzureSpeechServiceImpl implements SpeechService {
  final AzureTts _azureTts;

  AzureSpeechServiceImpl(this._azureTts);

  @override
  Future<void> speakText(String text) {
    return _azureTts.speakText(text);
  }

  @override
  Future<void> pause() {
    return _azureTts.pause();
  }

  @override
  Future<void> stop() {
    return _azureTts.player.stop();
  }

  @override
  Future<String?> generateAndCacheAudioForItem(item) {
    return _azureTts.generateAndCacheAudioForItem(item);
  }

  @override
  Future<List<Voice>> getVoices() {
    return _azureTts.getVoices();
  }

  @override
  Future<List<Voice>> getAvailableVoices() {
    return _azureTts.getAvailableVoices();
  }

  @override
  Future<void> speak(String text, {Voice? voice, double? pitch, double? rate}) {
    return _azureTts.speak(text, voice: voice, pitch: pitch, rate: rate);
  }

}
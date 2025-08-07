
import 'package:wingmate/domain/entities/phrase.dart';
import 'package:wingmate/domain/entities/voice.dart';

abstract class SpeechServiceRepository {
  Future<void> speakText(String text);
  Future<void> testVoice(String text, Voice voice);
  Future<String?> generateAndCacheAudioForItem(Phrase phrase);
  Future<void> pauseSpeech();
}

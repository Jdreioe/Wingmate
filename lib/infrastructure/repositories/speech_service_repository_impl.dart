
import 'package:wingmate/domain/entities/phrase.dart';
import 'package:wingmate/domain/entities/voice.dart';
import 'package:wingmate/domain/repositories/speech_service_repository.dart';
import 'package:wingmate/infrastructure/models/phrase_item.dart';
import 'package:wingmate/infrastructure/models/voice_item.dart';
import 'package:wingmate/infrastructure/services/tts/azure_text_to_speech.dart';

class SpeechServiceRepositoryImpl implements SpeechServiceRepository {
  final AzureTts azureTts;

  SpeechServiceRepositoryImpl(this.azureTts);

  @override
  Future<String?> generateAndCacheAudioForItem(Phrase phrase) async {
    return await azureTts.generateAndCacheAudioForItem(PhraseItem.fromDomain(phrase));
  }

  @override
  Future<void> pauseSpeech() async {
    await azureTts.pause();
  }

  @override
  Future<void> speakText(String text) async {
    await azureTts.speakText(text);
  }

  @override
  Future<void> testVoice(String text, Voice voice) async {
    await azureTts.testVoice(text, VoiceItem.fromDomain(voice));
  }
}

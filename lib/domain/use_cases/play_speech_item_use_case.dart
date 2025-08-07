import 'package:wingmate/domain/services/speech_service.dart';
import 'package:wingmate/infrastructure/models/phrase_item.dart';

class PlaySpeechItemUseCase {
  final SpeechService speechService;

  PlaySpeechItemUseCase({
    required this.speechService,
  });

  Future<void> execute(PhraseItem item) async {
    if (item.text != null) {
      await speechService.speak(item.text!);
    }
  }
}

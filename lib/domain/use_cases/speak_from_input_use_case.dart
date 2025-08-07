import 'package:wingmate/domain/services/speech_service.dart';

class SpeakFromInputUseCase {
  final SpeechService speechService;

  SpeakFromInputUseCase({
    required this.speechService,
  });

  Future<void> execute(String text) async {
    if (text.trim().isNotEmpty) {
      await speechService.speak(text);
    }
  }
}

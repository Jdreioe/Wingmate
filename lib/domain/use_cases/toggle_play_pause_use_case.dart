import 'package:wingmate/domain/services/speech_service.dart';

class TogglePlayPauseUseCase {
  final SpeechService speechService;

  TogglePlayPauseUseCase({
    required this.speechService,
  });

  Future<void> execute(bool isPlaying, String text) async {
    if (text.isNotEmpty) {
      if (!isPlaying) {
        await speechService.speak(text);
      } else {
        await speechService.stopSpeaking();
      }
    }
  }
}

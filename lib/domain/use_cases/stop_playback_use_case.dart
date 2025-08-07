import 'package:wingmate/domain/services/speech_service.dart';

class StopPlaybackUseCase {
  final SpeechService speechService;

  StopPlaybackUseCase({
    required this.speechService,
  });

  Future<void> execute() async {
    await speechService.stopSpeaking();
  }
}

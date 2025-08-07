import 'package:wingmate/core/usecase/usecase.dart';
import 'package:wingmate/domain/entities/voice.dart';
import 'package:wingmate/domain/repositories/voice_repository.dart';

class GetVoicesUseCase implements UseCase<List<Voice>, NoParams> {
  final VoiceRepository repository;

  GetVoicesUseCase(this.repository);

  @override
  Future<List<Voice>> call(NoParams params) async {
    return await repository.getVoices();
  }
}

class GetVoiceByIdUseCase implements UseCase<Voice?, int> {
  final VoiceRepository repository;

  GetVoiceByIdUseCase(this.repository);

  @override
  Future<Voice?> call(int id) async {
    return await repository.getVoiceById(id);
  }
}

class SaveVoiceUseCase implements UseCase<void, Voice> {
  final VoiceRepository repository;

  SaveVoiceUseCase(this.repository);

  @override
  Future<void> call(Voice voice) async {
    return await repository.saveVoice(voice);
  }
}

class DeleteVoiceUseCase implements UseCase<void, int> {
  final VoiceRepository repository;

  DeleteVoiceUseCase(this.repository);

  @override
  Future<void> call(int id) async {
    return await repository.deleteVoice(id);
  }
}

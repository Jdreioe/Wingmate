import 'package:wingmate/domain/entities/voice.dart';
import 'package:wingmate/domain/repositories/voice_repository.dart';
import 'package:wingmate/infrastructure/data_sources/voice_data_source.dart';

class VoiceRepositoryImpl implements VoiceRepository {
  final VoiceLocalDataSource localDataSource;

  VoiceRepositoryImpl(this.localDataSource);

  @override
  Future<List<Voice>> getVoices() async {
    return await localDataSource.getVoices();
  }

  @override
  Future<Voice?> getVoiceById(int id) async {
    return await localDataSource.getVoiceById(id);
  }

  @override
  Future<void> saveVoice(Voice voice) async {
    return await localDataSource.saveVoice(voice);
  }

  @override
  Future<void> deleteVoice(int id) async {
    return await localDataSource.deleteVoice(id);
  }
}
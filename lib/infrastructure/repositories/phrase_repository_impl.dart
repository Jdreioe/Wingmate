import 'package:wingmate/domain/entities/phrase.dart';
import 'package:wingmate/domain/repositories/phrase_repository.dart';
import 'package:wingmate/infrastructure/data_sources/phrase_data_source.dart';

class PhraseRepositoryImpl implements PhraseRepository {
  final PhraseLocalDataSource localDataSource;

  PhraseRepositoryImpl(this.localDataSource);

  @override
  Future<List<Phrase>> getPhrases() async {
    return await localDataSource.getPhrases();
  }

  @override
  Future<Phrase?> getPhraseById(int id) async {
    return await localDataSource.getPhraseById(id);
  }

  @override
  Future<void> savePhrase(Phrase phrase) async {
    return await localDataSource.savePhrase(phrase);
  }

  @override
  Future<void> deletePhrase(int id) async {
    return await localDataSource.deletePhrase(id);
  }
}
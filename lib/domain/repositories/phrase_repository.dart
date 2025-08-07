import 'package:wingmate/domain/entities/phrase.dart';

abstract class PhraseRepository {
  Future<List<Phrase>> getPhrases();
  Future<Phrase?> getPhraseById(int id);
  Future<void> savePhrase(Phrase phrase);
  Future<void> deletePhrase(int id);
}
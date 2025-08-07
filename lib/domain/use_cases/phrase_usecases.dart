import 'package:wingmate/core/usecase/usecase.dart';
import 'package:wingmate/domain/entities/phrase.dart';
import 'package:wingmate/domain/repositories/phrase_repository.dart';

class GetPhrasesUseCase implements UseCase<List<Phrase>, NoParams> {
  final PhraseRepository repository;

  GetPhrasesUseCase(this.repository);

  @override
  Future<List<Phrase>> call(NoParams params) async {
    return await repository.getPhrases();
  }
}

class GetPhraseByIdUseCase implements UseCase<Phrase?, int> {
  final PhraseRepository repository;

  GetPhraseByIdUseCase(this.repository);

  @override
  Future<Phrase?> call(int id) async {
    return await repository.getPhraseById(id);
  }
}

class SavePhraseUseCase implements UseCase<void, Phrase> {
  final PhraseRepository repository;

  SavePhraseUseCase(this.repository);

  @override
  Future<void> call(Phrase phrase) async {
    return await repository.savePhrase(phrase);
  }
}

class DeletePhraseUseCase implements UseCase<void, int> {
  final PhraseRepository repository;

  DeletePhraseUseCase(this.repository);

  @override
  Future<void> call(int id) async {
    return await repository.deletePhrase(id);
  }
}

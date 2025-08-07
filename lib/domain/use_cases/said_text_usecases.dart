import 'package:wingmate/core/usecase/usecase.dart';
import 'package:wingmate/domain/entities/said_text.dart';
import 'package:wingmate/domain/repositories/said_text_repository.dart';

class GetSaidTextsUseCase implements UseCase<List<SaidText>, NoParams> {
  final SaidTextRepository repository;

  GetSaidTextsUseCase(this.repository);

  @override
  Future<List<SaidText>> call(NoParams params) async {
    return await repository.getSaidTexts();
  }
}

class GetSaidTextByIdUseCase implements UseCase<SaidText?, int> {
  final SaidTextRepository repository;

  GetSaidTextByIdUseCase(this.repository);

  @override
  Future<SaidText?> call(int id) async {
    return await repository.getSaidTextById(id);
  }
}

class SaveSaidTextUseCase implements UseCase<void, SaidText> {
  final SaidTextRepository repository;

  SaveSaidTextUseCase(this.repository);

  @override
  Future<void> call(SaidText saidText) async {
    return await repository.saveSaidText(saidText);
  }
}

class DeleteSaidTextUseCase implements UseCase<void, int> {
  final SaidTextRepository repository;

  DeleteSaidTextUseCase(this.repository);

  @override
  Future<void> call(int id) async {
    return await repository.deleteSaidText(id);
  }
}

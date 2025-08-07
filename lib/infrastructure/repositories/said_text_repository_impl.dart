import 'package:wingmate/domain/entities/said_text.dart';
import 'package:wingmate/domain/repositories/said_text_repository.dart';
import 'package:wingmate/infrastructure/data_sources/said_text_data_source.dart';

class SaidTextRepositoryImpl implements SaidTextRepository {
  final SaidTextLocalDataSource localDataSource;

  SaidTextRepositoryImpl(this.localDataSource);

  @override
  Future<List<SaidText>> getSaidTexts() async {
    return await localDataSource.getSaidTexts();
  }

  @override
  Future<SaidText?> getSaidTextById(int id) async {
    return await localDataSource.getSaidTextById(id);
  }

  @override
  Future<void> saveSaidText(SaidText saidText) async {
    return await localDataSource.saveSaidText(saidText);
  }

  @override
  Future<void> deleteSaidText(int id) async {
    return await localDataSource.deleteSaidText(id);
  }
}

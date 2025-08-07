import 'package:wingmate/domain/entities/said_text.dart';

abstract class SaidTextRepository {
  Future<List<SaidText>> getSaidTexts();
  Future<SaidText?> getSaidTextById(int id);
  Future<void> saveSaidText(SaidText saidText);
  Future<void> deleteSaidText(int id);
}

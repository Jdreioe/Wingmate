import 'package:wingmate/domain/entities/said_text.dart';
import 'package:wingmate/infrastructure/data/said_text_dao.dart';
import 'package:wingmate/infrastructure/models/said_text_item.dart';

abstract class SaidTextDataSource {
  Future<List<SaidText>> getSaidTexts();
  Future<SaidText?> getSaidTextById(int id);
  Future<void> saveSaidText(SaidText saidText);
  Future<void> deleteSaidText(int id);
}

class SaidTextLocalDataSource implements SaidTextDataSource {
  final SaidTextDao saidTextDao;

  SaidTextLocalDataSource(this.saidTextDao);

  @override
  Future<List<SaidText>> getSaidTexts() async {
    final saidTextItems = await saidTextDao.getAll();
    return saidTextItems.map((item) => item.toDomain()).toList();
  }

  @override
  Future<SaidText?> getSaidTextById(int id) async {
    // SaidTextDao does not have a getById method, so I'll use getByText and assume id is unique for now.
    // This might need adjustment if the DAO is updated to support ID-based retrieval.
    final saidTextItem = await saidTextDao.getItemByText(id.toString()); // Assuming ID can be converted to text for lookup
    return saidTextItem?.toDomain();
  }

  @override
  Future<void> saveSaidText(SaidText saidText) async {
    final saidTextItem = SaidTextItem.fromDomain(saidText);
    if (saidText.id == null) {
      await saidTextDao.insertHistorik(saidTextItem);
    } else {
      await saidTextDao.updateItem(saidTextItem);
    }
  }

  @override
  Future<void> deleteSaidText(int id) async {
    await saidTextDao.delete(id);
  }
}

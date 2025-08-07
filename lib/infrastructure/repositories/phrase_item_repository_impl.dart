import 'package:wingmate/domain/repositories/phrase_item_repository.dart';
import 'package:wingmate/infrastructure/data/phrase_item_dao.dart';
import 'package:wingmate/infrastructure/data/phrase_item.dart';

class PhraseItemRepositoryImpl implements PhraseItemRepository {
  final PhraseItemDao phraseItemDao;

  PhraseItemRepositoryImpl(this.phraseItemDao);

  @override
  Future<int> insert(PhraseItem item) {
    return phraseItemDao.insert(item);
  }

  @override
  Future<int> updateItem(PhraseItem item) {
    return phraseItemDao.updateItem(item);
  }

  @override
  Future<int> deleteItem(int id) {
    return phraseItemDao.deleteItem(id);
  }

  @override
  Future<List<PhraseItem>> getAllRootItems() {
    return phraseItemDao.getAllRootItems();
  }

  @override
  Future<List<PhraseItem>> getAllItemsInCategory(int categoryId) {
    return phraseItemDao.getAllItemsInCategory(categoryId);
  }
}

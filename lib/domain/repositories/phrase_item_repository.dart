import 'package:wingmate/infrastructure/models/phrase_item.dart';

abstract class PhraseItemRepository {
  Future<List<PhraseItem>> getPhraseItems();
  Future<int> insertPhrase(PhraseItem item);
  Future<int> updatePhrase(PhraseItem item);
  Future<int> deleteItem(int id);
  Future<List<PhraseItem>> getAllRootItems();
  Future<List<PhraseItem>> getAllItemsInCategory(int categoryId);
  Future<void> updatePhrasePositions(List<PhraseItem> items);
}

import 'package:wingmate/domain/repositories/phrase_item_repository.dart';
import 'package:wingmate/infrastructure/models/phrase_item.dart';

class SelectFolderUseCase {
  final PhraseItemRepository phraseItemRepository;

  SelectFolderUseCase({
    required this.phraseItemRepository,
  });

  Future<List<PhraseItem>> execute(int categoryId) async {
    if (categoryId == -1) {
      return await phraseItemRepository.getAllRootItems();
    } else {
      return await phraseItemRepository.getAllItemsInCategory(categoryId);
    }
  }
}

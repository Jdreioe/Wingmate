import 'package:wingmate/domain/repositories/phrase_item_repository.dart';
import 'package:wingmate/infrastructure/models/phrase_item.dart';

class ReorderItemsUseCase {
  final PhraseItemRepository phraseItemRepository;

  ReorderItemsUseCase({
    required this.phraseItemRepository,
  });

  Future<void> execute(List<PhraseItem> items, int oldIndex, int newIndex) async {
    if (newIndex > oldIndex) {
      newIndex -= 1;
    }
    final item = items.removeAt(oldIndex);
    items.insert(newIndex, item);

    for (int i = 0; i < items.length; i++) {
      items[i] = items[i].copyWith(position: i);
    }
    await phraseItemRepository.updatePhrasePositions(items);
  }
}

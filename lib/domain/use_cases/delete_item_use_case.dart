import 'package:wingmate/domain/repositories/phrase_item_repository.dart';
import 'package:wingmate/infrastructure/models/phrase_item.dart';

class DeleteItemUseCase {
  final PhraseItemRepository phraseItemRepository;

  DeleteItemUseCase({
    required this.phraseItemRepository,
  });

  Future<bool> execute(PhraseItem phrase) async {
    final result = await phraseItemRepository.deleteItem(phrase.id!);
    return result > 0;
  }
}

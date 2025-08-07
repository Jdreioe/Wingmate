import 'package:wingmate/domain/repositories/phrase_item_repository.dart';
import 'package:wingmate/infrastructure/models/phrase_item.dart';
import 'package:wingmate/domain/services/speech_service.dart';
import 'package:wingmate/infrastructure/services/tts/azure_text_to_speech.dart';

class AddPhraseUseCase {
  final PhraseItemRepository phraseItemRepository;
  final AzureTts speechService;

  AddPhraseUseCase({
    required this.phraseItemRepository,
    required this.speechService,
  });

  Future<void> execute(String message, String category, bool categoryChecked, int? parentId) async {
    final phrase = PhraseItem(
      name: category,
      text: message,
      isCategory: categoryChecked,
      parentId: parentId,
      createdAt: DateTime.now().millisecondsSinceEpoch,
    );
    final id = await phraseItemRepository.insertPhrase(phrase);
    final updatedPhrase = phrase.copyWith(id: id);
    final filePath = await speechService.generateAndCacheAudioForItem(updatedPhrase);
    await phraseItemRepository.updatePhrase(updatedPhrase.copyWith(filePath: filePath));
  }
}

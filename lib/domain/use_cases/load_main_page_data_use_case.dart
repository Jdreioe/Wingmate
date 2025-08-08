import 'package:wingmate/domain/repositories/phrase_item_repository.dart';
import 'package:wingmate/domain/repositories/category_repository.dart';
import 'package:wingmate/domain/repositories/voice_repository.dart';
import 'package:wingmate/infrastructure/models/phrase_item.dart';
import 'package:wingmate/infrastructure/models/category_item.dart';

import 'package:wingmate/domain/entities/ui_settings.dart';
import 'package:wingmate/domain/repositories/phrase_item_repository.dart';
import 'package:wingmate/domain/repositories/category_repository.dart';
import 'package:wingmate/domain/repositories/settings_repository.dart';
import 'package:wingmate/domain/repositories/ui_settings_repository.dart';
import 'package:wingmate/domain/repositories/conversation_repository.dart';

class LoadMainPageDataUseCase {
  final PhraseItemRepository phraseItemRepository;
  final CategoryRepository categoryRepository;
  final SettingsRepository settingsRepository;
  final UiSettingsRepository uiSettingsRepository;
  final ConversationRepository conversationRepository;

  LoadMainPageDataUseCase({
    required this.phraseItemRepository,
    required this.categoryRepository,
    required this.settingsRepository,
    required this.uiSettingsRepository,
    required this.conversationRepository,
  });

  Future<Map<String, dynamic>> execute() async {
    final conversations = await conversationRepository.getConversations();
    final settings = await settingsRepository.getSettings();
    final phraseItems = await phraseItemRepository.getPhraseItems();
    // Use getCategoryItems if available, otherwise fallback to getCategories and map
    List categories;
    if (categoryRepository.runtimeType.toString().contains('CategoryItemDao')) {
      categories = await (categoryRepository as dynamic).getCategoryItems();
    } else {
      categories = (await categoryRepository.getCategories()).map((cat) => CategoryItem.fromDomain(cat)).toList();
    }
    final uiSettings = await uiSettingsRepository.getUiSettings();

    // You may want to add logic to get primary/secondary/supported languages from settings or elsewhere
    return {
      'conversations': conversations,
      'settings': settings,
      'phraseItems': phraseItems,
      'categories': categories,
      'uiSettings': uiSettings,
      // Add language fields as needed
    };
  }
}

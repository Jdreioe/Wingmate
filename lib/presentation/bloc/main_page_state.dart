part of 'main_page_bloc.dart';

@immutable
abstract class MainPageState {}

class MainPageInitial extends MainPageState {}

class MainPageLoaded extends MainPageState {
  final StyledTextController messageController;
  final FocusNode messageFocusNode;
  final bool isTextFieldExpanded;
  final bool isKeyboardVisible;
  final List<PhraseItem> phraseItems;
  final List<CategoryItem> categories;
  final CategoryItem? selectedCategory;
  final String primaryLanguage;
  final String secondaryLanguage;
  final List<String> supportedLanguages;
  final String lastUsed;
  final bool isLoading;
  final UiSettings uiSettings;

  final bool isPlaying;

  MainPageLoaded({
    required this.messageController,
    required this.messageFocusNode,
    required this.isTextFieldExpanded,
    required this.isKeyboardVisible,
    required this.phraseItems,
    required this.categories,
    this.selectedCategory,
    required this.primaryLanguage,
    required this.secondaryLanguage,
    required this.supportedLanguages,
    required this.lastUsed,
    required this.isLoading,
    required this.uiSettings,
    this.isPlaying = false,
  });

  MainPageLoaded copyWith({
    StyledTextController? messageController,
    FocusNode? messageFocusNode,
    bool? isTextFieldExpanded,
    bool? isKeyboardVisible,
    List<PhraseItem>? phraseItems,
    List<CategoryItem>? categories,
    CategoryItem? selectedCategory,
    String? primaryLanguage,
    String? secondaryLanguage,
    List<String>? supportedLanguages,
    String? lastUsed,
    bool? isLoading,
    UiSettings? uiSettings,
    bool? isPlaying,
  }) {
    return MainPageLoaded(
      messageController: messageController ?? this.messageController,
      messageFocusNode: messageFocusNode ?? this.messageFocusNode,
      isTextFieldExpanded: isTextFieldExpanded ?? this.isTextFieldExpanded,
      isKeyboardVisible: isKeyboardVisible ?? this.isKeyboardVisible,
      phraseItems: phraseItems ?? this.phraseItems,
      categories: categories ?? this.categories,
      selectedCategory: selectedCategory ?? this.selectedCategory,
      primaryLanguage: primaryLanguage ?? this.primaryLanguage,
      secondaryLanguage: secondaryLanguage ?? this.secondaryLanguage,
      supportedLanguages: supportedLanguages ?? this.supportedLanguages,
      lastUsed: lastUsed ?? this.lastUsed,
      isLoading: isLoading ?? this.isLoading,
      uiSettings: uiSettings ?? this.uiSettings,
      isPlaying: isPlaying ?? this.isPlaying,
    );
  }
}

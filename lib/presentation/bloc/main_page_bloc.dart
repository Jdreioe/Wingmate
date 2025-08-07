import 'package:bloc/bloc.dart';
import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:meta/meta.dart';
import 'package:wingmate/infrastructure/models/phrase_item.dart';
import 'package:wingmate/infrastructure/models/category_item.dart';
import 'package:wingmate/domain/entities/phrase.dart';
import 'package:wingmate/domain/entities/category.dart';
import 'package:wingmate/domain/entities/ui_settings.dart';
import 'package:wingmate/domain/entities/voice.dart';
import 'package:wingmate/presentation/widgets/styled_text_controller.dart';

import 'package:wingmate/domain/use_cases/add_category_use_case.dart';
import 'package:wingmate/domain/use_cases/add_phrase_use_case.dart';
import 'package:wingmate/domain/use_cases/delete_item_use_case.dart';
import 'package:wingmate/domain/use_cases/load_main_page_data_use_case.dart';
import 'package:wingmate/domain/use_cases/play_speech_item_use_case.dart';
import 'package:wingmate/domain/use_cases/reorder_items_use_case.dart';
import 'package:wingmate/domain/use_cases/select_folder_use_case.dart';
import 'package:wingmate/domain/use_cases/speak_from_input_use_case.dart';
import 'package:wingmate/domain/use_cases/stop_playback_use_case.dart';
import 'package:wingmate/domain/use_cases/update_primary_language_use_case.dart';
import 'package:wingmate/domain/use_cases/update_ui_settings_use_case.dart';
import 'package:wingmate/domain/use_cases/update_secondary_language_use_case.dart';
import 'package:wingmate/domain/use_cases/toggle_play_pause_use_case.dart';

part 'main_page_event.dart';
part 'main_page_state.dart';

class MainPageBloc extends Bloc<MainPageEvent, MainPageState> {
  final LoadMainPageDataUseCase loadMainPageDataUseCase;
  final AddPhraseUseCase addPhraseUseCase;
  final DeleteItemUseCase deleteItemUseCase;
  final ReorderItemsUseCase reorderItemsUseCase;
  final SelectFolderUseCase selectFolderUseCase;
  final PlaySpeechItemUseCase playSpeechItemUseCase;
  final SpeakFromInputUseCase speakFromInputUseCase;
  final StopPlaybackUseCase stopPlaybackUseCase;
  final UpdatePrimaryLanguageUseCase updatePrimaryLanguageUseCase;
  final UpdateUiSettingsUseCase updateUiSettingsUseCase;
  final AddCategoryUseCase addCategoryUseCase;
  final UpdateSecondaryLanguageUseCase updateSecondaryLanguageUseCase;
  final TogglePlayPauseUseCase togglePlayPauseUseCase;

  
  late StyledTextController _keyboardController;
  String _primaryLanguage = 'en-US';
  String _secondaryLanguage = 'en-US'; // Default secondary language
  List<String> _supportedLanguages = [];
  String _lastUsed = '';
  List<PhraseItem> _phraseItems = [];
  List<CategoryItem> _categories = [];
  int _currentFolderId = -1;
  bool _isSomeFolderSelected = false;
  UiSettings? _uiSettings;

  MainPageBloc({
    required this.loadMainPageDataUseCase,
    required this.addPhraseUseCase,
    required this.deleteItemUseCase,
    required this.reorderItemsUseCase,
    required this.selectFolderUseCase,
    required this.playSpeechItemUseCase,
    required this.speakFromInputUseCase,
    required this.stopPlaybackUseCase,
    required this.updatePrimaryLanguageUseCase,
    required this.updateUiSettingsUseCase,
    required this.addCategoryUseCase,
    required this.togglePlayPauseUseCase,
    required this.updateSecondaryLanguageUseCase,
  }) : super(MainPageInitial()) {
    _keyboardController = StyledTextController(
      highlightColor: Colors.amber.shade400,
      uiSettings: _uiSettings ?? UiSettings(name: 'default'),
    );
    

    on<LoadMainPage>(_onLoadMainPage);
    on<TogglePlayPause>(_onTogglePlayPause);
    on<AddXmlTag>(_onAddXmlTag);
    on<HandleSaveMessage>(_onHandleSaveMessage);
    on<DeleteItem>(_onDeleteItem);
    on<ReorderItems>(_onReorderItems);
    on<SelectFolder>(_onSelectFolder);
    on<SelectRootFolder>(_onSelectRootFolder);
    on<PlaySpeechItem>(_onPlaySpeechItem);
    on<AddTextToInput>(_onAddTextToInput);
    on<SpeakFromInput>(_onSpeakFromInput);
    on<StopPlayback>(_onStopPlayback);
    on<OnReplayPressed>(_onOnReplayPressed);
    on<AddBreak>(_onAddBreak);
    on<AddPhrase>(_onAddPhrase);
    on<EditPhrase>(_onEditPhrase);
    on<UpdatePrimaryLanguage>(_onUpdatePrimaryLanguage);
    on<UpdateSecondaryLanguage>(_onUpdateSecondaryLanguage);
    on<UpdateUiSettings>(_onUpdateUiSettings);
    on<AddCategory>(_onAddCategory);
    on<ToggleTextFieldExpanded>(_onToggleTextFieldExpanded);
    on<UpdateKeyboardVisibility>(_onUpdateKeyboardVisibility);

    _init();
  }

  Future<void> _init() async {
    final data = await loadMainPageDataUseCase.execute();
    _phraseItems = (data['phraseItems'] as List<PhraseItem>?) ?? <PhraseItem>[];
    _categories = (data['categories'] as List<CategoryItem>?) ?? <CategoryItem>[];
    _primaryLanguage = data['primaryLanguage'] ?? 'en-US';
    _secondaryLanguage = data['secondaryLanguage'] ?? 'en-US';
    _supportedLanguages = data['supportedLanguages'] ?? <String>[];
    _uiSettings = data['uiSettings'];
    _keyboardController.dispose();
    _keyboardController = StyledTextController(
      highlightColor: Colors.amber.shade400,
      uiSettings: _uiSettings ?? UiSettings(name: 'default'),
    );
    _emitLoadedState();
  }

  Future<void> _onLoadMainPage(LoadMainPage event, Emitter<MainPageState> emit) async {
    final data = await loadMainPageDataUseCase.execute();
    _phraseItems = (data['phraseItems'] as List<PhraseItem>?) ?? <PhraseItem>[];
    _categories = (data['categories'] as List<CategoryItem>?) ?? <CategoryItem>[];
    _primaryLanguage = data['primaryLanguage'] ?? 'en-US';
    _secondaryLanguage = data['secondaryLanguage'] ?? 'en-US';
    _supportedLanguages = data['supportedLanguages'] ?? <String>[];
    _uiSettings = data['uiSettings'];
    _keyboardController.dispose();
    _keyboardController = StyledTextController(
      highlightColor: Colors.amber.shade400,
      uiSettings: _uiSettings ?? UiSettings(name: 'default'),
    );
    _emitLoadedState();
  }

  void _emitLoadedState() {
    emit(MainPageLoaded(
      messageController: _keyboardController,
      messageFocusNode: FocusNode(), // FocusNode should be managed by the UI or passed in
      isTextFieldExpanded: _isSomeFolderSelected,
      isKeyboardVisible: false, // This needs to be managed by the BLoC
      phraseItems: _phraseItems,
      categories: _categories,
      selectedCategory: _currentFolderId != -1 ? _categories.firstWhere((element) => element.id == _currentFolderId) : null,
      primaryLanguage: _primaryLanguage,
      secondaryLanguage: _secondaryLanguage,
      supportedLanguages: _supportedLanguages,
      lastUsed: _lastUsed,
      isLoading: false,
      uiSettings: _uiSettings ?? UiSettings(name: 'default'),
    ));
  }

  Future<void> _onTogglePlayPause(TogglePlayPause event, Emitter<MainPageState> emit) async {
    await togglePlayPauseUseCase.execute(state is MainPageLoaded ? (state as MainPageLoaded).isPlaying : false, event.text);
    _emitLoadedState();
  }

  Future<void> _onAddXmlTag(AddXmlTag event, Emitter<MainPageState> emit) async {
    // This functionality needs to be moved to a use case if it's still required.
    _emitLoadedState();
  }

  Future<void> _onHandleSaveMessage(HandleSaveMessage event, Emitter<MainPageState> emit) async {
    await addPhraseUseCase.execute(event.message, event.category, event.categoryChecked, _currentFolderId == -1 ? null : _currentFolderId);
    await _reloadData();
    _emitLoadedState();
  }

  Future<void> _onDeleteItem(DeleteItem event, Emitter<MainPageState> emit) async {
    final itemToDelete = _phraseItems[event.index];
    await deleteItemUseCase.execute(itemToDelete);
    await _reloadData();
    _emitLoadedState();
  }

  Future<void> _onReorderItems(ReorderItems event, Emitter<MainPageState> emit) async {
    await reorderItemsUseCase.execute(_phraseItems, event.oldIndex, event.newIndex);
    _emitLoadedState();
  }

  Future<void> _onSelectFolder(SelectFolder event, Emitter<MainPageState> emit) async {
    _currentFolderId = event.categoryId;
    _isSomeFolderSelected = event.categoryId != -1;
    _phraseItems = await selectFolderUseCase.execute(event.categoryId);
    _emitLoadedState();
  }

  Future<void> _onSelectRootFolder(SelectRootFolder event, Emitter<MainPageState> emit) async {
    _currentFolderId = -1;
    _isSomeFolderSelected = false;
    _phraseItems = await selectFolderUseCase.execute(-1);
    _emitLoadedState();
  }

  Future<void> _onPlaySpeechItem(PlaySpeechItem event, Emitter<MainPageState> emit) async {
    await playSpeechItemUseCase.execute(event.item);
    _emitLoadedState();
  }

  Future<void> _onAddTextToInput(AddTextToInput event, Emitter<MainPageState> emit) async {
    final selection = _keyboardController.selection;
    final currentText = _keyboardController.text;

    if (selection.start == -1 || selection.end == -1) {
      final newText = currentText + event.text;
      _keyboardController.value = TextEditingValue(
        text: newText,
        selection: TextSelection.fromPosition(
          TextPosition(offset: newText.length),
        ),
      );
    } else {
      final newText = currentText.replaceRange(
          selection.start, selection.end, event.text);
      _keyboardController.value = TextEditingValue(
        text: newText,
        selection: TextSelection.fromPosition(
          TextPosition(offset: selection.start + event.text.length),
        ),
      );
    }
    _emitLoadedState();
  }

  Future<void> _onSpeakFromInput(SpeakFromInput event, Emitter<MainPageState> emit) async {
    await speakFromInputUseCase.execute(event.text);
    _emitLoadedState();
  }

  Future<void> _onStopPlayback(StopPlayback event, Emitter<MainPageState> emit) async {
    await stopPlaybackUseCase.execute();
    _emitLoadedState();
  }

  Future<void> _onOnReplayPressed(OnReplayPressed event, Emitter<MainPageState> emit) async {
    if (_keyboardController.text.isNotEmpty) {
      _lastUsed = _keyboardController.text;
      _keyboardController.clear();
    } else if (_lastUsed.isNotEmpty) {
      _keyboardController.text = _lastUsed;
      _lastUsed = '';
    }
    _emitLoadedState();
  }

  Future<void> _onAddBreak(AddBreak event, Emitter<MainPageState> emit) async {
    _onAddTextToInput(AddTextToInput('[BREAK:${event.breakTime}]'), emit);
    _emitLoadedState();
  }

  Future<void> _onAddPhrase(AddPhrase event, Emitter<MainPageState> emit) async {
    // This will require context from the UI layer to show the dialog
    // For now, we'll just reload phrases
    await _reloadData();
    _emitLoadedState();
  }

  Future<void> _onEditPhrase(EditPhrase event, Emitter<MainPageState> emit) async {
    // This will require context from the UI layer to show the dialog
    // For now, we'll just reload phrases
    await _reloadData();
    _emitLoadedState();
  }

  Future<void> _onUpdatePrimaryLanguage(UpdatePrimaryLanguage event, Emitter<MainPageState> emit) async {
    _primaryLanguage = event.language;
    await updatePrimaryLanguageUseCase.execute(event.language);
    _emitLoadedState();
  }

  Future<void> _onUpdateSecondaryLanguage(UpdateSecondaryLanguage event, Emitter<MainPageState> emit) async {
    _secondaryLanguage = event.language;
    await updateSecondaryLanguageUseCase.execute(event.language);
    _emitLoadedState();
  }

  Future<void> _onUpdateUiSettings(UpdateUiSettings event, Emitter<MainPageState> emit) async {
    _uiSettings = event.uiSettings;
    _keyboardController = StyledTextController(
      highlightColor: Colors.amber.shade400,
      uiSettings: _uiSettings ?? UiSettings(name: 'default'),
    );
    await updateUiSettingsUseCase.execute(event.uiSettings);
    _emitLoadedState();
  }

  Future<void> _onAddCategory(AddCategory event, Emitter<MainPageState> emit) async {
    await addCategoryUseCase.execute(event.categoryName, _secondaryLanguage);
    await _reloadData();
    _emitLoadedState();
  }

  Future<void> _reloadData() async {
    final data = await loadMainPageDataUseCase.execute();
    _phraseItems = (data['phraseItems'] as List<PhraseItem>?) ?? <PhraseItem>[];
    _categories = (data['categories'] as List<CategoryItem>?) ?? <CategoryItem>[];
    _primaryLanguage = data['primaryLanguage'] ?? 'en-US';
    _secondaryLanguage = data['secondaryLanguage'] ?? 'en-US';
    _supportedLanguages = data['supportedLanguages'] ?? <String>[];
    _uiSettings = data['uiSettings'];
  }

  Future<void> _onToggleTextFieldExpanded(ToggleTextFieldExpanded event, Emitter<MainPageState> emit) async {
    _isSomeFolderSelected = !_isSomeFolderSelected;
    _emitLoadedState();
  }

  Future<void> _onUpdateKeyboardVisibility(UpdateKeyboardVisibility event, Emitter<MainPageState> emit) async {
    // The MainPageService doesn't directly manage keyboard visibility,
    // so we'll update the state directly here.
    // This assumes that the `isKeyboardVisible` in MainPageLoaded state
    // is directly controlled by this event.
    if (state is MainPageLoaded) {
      emit((state as MainPageLoaded).copyWith(isKeyboardVisible: event.isVisible));
    }
  }

  @override
  Future<void> close() {
    _keyboardController.dispose();
    // The speech service should be disposed via dependency injection setup
    return super.close();
  }
}

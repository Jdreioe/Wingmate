import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:wingmate/data/app_database.dart';
import 'package:wingmate/config/speech_service_config.dart';
import 'package:wingmate/services/tts/azure_text_to_speech.dart';
import '../services/main_page_service.dart';
import 'package:wingmate/widgets/styled_text_controller.dart';
import 'package:wingmate/ui/main_page/widgets/category_selector.dart';
import 'package:wingmate/ui/main_page/widgets/phrase_grid.dart';
import 'package:wingmate/ui/main_page/widgets/playback_controls.dart';
import 'package:wingmate/ui/main_page/widgets/text_input.dart' as custom_text_input;
import 'package:wingmate/ui/main_page/widgets/top_bar.dart';
import 'package:wingmate/ui/settings_page.dart';
import 'package:wingmate/ui/history_page.dart';
import 'package:wingmate/dialogs/add_phrase_dialog.dart';
import 'package:wingmate/data/phrase_item.dart';
import 'package:wingmate/data/category_item.dart';
import 'package:wingmate/data/category_dao.dart';
import 'package:wingmate/data/ui_settings_dao.dart';
import 'package:wingmate/data/ui_settings.dart';
import 'package:wingmate/data/phrase_item_dao.dart';
import 'package:wingmate/data/said_text_dao.dart';
import 'package:wingmate/services/subscription_manager.dart';
class MainPage extends StatefulWidget {
  final String speechServiceEndpoint;
  final String speechServiceKey;
  final Future<void> Function(String endpoint, String key, UiSettings uiSettings) onSaveSettings;
  final UiSettings uiSettings;

  const MainPage({
    Key? key,
    required this.speechServiceEndpoint,
    required this.speechServiceKey,
    required this.onSaveSettings,
    required this.uiSettings,
  }) : super(key: key);

  @override
  _MainPageState createState() => _MainPageState();
}

class _MainPageState extends State<MainPage> with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  late final MainPageService _service;
  late AzureTts _azureTts;
  final StyledTextController _keyboardController = StyledTextController(
      highlightColor: Colors.amber.shade400);
  String _secondaryLanguage = 'es-ES'; // Default secondary language

  late AnimationController _animationController;
  late Animation<double> _animation;

  bool _isTextFieldExpanded = false;

  bool _isKeyboardVisible = false;
  List<PhraseItem> _phraseItems = []; // New: List to hold phrase items
  CategoryItem? _selectedCategory; // New: Currently selected category

  String _lastUsed = '';

  @override
  void didChangeMetrics() {
    final bottomInset = WidgetsBinding.instance.window.viewInsets.bottom;
    final newValue = bottomInset > 0.0;
    if (newValue != _isKeyboardVisible) {
      setState(() {
        _isKeyboardVisible = newValue;
      });
    }
  }

  late String _speechServiceEndpoint;
  late String _speechServiceKey;
  late UiSettings _currentUiSettings;

  @override
  void initState() {
    super.initState();
    _speechServiceEndpoint = widget.speechServiceEndpoint;
    _speechServiceKey = widget.speechServiceKey;
    _currentUiSettings = widget.uiSettings;
    WidgetsBinding.instance.addObserver(this);
    _initializeService(_currentUiSettings);

    _animationController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 300),
    );
    _animation = CurvedAnimation(
      parent: _animationController,
      curve: Curves.easeInOut,
    );

    // NEW: Add listener to update UI on text change in keyboard mode
    _keyboardController.addListener(() {
      setState(() {});
    });
    _initializeAzureTts();
  }

  Future<void> _initializeService(UiSettings uiSettings) async {
    _service = MainPageService(
      messageController: TextEditingController(),
      messageFocusNode: FocusNode(),
      phraseItemDao: PhraseItemDao(AppDatabase()),
      saidTextDao: SaidTextDao(AppDatabase()),
      categoryDao: CategoryDao(AppDatabase()), // New: Pass CategoryDao
      subscriptionManager: SubscriptionManager(
        onSubscriptionStatusChanged: (isSubscribed) {},
      ),
      settingsBox: Hive.box('settings'),
      voiceBox: Hive.box('selectedVoice'),
      context: context,
      speechServiceEndpoint: widget.speechServiceEndpoint,
      speechServiceKey: widget.speechServiceKey,
      onSaveSettings: widget.onSaveSettings,
      uiSettings: uiSettings,
    );
    await _service.initialize();
    await _loadPhraseItems();
    await _loadCategories(); // New: Load categories on init
  }

  void _initializeAzureTts() {
    _azureTts = AzureTts(
      region: widget.speechServiceEndpoint,
      subscriptionKey: widget.speechServiceKey,
      settingsBox: Hive.box('settings'),
      voiceBox: Hive.box('selectedVoice'),
      context: context,
      saidTextDao: SaidTextDao(AppDatabase()),
    );
  }


  

  

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _service.dispose();
    _keyboardController.dispose();
    _azureTts.player.dispose();
    _animationController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: _buildPrototypeUI(),
      ),
      bottomNavigationBar: BottomNavigationBar(
        items: const <BottomNavigationBarItem>[
          BottomNavigationBarItem(
            icon: Icon(Icons.grid_view),
            label: 'Phrases',
          ),
          BottomNavigationBarItem(
            icon: Icon(Icons.settings_voice),
            label: 'Voice settings',
          ),
        ],
      ),
    );
  }

  Widget _buildPrototypeUI() {
    return Padding(
      padding: const EdgeInsets.all(8.0),
      child: Column(
        children: [
          TopBar(
            onWrapWithLangTag: _wrapWithLangTag,
            onHistoryPressed: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => HistoryPage(),
                ),
              );
            },
            onSettingsPressed: () {
              showGeneralDialog(
                context: context,
                pageBuilder: (context, animation, secondaryAnimation) =>
                    SettingsPage(
                      uiSettingsDao: UiSettingsDao(AppDatabase()),
                      onSettingsSaved: (newUiSettings) {
                        widget.onSaveSettings(
                          widget.speechServiceEndpoint,
                          widget.speechServiceKey,
                          newUiSettings,
                        );
                      },
                      speechServiceEndpoint: widget.speechServiceEndpoint,
                      speechServiceKey: widget.speechServiceKey,
                      onSaveSettings: widget.onSaveSettings,
                    ),
                transitionDuration: const Duration(milliseconds: 300),
                transitionBuilder: (context, animation, secondaryAnimation, child) {
                  return SlideTransition(
                    position: Tween(begin: const Offset(0, 1), end: Offset.zero).animate(animation),
                    child: child,
                  );
                },
              );
            },
            secondaryLanguageSelector: _buildSecondaryLanguageSelector(),
          ),
          const SizedBox(height: 8),
          const SizedBox(height: 8),
          AnimatedBuilder(
            animation: _animation,
            builder: (context, child) {
              final int maxLines = _isKeyboardVisible ? 5 : 8;

              return custom_text_input.TextInput(
                controller: _keyboardController,
                isExpanded: _isTextFieldExpanded,
                animation: _animation,
                maxLines: maxLines,
                uiSettings: widget.uiSettings,
              );
            },
          ),
          const SizedBox(height: 8),
          PlaybackControls(
            isExpanded: _isTextFieldExpanded,
            onPlayPressed: _speakFromInput,
            onStopPressed: _stopPlayback,
            onReplayPressed: _onReplayPressed,
            onToggleExpand: () {
              setState(() {
                _isTextFieldExpanded = !_isTextFieldExpanded;
                if (_isTextFieldExpanded) {
                  _animationController.forward();
                } else {
                  _animationController.reverse();
                }
              });
            },
            isTextEmpty: _keyboardController.text.isEmpty,
            isLastUsedEmpty: _lastUsed.isEmpty,
          ),
          const SizedBox(height: 8),
          Expanded(
            child: AnimatedSwitcher(
              duration: const Duration(milliseconds: 300),
              layoutBuilder: (Widget? currentChild,
                  List<Widget> previousChildren) {
                return Stack(
                  fit: StackFit.expand,
                  children: <Widget>[
                    ...previousChildren,
                    if (currentChild != null) currentChild,
                  ],
                );
              },
              child: _isTextFieldExpanded && _isKeyboardVisible
                  ? Container(key: const ValueKey('empty'))
                  : Column(
                key: const ValueKey('grid'),
                children: [
                  CategorySelector(
                    onAddCategoryPressed: _addCategory,
                    categories: _categories,
                    onCategorySelected: (category) {
                      setState(() {
                        _selectedCategory = category;
                      });
                      _loadPhraseItems(); // Reload phrases for the selected category
                    },
                  ),
                  SizedBox(height: 8),
                  Expanded(
                      child: PhraseGrid(
                        onPhraseSelected: _addTextToInput,
                        onPhraseLongPressed: _editPhrase, // New: Pass the edit function
                        uiSettings: widget.uiSettings,
                        phraseItems: _phraseItems,
                      )),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }


  // --- Helper Methods for UI Logic ---

  // --- NEW: Methods for secondary language feature ---

  Widget _buildSecondaryLanguageSelector() {
    return DropdownButton<String>(
      value: _secondaryLanguage,
      onChanged: (String? newValue) {
        if (newValue != null) {
          setState(() {
            _secondaryLanguage = newValue;
          });
        }
      },
      underline: Container(),
      items: const [
        DropdownMenuItem(value: 'en-US', child: Text('English')),
        DropdownMenuItem(value: 'es-ES', child: Text('Español')),
        DropdownMenuItem(value: 'da-DK', child: Text('Dansk')),
        DropdownMenuItem(value: 'fr-FR', child: Text('Français')),
      ],
    );
  }

  void _wrapWithLangTag() {
    final text = _keyboardController.text;
    final selection = _keyboardController.selection;
    if (!selection.isValid || selection.isCollapsed) return;

    final selectedText = selection.textInside(text);
    final newText =
        '<lang xml:lang="$_secondaryLanguage">$selectedText</lang>';

    _keyboardController.text = text.replaceRange(
      selection.start,
      selection.end,
      newText,
    );
    // Move cursor after the inserted tag
    _keyboardController.selection = TextSelection.fromPosition(
      TextPosition(offset: selection.start + newText.length),
    );
  }

  void _addTextToInput(String text) {
    if (text == 'ADD_PHRASE_BUTTON') {
      _addPhrase();
      return;
    }

    final selection = _keyboardController.selection;
    final currentText = _keyboardController.text;

    if (selection.start == -1 || selection.end == -1) {
      // No valid selection, append the text to the end.
      final newText = currentText + text;
      _keyboardController.value = TextEditingValue(
        text: newText,
        selection: TextSelection.fromPosition(
          TextPosition(offset: newText.length),
        ),
      );
    } else {
      // Valid selection, replace the selected range.
      final newText = currentText.replaceRange(
          selection.start, selection.end, text);
      _keyboardController.value = TextEditingValue(
        text: newText,
        selection: TextSelection.fromPosition(
          TextPosition(offset: selection.start + text.length),
        ),
      );
    }
  }

  void _speakFromInput() {
    final text = _keyboardController.text;
    if (text
        .trim()
        .isNotEmpty) {
      _azureTts.speakText(text);
    }
  }

  void _stopPlayback() {
    _azureTts.player.stop();
  }

  void _onReplayPressed() {
    setState(() {
      if (_keyboardController.text.isNotEmpty) {
        _lastUsed = _keyboardController.text;
        _keyboardController.clear();
      } else if (_lastUsed.isNotEmpty) {
        _keyboardController.text = _lastUsed;
        _lastUsed = '';
      }
    });
  }

  void _addPhrase() async {
    final categories = await _service.categoryDao.getAll();
    showDialog<void>(
      context: context,
      builder: (context) {
        return AddPhraseDialog(
          categories: categories,
          onSave: (newPhrase) async {
            await _service.phraseItemDao.insert(newPhrase);
            _loadPhraseItems(); // Refresh the PhraseGrid
          },
        );
      },
    );
  }

  Future<void> _loadPhraseItems() async {
    debugPrint('Loading phrase items...');
    List<PhraseItem> items;
    if (_selectedCategory != null) {
      items = await _service.phraseItemDao.getAllItemsInCategory(_selectedCategory!.id!); // Load phrases for selected category
    } else {
      items = await _service.phraseItemDao.getAll(); // Load all phrases if no category selected
    }
    debugPrint('Fetched ${items.length} items from DAO.');
    setState(() {
      _phraseItems = items.where((item) => item.isCategory == false).toList(); // Filter out categories
      debugPrint('Filtered phrase items: ${_phraseItems.length}');
      // Add a special PhraseItem for the "Add Phrase" button
      _phraseItems.add(PhraseItem(
        name: '+',
        text: 'Add Phrase',
        isCategory: false, // Treat as a regular item for display purposes
        id: -1, // A unique ID to identify it as the add button
      ));
      debugPrint('Total phrase items after adding button: ${_phraseItems.length}');
    });
  }

  void _addCategory() async {
    final newCategoryName = await showDialog<String>(
      context: context,
      builder: (context) {
        final controller = TextEditingController();
        return AlertDialog(
          title: const Text('Add New Category'),
          content: TextField(
            controller: controller,
            decoration: const InputDecoration(labelText: 'Category Name'),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context), // Cancel
              child: const Text('Cancel'),
            ),
            ElevatedButton(
              onPressed: () => Navigator.pop(context, controller.text.trim()), // Save
              child: const Text('Add'),
            ),
          ],
        );
      },
    );

    if (newCategoryName != null && newCategoryName.isNotEmpty) {
      final newCategory = CategoryItem(
        name: newCategoryName,
        language: _secondaryLanguage, // Use the currently selected language
        // You can add color and icon selection here later
      );
      await _service.categoryDao.insert(newCategory);
      // Refresh categories in CategorySelector
      _loadCategories();
      debugPrint('New category added: $newCategoryName');
    }
  }

  void _editPhrase(PhraseItem phraseItem) async {
    final categories = await _service.categoryDao.getAll();
    showDialog<void>(
      context: context,
      builder: (context) {
        return AddPhraseDialog(
          phraseItem: phraseItem, // Pass the existing phrase item for editing
          categories: categories,
          onSave: (updatedPhrase) async {
            await _service.phraseItemDao.updateItem(updatedPhrase);
            _loadPhraseItems(); // Refresh the PhraseGrid
          },
        );
      },
    );
  }

  List<CategoryItem> _categories = [];

  Future<void> _loadCategories() async {
    final loadedCategories = await _service.categoryDao.getAll();
    setState(() {
      _categories = loadedCategories;
    });
  }
}

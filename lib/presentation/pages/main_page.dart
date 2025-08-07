import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:wingmate/presentation/bloc/main_page_bloc.dart';
import 'package:wingmate/infrastructure/data/app_database.dart';
import 'package:wingmate/domain/entities/ui_settings.dart';
import 'package:wingmate/infrastructure/data/ui_settings_dao.dart';
import 'package:wingmate/presentation/widgets/add_break_dialog.dart';
import 'package:wingmate/presentation/widgets/add_phrase_dialog.dart';
import 'package:wingmate/presentation/pages/history_page.dart';
import 'package:wingmate/presentation/widgets/category_selector.dart';
import 'package:wingmate/presentation/widgets/playback_controls.dart';
import 'package:wingmate/presentation/widgets/phrase_grid.dart';
import 'package:wingmate/presentation/widgets/text_input.dart' as custom_text_input;
import 'package:wingmate/presentation/widgets/top_bar.dart';
import 'package:wingmate/presentation/pages/settings_page.dart';
import 'package:wingmate/presentation/widgets/voice_settings_tab.dart';
import 'package:wingmate/infrastructure/models/phrase_item.dart';

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
  late AnimationController _animationController;
  late Animation<double> _animation;
  int _selectedIndex = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    _animationController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 300),
    );
    _animation = CurvedAnimation(
      parent: _animationController,
      curve: Curves.easeInOut,
    );
  }

  @override
  void didChangeMetrics() {
    final bottomInset = WidgetsBinding.instance.window.viewInsets.bottom;
    final newValue = bottomInset > 0.0;
    if (newValue != (context.read<MainPageBloc>().state as MainPageLoaded).isKeyboardVisible) {
      context.read<MainPageBloc>().add(UpdateKeyboardVisibility(newValue));
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _animationController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<MainPageBloc, MainPageState>(
      listener: (context, state) {
        if (state is MainPageLoaded) {
          if (state.isTextFieldExpanded) {
            _animationController.forward();
          } else {
            _animationController.reverse();
          }
        }
      },
      builder: (context, state) {
        if (state is MainPageLoaded) {
          return Scaffold(
            body: SafeArea(
              child: _buildPrototypeUI(state),
            ),
            bottomNavigationBar: BottomNavigationBar(
              items: const <BottomNavigationBarItem>[
                BottomNavigationBarItem(
                  icon: Icon(Icons.grid_view),
                  label: 'Phrases',
                ),
                BottomNavigationBarItem(
                  icon: Icon(Icons.settings),
                  label: 'Settings',
                ),
                BottomNavigationBarItem(
                  icon: Icon(Icons.mic),
                  label: 'Voice Settings',
                ),
              ],
              currentIndex: _selectedIndex,
              onTap: (index) {
                setState(() {
                  _selectedIndex = index;
                });
              },
            ),
          );
        }
        return const Center(child: CircularProgressIndicator());
      },
    );
  }

  Widget _buildPrototypeUI(MainPageLoaded state) {
    return Padding(
      padding: const EdgeInsets.all(8.0),
      child: Column(
        children: [
          TopBar(
            onWrapWithLangTag: () => context.read<MainPageBloc>().add(AddXmlTag('[lang]')),
            onHistoryPressed: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => HistoryPage(),
                ),
              );
            },
            onSettingsPressed: () {
              setState(() {
                _selectedIndex = 1;
              });
            },
            primaryLanguageSelector: _buildPrimaryLanguageSelector(state),
            secondaryLanguageSelector: _buildSecondaryLanguageSelector(state),
            showWrapWithLangTag: state.supportedLanguages.isNotEmpty,
          ),
          const SizedBox(height: 8),
          const SizedBox(height: 8),
          AnimatedBuilder(
            animation: _animation,
            builder: (context, child) {
              final int maxLines = state.isKeyboardVisible ? 5 : 8;

              return custom_text_input.TextInput(
                controller: state.messageController,
                isExpanded: state.isTextFieldExpanded,
                animation: _animation,
                maxLines: maxLines,
                uiSettings: state.uiSettings,
              );
            },
          ),
          const SizedBox(height: 8),
          PlaybackControls(
            isExpanded: state.isTextFieldExpanded,
            onPlayPressed: () => context.read<MainPageBloc>().add(SpeakFromInput(state.messageController.text)),
            onStopPressed: () => context.read<MainPageBloc>().add(StopPlayback()),
            onReplayPressed: () => context.read<MainPageBloc>().add(OnReplayPressed()),
            onAddBreakPressed: _addBreak,
            onToggleExpand: () {
              context.read<MainPageBloc>().add(ToggleTextFieldExpanded());
            },
            isTextEmpty: state.messageController.text.isEmpty,
            isLastUsedEmpty: state.lastUsed.isEmpty,
          ),
          const SizedBox(height: 8),
          Expanded(
            child: IndexedStack(
              index: _selectedIndex,
              children: [
                _buildPhrasesView(state),
                _buildSettingsView(state),
                _buildVoiceSettingsView(state),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPhrasesView(MainPageLoaded state) {
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 300),
      layoutBuilder: (Widget? currentChild, List<Widget> previousChildren) {
        return Stack(
          fit: StackFit.expand,
          children: <Widget>[
            ...previousChildren,
            if (currentChild != null) currentChild,
          ],
        );
      },
      child: state.isTextFieldExpanded && state.isKeyboardVisible
          ? Container(key: const ValueKey('empty'))
          : Column(
        key: const ValueKey('grid'),
        children: [
          CategorySelector(
            onAddCategoryPressed: _addCategory,
            categories: state.categories,
            onCategorySelected: (category) {
              context.read<MainPageBloc>().add(SelectFolder(category.id!, category.name!));
            },
          ),
          SizedBox(height: 8),
          Expanded(
              child: PhraseGrid(
                onPhraseSelected: (text) => context.read<MainPageBloc>().add(AddTextToInput(text)),
                onPhraseLongPressed: (phraseItem) => _editPhrase(phraseItem, state),
                uiSettings: state.uiSettings,
                phraseItems: state.phraseItems,
              )),
        ],
      ),
    );
  }

  Widget _buildSettingsView(MainPageLoaded state) {
    return SettingsPage(
      uiSettingsDao: UiSettingsDao(AppDatabase()),
      onSettingsSaved: (newUiSettings) {
        context.read<MainPageBloc>().add(UpdateUiSettings(newUiSettings));
      },
      speechServiceEndpoint: widget.speechServiceEndpoint,
      speechServiceKey: widget.speechServiceKey,
      onSaveSettings: widget.onSaveSettings,
    );
  }

  Widget _buildVoiceSettingsView(MainPageLoaded state) {
    return VoiceSettingsTab(
      endpoint: widget.speechServiceEndpoint,
      subscriptionKey: widget.speechServiceKey,
      onSaveSettings: widget.onSaveSettings,
      uiSettings: state.uiSettings,
    );
  }

  Widget _buildPrimaryLanguageSelector(MainPageLoaded state) {
    final uniqueLanguages = state.supportedLanguages.toSet().toList();
    return DropdownButton<String>(
      value: uniqueLanguages.contains(state.primaryLanguage) ? state.primaryLanguage : null,
      onChanged: (String? newValue) {
        if (newValue != null) {
          context.read<MainPageBloc>().add(UpdatePrimaryLanguage(newValue));
        }
      },
      underline: Container(),
      items: uniqueLanguages.map<DropdownMenuItem<String>>((String value) {
        return DropdownMenuItem<String>(
          value: value,
          child: Text(value),
        );
      }).toList(),
    );
  }

  Widget _buildSecondaryLanguageSelector(MainPageLoaded state) {
    final uniqueLanguages = state.supportedLanguages.toSet().toList();
    return DropdownButton<String>(
      value: uniqueLanguages.contains(state.secondaryLanguage) ? state.secondaryLanguage : null,
      onChanged: (String? newValue) {
        if (newValue != null) {
          context.read<MainPageBloc>().add(UpdateSecondaryLanguage(newValue));
        }
      },
      underline: Container(),
      items: uniqueLanguages.map<DropdownMenuItem<String>>((String value) {
        return DropdownMenuItem<String>(
          value: value,
          child: Text(value),
        );
      }).toList(),
    );
  }

  

  

  void _addBreak() {
    showDialog<int>(
      context: context,
      builder: (context) => const AddBreakDialog(),
    ).then((breakTime) {
      if (breakTime != null) {
        context.read<MainPageBloc>().add(AddBreak(breakTime));
      }
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
      context.read<MainPageBloc>().add(AddCategory(newCategoryName));
    }
  }

  void _editPhrase(PhraseItem phraseItem, MainPageLoaded state) async {
    showDialog<void>(
      context: context,
      builder: (context) {
        return AddPhraseDialog(
          phraseItem: phraseItem,
          categories: state.categories,
          onSave: (updatedPhrase) {
            context.read<MainPageBloc>().add(EditPhrase(updatedPhrase));
          },
        );
      },
    );
  }
}

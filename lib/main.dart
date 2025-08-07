import 'package:flutter/material.dart';
import 'package:hive/hive.dart';
import 'package:hive_flutter/hive_flutter.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:wingmate/presentation/app.dart';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import 'package:wingmate/core/platform_info.dart';

import 'package:wingmate/core/app_initializer.dart';
import 'package:wingmate/infrastructure/config/speech_service_config.dart';
import 'package:wingmate/infrastructure/config/speech_service_config_adapter.dart';
import 'package:wingmate/domain/entities/voice.dart';

import 'package:wingmate/infrastructure/data/app_database.dart';
import 'package:wingmate/domain/entities/ui_settings.dart';
import 'package:wingmate/infrastructure/data/ui_settings_dao.dart';
import 'package:wingmate/presentation/bloc/main_page_bloc.dart';

import 'package:wingmate/infrastructure/data/phrase_item_dao.dart';
import 'package:wingmate/infrastructure/data/category_item_dao.dart';
import 'package:wingmate/infrastructure/data/voice_dao.dart';
import 'package:wingmate/infrastructure/services/tts/azure_text_to_speech.dart';
import 'package:wingmate/infrastructure/data/said_text_dao.dart';

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

final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  if (kIsWeb) {
    await Hive.initFlutter();
  } else if (isLinux) {
    final tempDir = await getTemporaryDirectory();
    Hive.init(tempDir.path);
  } else {
    final appDocumentDir = await getApplicationDocumentsDirectory();
    Hive.init(appDocumentDir.path);
  }

  try {
    if (!Hive.isAdapterRegistered(1)) {
      Hive.registerAdapter(VoiceAdapter());
    }
    if (!Hive.isAdapterRegistered(SpeechServiceConfigAdapter().typeId)) {
      Hive.registerAdapter(SpeechServiceConfigAdapter());
    }

    // Open Hive boxes after adapters are registered
    await Hive.openBox('selectedVoice');
    await Hive.openBox('settings');

    await AppInitializer.initialize();
    final appDatabase = AppDatabase();
    final uiSettingsDao = UiSettingsDao(appDatabase);
    final phraseItemDao = PhraseItemDao(appDatabase);
    final categoryItemDao = CategoryItemDao(appDatabase);
    final voiceDao = VoiceDao(appDatabase);
    // ...existing code...

    final saidTextDao = SaidTextDao(appDatabase);

    var uiSettings = await uiSettingsDao.getUiSettings();
    if (uiSettings == null) {
      uiSettings = UiSettings(name: 'default');
      await uiSettingsDao.insert(uiSettings);
    }

    final config = Hive.box('settings').get('config') as SpeechServiceConfig? ??
        SpeechServiceConfig(endpoint: '', key: '');

    final azureTts = AzureTts(
      subscriptionKey: config.key,
      region: config.endpoint,
      settingsBox: Hive.box('settings'),
      voiceBox: Hive.box('selectedVoice'),
      context: navigatorKey.currentContext, // Allow null at startup
      saidTextDao: saidTextDao,
    );

    final loadMainPageDataUseCase = LoadMainPageDataUseCase(
      phraseItemRepository: phraseItemDao,
      categoryRepository: categoryItemDao,
      settingsRepository: uiSettingsDao,
      uiSettingsRepository: uiSettingsDao,
      conversationRepository: saidTextDao,
    );
    final addPhraseUseCase = AddPhraseUseCase(
      phraseItemRepository: phraseItemDao,
      speechService: azureTts,
    );
    final deleteItemUseCase = DeleteItemUseCase(
      phraseItemRepository: phraseItemDao,
    );
    final reorderItemsUseCase = ReorderItemsUseCase(
      phraseItemRepository: phraseItemDao,
    );
    final selectFolderUseCase = SelectFolderUseCase(
      phraseItemRepository: phraseItemDao,
    );
    final playSpeechItemUseCase = PlaySpeechItemUseCase(
      speechService: azureTts,
    );
    final speakFromInputUseCase = SpeakFromInputUseCase(
      speechService: azureTts,
    );
    final stopPlaybackUseCase = StopPlaybackUseCase(
      speechService: azureTts,
    );
    final updatePrimaryLanguageUseCase = UpdatePrimaryLanguageUseCase(
      settingsRepository: uiSettingsDao,
    );
    final updateUiSettingsUseCase = UpdateUiSettingsUseCase(
      uiSettingsRepository: uiSettingsDao,
    );
    final addCategoryUseCase = AddCategoryUseCase(
      categoryRepository: categoryItemDao,
    );
    final updateSecondaryLanguageUseCase = UpdateSecondaryLanguageUseCase(
      settingsRepository: uiSettingsDao,
    );
    final togglePlayPauseUseCase = TogglePlayPauseUseCase(
      speechService: azureTts,
    );

    runApp(
      BlocProvider(
        create: (context) => MainPageBloc(
          loadMainPageDataUseCase: loadMainPageDataUseCase,
          addPhraseUseCase: addPhraseUseCase,
          deleteItemUseCase: deleteItemUseCase,
          reorderItemsUseCase: reorderItemsUseCase,
          selectFolderUseCase: selectFolderUseCase,
          playSpeechItemUseCase: playSpeechItemUseCase,
          speakFromInputUseCase: speakFromInputUseCase,
          stopPlaybackUseCase: stopPlaybackUseCase,
          updatePrimaryLanguageUseCase: updatePrimaryLanguageUseCase,
          updateUiSettingsUseCase: updateUiSettingsUseCase,
          addCategoryUseCase: addCategoryUseCase,
          updateSecondaryLanguageUseCase: updateSecondaryLanguageUseCase,
          togglePlayPauseUseCase: togglePlayPauseUseCase,
        )..add(LoadMainPage()),
        child: MyApp(
          speechServiceEndpoint: config.endpoint,
          speechServiceKey: config.key,
          uiSettings: uiSettings,
          onSaveSettings: (endpoint, key, newUiSettings) async {
            final box = Hive.box('settings');
            final config = SpeechServiceConfig(endpoint: endpoint, key: key);
            await box.put('config', config);
            await uiSettingsDao.saveUiSettings(newUiSettings);
          },
        ),
      ),
    );
  } catch (e, stack) {
    print('Error during app initialization: $e');
    print('Stack trace: $stack');
    runApp(_buildErrorApp(e.toString()));
  }
}

MaterialApp _buildErrorApp(String errorMessage) {
  return MaterialApp(
    home: Scaffold(
      body: Center(child: Text('Failed to initialize app: $errorMessage')),
    ),
  );
}

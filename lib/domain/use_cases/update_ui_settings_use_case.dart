import 'package:wingmate/domain/repositories/ui_settings_repository.dart';
import 'package:wingmate/domain/entities/ui_settings.dart';

class UpdateUiSettingsUseCase {
  final UiSettingsRepository uiSettingsRepository;

  UpdateUiSettingsUseCase({
    required this.uiSettingsRepository,
  });

  Future<void> execute(UiSettings uiSettings) async {
    await uiSettingsRepository.saveUiSettings(uiSettings);
  }
}

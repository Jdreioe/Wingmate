import 'package:wingmate/domain/entities/ui_settings.dart';

abstract class UiSettingsRepository {
  Future<UiSettings?> getUiSettings();
  Future<void> saveUiSettings(UiSettings uiSettings);
}

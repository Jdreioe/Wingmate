import 'package:wingmate/domain/entities/ui_settings.dart';

abstract class SettingsRepository {
  Future<UiSettings> getSettings();
  Future<void> updatePrimaryLanguage(String language);
  Future<void> saveSetting(String key, dynamic value);
}

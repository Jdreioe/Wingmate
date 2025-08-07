import 'package:hive/hive.dart';
import 'package:wingmate/domain/repositories/settings_repository.dart';

class SettingsRepositoryImpl implements SettingsRepository {
  final Box<dynamic> settingsBox;

  SettingsRepositoryImpl(this.settingsBox);

  @override
  Future<void> saveSetting(String key, dynamic value) async {
    await settingsBox.put(key, value);
  }

  @override
  Future<dynamic> getSetting(String key) async {
    return settingsBox.get(key);
  }
}

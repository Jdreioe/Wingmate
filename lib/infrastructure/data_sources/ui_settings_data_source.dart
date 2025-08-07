import 'package:wingmate/domain/entities/ui_settings.dart';
import 'package:wingmate/infrastructure/data/ui_settings_dao.dart';
import 'package:wingmate/infrastructure/models/ui_settings.dart' as infra_ui_settings;

abstract class UiSettingsDataSource {
  Future<UiSettings?> getUiSettings();
  Future<void> saveUiSettings(UiSettings uiSettings);
}

class UiSettingsLocalDataSource implements UiSettingsDataSource {
  final UiSettingsDao uiSettingsDao;

  UiSettingsLocalDataSource(this.uiSettingsDao);

  @override
  Future<UiSettings?> getUiSettings() async {
    // Assuming there's only one UI settings entry, or we fetch by a known name
    final uiSettingsItem = await uiSettingsDao.getByName('default'); // Or whatever name is used
    return uiSettingsItem?.toDomain();
  }

  @override
  Future<void> saveUiSettings(UiSettings uiSettings) async {
    final uiSettingsItem = infra_ui_settings.UiSettings.fromDomain(uiSettings);
    if (uiSettings.id == null) {
      await uiSettingsDao.insert(uiSettingsItem);
    } else {
      await uiSettingsDao.update(uiSettingsItem);
    }
  }
}

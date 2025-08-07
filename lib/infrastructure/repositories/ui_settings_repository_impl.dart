import 'package:wingmate/domain/entities/ui_settings.dart';
import 'package:wingmate/domain/repositories/ui_settings_repository.dart';
import 'package:wingmate/infrastructure/data_sources/ui_settings_data_source.dart';

class UiSettingsRepositoryImpl implements UiSettingsRepository {
  final UiSettingsLocalDataSource localDataSource;

  UiSettingsRepositoryImpl(this.localDataSource);

  @override
  Future<UiSettings?> getUiSettings() async {
    return await localDataSource.getUiSettings();
  }

  @override
  Future<void> saveUiSettings(UiSettings uiSettings) async {
    return await localDataSource.saveUiSettings(uiSettings);
  }
}

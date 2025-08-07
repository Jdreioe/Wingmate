import 'package:wingmate/core/usecase/usecase.dart';
import 'package:wingmate/domain/entities/ui_settings.dart';
import 'package:wingmate/domain/repositories/ui_settings_repository.dart';

class GetUiSettingsUseCase implements UseCase<UiSettings?, NoParams> {
  final UiSettingsRepository repository;

  GetUiSettingsUseCase(this.repository);

  @override
  Future<UiSettings?> call(NoParams params) async {
    return await repository.getUiSettings();
  }
}

class SaveUiSettingsUseCase implements UseCase<void, UiSettings> {
  final UiSettingsRepository repository;

  SaveUiSettingsUseCase(this.repository);

  @override
  Future<void> call(UiSettings uiSettings) async {
    return await repository.saveUiSettings(uiSettings);
  }
}

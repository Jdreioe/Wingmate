import 'package:wingmate/domain/repositories/settings_repository.dart';

class UpdateSecondaryLanguageUseCase {
  final SettingsRepository settingsRepository;

  UpdateSecondaryLanguageUseCase({
    required this.settingsRepository,
  });

  Future<void> execute(String language) async {
    await settingsRepository.saveSetting('secondaryLanguage', language);
  }
}

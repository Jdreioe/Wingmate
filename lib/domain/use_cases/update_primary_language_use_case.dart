import 'package:wingmate/domain/repositories/settings_repository.dart';

class UpdatePrimaryLanguageUseCase {
  final SettingsRepository settingsRepository;

  UpdatePrimaryLanguageUseCase({
    required this.settingsRepository,
  });

  Future<void> execute(String language) async {
    await settingsRepository.updatePrimaryLanguage(language);
  }
}

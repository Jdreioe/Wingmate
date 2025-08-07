import 'package:sqflite/sqflite.dart';
import 'package:wingmate/infrastructure/data/app_database.dart';
import 'package:wingmate/infrastructure/models/ui_settings.dart' as ui_settings_model;
import 'package:wingmate/domain/entities/ui_settings.dart' as domain_ui_settings;
import 'package:wingmate/domain/repositories/settings_repository.dart';
import 'package:wingmate/domain/repositories/ui_settings_repository.dart';

class UiSettingsDao implements SettingsRepository, UiSettingsRepository {
  @override
  Future<void> saveSetting(String key, dynamic value) async {
    final currentSettings = await getUiSettings();
    if (currentSettings != null) {
      if (key == 'primaryLanguage') {
        final updatedSettings = currentSettings.copyWith(primaryLanguage: value as String);
        await saveUiSettings(updatedSettings);
      } else if (key == 'secondaryLanguage') {
        final updatedSettings = currentSettings.copyWith(secondaryLanguage: value as String);
        await saveUiSettings(updatedSettings);
      }
      // Add more keys as needed
    } else {
      throw Exception('UI Settings not found, cannot save setting');
    }
  }
  final AppDatabase _appDatabase;

  UiSettingsDao(this._appDatabase);

  @override
  Future<domain_ui_settings.UiSettings> getSettings() async {
    final settings = await getUiSettings();
    if (settings == null) {
      // Handle the case where settings are not found, perhaps return a default or throw an error
      throw Exception("UI Settings not found");
    }
    return settings;
  }

  @override
  Future<void> updatePrimaryLanguage(String language) async {
    final currentSettings = await getUiSettings();
    if (currentSettings != null) {
      final updatedSettings = currentSettings.copyWith(primaryLanguage: language);
      await saveUiSettings(updatedSettings);
    } else {
      // Handle the case where settings are not found
      throw Exception("UI Settings not found, cannot update primary language");
    }
  }

  Future<void> insert(domain_ui_settings.UiSettings settings) async {
    final db = await _appDatabase.database;
    final model = ui_settings_model.UiSettings.fromDomain(settings);
    final id = await db.insert(
      'ui_settings',
      model.toMap(),
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
    // Update the ID of the domain entity if it was null
    if (settings.id == null) {
      // This requires a copyWith method in your domain entity
      // For now, we'll just update the model's ID
      model.id = id;
    }
  }

  Future<List<domain_ui_settings.UiSettings>> getAll() async {
    final db = await _appDatabase.database;
    final List<Map<String, dynamic>> maps = await db.query('ui_settings');
    return List.generate(maps.length, (i) {
      return ui_settings_model.UiSettings.fromMap(maps[i]).toDomain();
    });
  }

  Future<domain_ui_settings.UiSettings?> getUiSettings() async {
    final db = await _appDatabase.database;
    final List<Map<String, dynamic>> maps = await db.query(
      'ui_settings',
      where: 'name = ?',
      whereArgs: ['default'],
    );
    if (maps.isNotEmpty) {
      return ui_settings_model.UiSettings.fromMap(maps.first).toDomain();
    }
    return null;
  }

  Future<void> saveUiSettings(domain_ui_settings.UiSettings uiSettings) async {
    final db = await _appDatabase.database;
    final model = ui_settings_model.UiSettings.fromDomain(uiSettings);
    await db.update(
      'ui_settings',
      model.toMap(),
      where: 'id = ?',
      whereArgs: [model.id],
    );
  }

  Future<void> delete(int id) async {
    final db = await _appDatabase.database;
    await db.delete(
      'ui_settings',
      where: 'id = ?',
      whereArgs: [id],
    );
  }

  Future<void> deleteByName(String name) async {
    final db = await _appDatabase.database;
    await db.delete(
      'ui_settings',
      where: 'name = ?',
      whereArgs: [name],
    );
  }
}
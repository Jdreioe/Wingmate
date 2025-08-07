import 'package:wingmate/domain/models/user_profile.dart';
import 'package:wingmate/infrastructure/data/app_database.dart';
import 'package:sqflite/sqflite.dart';

class UserProfileDao {
  final AppDatabase _appDatabase;

  UserProfileDao(this._appDatabase);

  Future<int> insertProfile(UserProfile profile) async {
    final db = await _appDatabase.database;
    return await db.insert(
      'user_profiles',
      profile.toMap(),
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<UserProfile?> getProfileById(int id) async {
    final db = await _appDatabase.database;
    final List<Map<String, dynamic>> maps = await db.query(
      'user_profiles',
      where: 'id = ?',
      whereArgs: [id],
    );

    if (maps.isNotEmpty) {
      return UserProfile.fromMap(maps.first);
    } else {
      return null;
    }
  }

  Future<List<UserProfile>> getAllProfiles() async {
    final db = await _appDatabase.database;
    final List<Map<String, dynamic>> maps = await db.query('user_profiles');

    return List.generate(maps.length, (i) {
      return UserProfile.fromMap(maps[i]);
    });
  }

  Future<int> updateProfile(UserProfile profile) async {
    final db = await _appDatabase.database;
    return await db.update(
      'user_profiles',
      profile.toMap(),
      where: 'id = ?',
      whereArgs: [profile.id],
    );
  }

  Future<int> deleteProfile(int id) async {
    final db = await _appDatabase.database;
    return await db.delete(
      'user_profiles',
      where: 'id = ?',
      whereArgs: [id],
    );
  }
}

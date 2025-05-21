import 'package:flutter_test/flutter_test.dart';
import 'package:mockito/mockito.dart'; // We would use this if mocks could be generated
import 'package:sqflite/sqflite.dart';
import 'package:wingmate/models/user_profile.dart';
import 'package:wingmate/utils/app_database.dart';
import 'package:wingmate/utils/user_profile_dao.dart';

// Manual (Illustrative) Mock for AppDatabase - In real scenario, use @GenerateMocks
class MockAppDatabase extends Mock implements AppDatabase {
  final MockDatabase _mockDb;
  MockAppDatabase(this._mockDb);

  @override
  Future<Database> get database async => _mockDb;
}

// Manual (Illustrative) Mock for Database - In real scenario, use @GenerateMocks
class MockDatabase extends Mock implements Database {}

void main() {
  late UserProfileDao userProfileDao;
  late MockAppDatabase mockAppDatabase;
  late MockDatabase mockDatabase;

  setUp(() {
    mockDatabase = MockDatabase();
    mockAppDatabase = MockAppDatabase(mockDatabase);
    userProfileDao = UserProfileDao(mockAppDatabase);
  });

  final tUserProfile = UserProfile(
    id: 1,
    name: 'Test Profile',
    voiceName: 'test_voice',
    languageCode: 'en-US',
    speechRate: 1.0,
    pitch: 1.0,
  );

  final tUserProfileMap = {
    'id': 1,
    'name': 'Test Profile',
    'voiceName': 'test_voice',
    'languageCode': 'en-US',
    'speechRate': 1.0,
    'pitch': 1.0,
  };

  group('UserProfileDao', () {
    test('insertProfile calls db.insert with correct values and returns id', () async {
      // Arrange
      // Simulate that db.insert returns a new ID (e.g., 1)
      when(mockDatabase.insert(
        'user_profiles',
        tUserProfile.toMap()..remove('id'), // DAO typically inserts without ID if it's auto-increment
        conflictAlgorithm: ConflictAlgorithm.replace,
      )).thenAnswer((_) async => 1);
      
      // Act
      final profileToInsert = tUserProfile.copyWith(id: null); // DAO expects ID to be null for insertion
      final id = await userProfileDao.insertProfile(profileToInsert);

      // Assert
      expect(id, 1);
      verify(mockDatabase.insert(
        'user_profiles',
        profileToInsert.toMap(), // Verify with the map from the profile (which will have id: null)
        conflictAlgorithm: ConflictAlgorithm.replace,
      ));
    });

    test('getProfileById calls db.query and returns UserProfile if found', () async {
      // Arrange
      when(mockDatabase.query(
        'user_profiles',
        where: 'id = ?',
        whereArgs: [1],
      )).thenAnswer((_) async => [tUserProfileMap]);

      // Act
      final profile = await userProfileDao.getProfileById(1);

      // Assert
      expect(profile, isNotNull);
      expect(profile!.id, tUserProfile.id);
      expect(profile.name, tUserProfile.name);
      verify(mockDatabase.query('user_profiles', where: 'id = ?', whereArgs: [1]));
    });

    test('getProfileById returns null if not found', () async {
      // Arrange
      when(mockDatabase.query(
        'user_profiles',
        where: 'id = ?',
        whereArgs: [1],
      )).thenAnswer((_) async => []); // Empty list means not found

      // Act
      final profile = await userProfileDao.getProfileById(1);

      // Assert
      expect(profile, isNull);
      verify(mockDatabase.query('user_profiles', where: 'id = ?', whereArgs: [1]));
    });

    test('getAllProfiles calls db.query and returns a list of UserProfiles', () async {
      // Arrange
      final List<Map<String, dynamic>> mapList = [
        tUserProfileMap,
        {...tUserProfileMap, 'id': 2, 'name': 'Profile 2'},
      ];
      when(mockDatabase.query('user_profiles')).thenAnswer((_) async => mapList);

      // Act
      final profiles = await userProfileDao.getAllProfiles();

      // Assert
      expect(profiles.length, 2);
      expect(profiles[0].id, tUserProfileMap['id']);
      expect(profiles[1].name, 'Profile 2');
      verify(mockDatabase.query('user_profiles'));
    });
    
    test('getAllProfiles returns an empty list if no profiles found', () async {
      // Arrange
      when(mockDatabase.query('user_profiles')).thenAnswer((_) async => []);

      // Act
      final profiles = await userProfileDao.getAllProfiles();

      // Assert
      expect(profiles, isEmpty);
      verify(mockDatabase.query('user_profiles'));
    });

    test('updateProfile calls db.update with correct values', () async {
      // Arrange
      when(mockDatabase.update(
        'user_profiles',
        tUserProfileMap,
        where: 'id = ?',
        whereArgs: [tUserProfile.id],
      )).thenAnswer((_) async => 1); // 1 row affected

      // Act
      await userProfileDao.updateProfile(tUserProfile);

      // Assert
      verify(mockDatabase.update(
        'user_profiles',
        tUserProfileMap,
        where: 'id = ?',
        whereArgs: [tUserProfile.id],
      ));
    });

    test('deleteProfile calls db.delete with correct id', () async {
      // Arrange
      when(mockDatabase.delete(
        'user_profiles',
        where: 'id = ?',
        whereArgs: [1],
      )).thenAnswer((_) async => 1); // 1 row affected

      // Act
      await userProfileDao.deleteProfile(1);

      // Assert
      verify(mockDatabase.delete(
        'user_profiles',
        where: 'id = ?',
        whereArgs: [1],
      ));
    });
  });
}

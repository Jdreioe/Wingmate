import 'package:flutter_test/flutter_test.dart';
import 'package:wingmate/models/user_profile.dart';

void main() {
  group('UserProfile', () {
    final tUserProfile = UserProfile(
      id: 1,
      name: 'Test Profile',
      voiceName: 'test_voice',
      languageCode: 'en-US',
      speechRate: 1.0,
      pitch: 1.0,
    );

    test('constructor assigns fields correctly', () {
      expect(tUserProfile.id, 1);
      expect(tUserProfile.name, 'Test Profile');
      expect(tUserProfile.voiceName, 'test_voice');
      expect(tUserProfile.languageCode, 'en-US');
      expect(tUserProfile.speechRate, 1.0);
      expect(tUserProfile.pitch, 1.0);
    });

    test('copyWith creates a new instance with updated fields', () {
      final updatedProfile = tUserProfile.copyWith(
        name: 'Updated Profile',
        speechRate: 1.5,
      );

      expect(updatedProfile.id, tUserProfile.id);
      expect(updatedProfile.name, 'Updated Profile');
      expect(updatedProfile.voiceName, tUserProfile.voiceName);
      expect(updatedProfile.languageCode, tUserProfile.languageCode);
      expect(updatedProfile.speechRate, 1.5);
      expect(updatedProfile.pitch, tUserProfile.pitch);
      expect(updatedProfile == tUserProfile, isFalse); // Ensure it's a new instance
    });

    test('copyWith creates a new instance with no fields updated if none provided', () {
      final copiedProfile = tUserProfile.copyWith();

      expect(copiedProfile.id, tUserProfile.id);
      expect(copiedProfile.name, tUserProfile.name);
      expect(copiedProfile.voiceName, tUserProfile.voiceName);
      expect(copiedProfile.languageCode, tUserProfile.languageCode);
      expect(copiedProfile.speechRate, tUserProfile.speechRate);
      expect(copiedProfile.pitch, tUserProfile.pitch);
      expect(copiedProfile == tUserProfile, isFalse); // Still a new instance
                                                      // but content-wise it would be equal
      expect(copiedProfile.hashCode, tUserProfile.hashCode); // Hash should be same if fields are same
    });
    
    test('toMap produces a correct map', () {
      final map = tUserProfile.toMap();
      expect(map, {
        'id': 1,
        'name': 'Test Profile',
        'voiceName': 'test_voice',
        'languageCode': 'en-US',
        'speechRate': 1.0,
        'pitch': 1.0,
      });
    });

    test('fromMap reconstructs a UserProfile correctly', () {
      final map = {
        'id': 1,
        'name': 'Test Profile',
        'voiceName': 'test_voice',
        'languageCode': 'en-US',
        'speechRate': 1.0,
        'pitch': 1.0,
      };
      final profileFromMap = UserProfile.fromMap(map);

      expect(profileFromMap.id, 1);
      expect(profileFromMap.name, 'Test Profile');
      expect(profileFromMap.voiceName, 'test_voice');
      expect(profileFromMap.languageCode, 'en-US');
      expect(profileFromMap.speechRate, 1.0);
      expect(profileFromMap.pitch, 1.0);
    });

    test('fromMap handles null id correctly', () {
      final map = {
        'id': null,
        'name': 'Test Profile No ID',
        'voiceName': 'test_voice_noid',
        'languageCode': 'en-GB',
        'speechRate': 0.8,
        'pitch': 1.2,
      };
      final profileFromMap = UserProfile.fromMap(map);

      expect(profileFromMap.id, null);
      expect(profileFromMap.name, 'Test Profile No ID');
      expect(profileFromMap.speechRate, 0.8);
    });


    test('equality (==) and hashCode work correctly', () {
      final profile1 = UserProfile(
        id: 1,
        name: 'Profile A',
        voiceName: 'voice_a',
        languageCode: 'en-US',
        speechRate: 1.0,
        pitch: 1.0,
      );
      final profile2 = UserProfile(
        id: 1,
        name: 'Profile A',
        voiceName: 'voice_a',
        languageCode: 'en-US',
        speechRate: 1.0,
        pitch: 1.0,
      );
      final profile3 = UserProfile(
        id: 2, // Different ID
        name: 'Profile B',
        voiceName: 'voice_b',
        languageCode: 'en-GB',
        speechRate: 1.2,
        pitch: 0.8,
      );

      expect(profile1 == profile2, isTrue);
      expect(profile1.hashCode == profile2.hashCode, isTrue);

      expect(profile1 == profile3, isFalse);
      expect(profile1.hashCode == profile3.hashCode, isFalse);
      
      // Test with one field different
      final profile4 = profile1.copyWith(name: 'Profile C');
      expect(profile1 == profile4, isFalse);
      expect(profile1.hashCode == profile4.hashCode, isFalse);
    });

     test('toString returns a non-empty string containing class name and fields', () {
      final profileString = tUserProfile.toString();
      expect(profileString, contains('UserProfile'));
      expect(profileString, contains('id: ${tUserProfile.id}'));
      expect(profileString, contains('name: ${tUserProfile.name}'));
      expect(profileString, contains('voiceName: ${tUserProfile.voiceName}'));
      expect(profileString, contains('languageCode: ${tUserProfile.languageCode}'));
      expect(profileString, contains('speechRate: ${tUserProfile.speechRate}'));
      expect(profileString, contains('pitch: ${tUserProfile.pitch}'));
    });
  });
}

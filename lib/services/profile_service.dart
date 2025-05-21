import 'package:shared_preferences/shared_preferences.dart';
import '../models/user_profile.dart';
import '../utils/user_profile_dao.dart';

class ProfileService {
  final UserProfileDao _userProfileDao;
  static const String _activeProfileIdKey = 'active_profile_id';

  ProfileService(this._userProfileDao);

  Future<UserProfile> createProfile(UserProfile profile) async {
    final id = await _userProfileDao.insertProfile(profile);
    return profile.copyWith(id: id);
  }

  Future<List<UserProfile>> getProfiles() async {
    return await _userProfileDao.getAllProfiles();
  }

  Future<void> setActiveProfile(int profileId) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_activeProfileIdKey, profileId);
  }

  Future<UserProfile?> getActiveProfile() async {
    final prefs = await SharedPreferences.getInstance();
    final profileId = prefs.getInt(_activeProfileIdKey);
    if (profileId != null) {
      return await _userProfileDao.getProfileById(profileId);
    }
    return null;
  }

  Future<UserProfile?> getDefaultProfile() async {
    UserProfile? activeProfile = await getActiveProfile();
    if (activeProfile != null) {
      return activeProfile;
    }

    final profiles = await _userProfileDao.getAllProfiles();
    if (profiles.isNotEmpty) {
      // Attempt to find a profile named "Default"
      final defaultProfile = profiles.firstWhere(
        (p) => p.name.toLowerCase() == 'default',
        orElse: () => profiles.first, // Otherwise, return the first profile
      );
      if (defaultProfile.id != null) {
        await setActiveProfile(defaultProfile.id!);
      }
      return defaultProfile;
    }
    return null;
  }

  Future<void> updateProfile(UserProfile profile) async {
    await _userProfileDao.updateProfile(profile);
  }

  Future<void> deleteProfile(int profileId) async {
    await _userProfileDao.deleteProfile(profileId);
    final prefs = await SharedPreferences.getInstance();
    final activeId = prefs.getInt(_activeProfileIdKey);
    if (activeId == profileId) {
      await prefs.remove(_activeProfileIdKey);
    }
  }

  // Optional init method - not strictly necessary for this service yet
  Future<void> init() async {
    // Perform any initial setup if needed in the future
  }
}

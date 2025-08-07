import 'package:wingmate/domain/models/user_profile.dart';
import 'package:wingmate/infrastructure/data/user_profile_dao.dart';

abstract class UserProfileDataSource {
  Future<UserProfile?> getUserProfile();
  Future<void> saveUserProfile(UserProfile userProfile);
}

class UserProfileLocalDataSource implements UserProfileDataSource {
  final UserProfileDao userProfileDao;

  UserProfileLocalDataSource(this.userProfileDao);

  @override
  Future<UserProfile?> getUserProfile() async {
    // Assuming there's only one user profile, or we fetch by a known ID (e.g., 1)
    return await userProfileDao.getProfileById(1); // Or implement logic to get the current user's profile
  }

  @override
  Future<void> saveUserProfile(UserProfile userProfile) async {
    if (userProfile.id == null) {
      await userProfileDao.insertProfile(userProfile);
    } else {
      await userProfileDao.updateProfile(userProfile);
    }
  }
}

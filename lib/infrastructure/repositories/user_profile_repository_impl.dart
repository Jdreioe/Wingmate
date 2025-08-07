import 'package:wingmate/domain/models/user_profile.dart';
import 'package:wingmate/domain/repositories/user_profile_repository.dart';
import 'package:wingmate/infrastructure/data_sources/user_profile_data_source.dart';

class UserProfileRepositoryImpl implements UserProfileRepository {
  final UserProfileLocalDataSource localDataSource;

  UserProfileRepositoryImpl(this.localDataSource);

  @override
  Future<UserProfile?> getUserProfile() async {
    return await localDataSource.getUserProfile();
  }

  @override
  Future<void> saveUserProfile(UserProfile userProfile) async {
    return await localDataSource.saveUserProfile(userProfile);
  }
}
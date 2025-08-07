import 'package:wingmate/core/usecase/usecase.dart';
import 'package:wingmate/domain/models/user_profile.dart';
import 'package:wingmate/domain/repositories/user_profile_repository.dart';

class GetUserProfileUseCase implements UseCase<UserProfile?, NoParams> {
  final UserProfileRepository repository;

  GetUserProfileUseCase(this.repository);

  @override
  Future<UserProfile?> call(NoParams params) async {
    return await repository.getUserProfile();
  }
}

class SaveUserProfileUseCase implements UseCase<void, UserProfile> {
  final UserProfileRepository repository;

  SaveUserProfileUseCase(this.repository);

  @override
  Future<void> call(UserProfile userProfile) async {
    return await repository.saveUserProfile(userProfile);
  }
}

import 'package:mockito/annotations.dart';
import 'package:sqflite/sqflite.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:wingmate/infrastructure/data/app_database.dart';
import 'package:wingmate/infrastructure/data/user_profile_dao.dart';

// For UserProfileDao tests
@GenerateMocks([AppDatabase])
void daoMocks() {}

// For ProfileService tests
@GenerateNiceMocks([
  MockSpec<UserProfileDao>(),
  MockSpec<SharedPreferences>()
])
void serviceMocks() {}

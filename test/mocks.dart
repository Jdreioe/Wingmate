import 'package:mockito/annotations.dart';
import 'package:sqflite/sqflite.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:wingmate/utils/app_database.dart';
import 'package:wingmate/utils/user_profile_dao.dart';

// For UserProfileDao tests
@GenerateMocks([AppDatabase, Database])
void daoMocks() {}

// For ProfileService tests
@GenerateNiceMocks([
  MockSpec<UserProfileDao>(),
  MockSpec<SharedPreferences>()
])
void serviceMocks() {}

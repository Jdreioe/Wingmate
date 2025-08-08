import 'package:mockito/annotations.dart';
import 'package:wingmate/infrastructure/services/voice_service.dart';
import 'package:wingmate/infrastructure/data/app_database.dart';
import 'package:wingmate/infrastructure/data/voice_dao.dart';

@GenerateMocks([VoiceService, AppDatabase, VoiceDao])
class Mocks {}
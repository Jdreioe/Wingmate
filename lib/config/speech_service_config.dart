import 'package:hive/hive.dart';

@HiveType(typeId: 0)
class SpeechServiceConfig {
  @HiveField(0)
  final String endpoint;

  @HiveField(1)
  final String key;

  SpeechServiceConfig({
    required this.endpoint,
    required this.key,
  });
}

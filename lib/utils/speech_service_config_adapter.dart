import 'package:hive/hive.dart';
import 'package:wingmate/utils/speech_service_config.dart';

class SpeechServiceConfigAdapter extends TypeAdapter<SpeechServiceConfig> {
  @override
  final int typeId = 0;

  @override
  SpeechServiceConfig read(BinaryReader reader) {
    final endpoint = reader.readString();
    final key = reader.readString();
    return SpeechServiceConfig(endpoint: endpoint, key: key);
  }

  @override
  void write(BinaryWriter writer, SpeechServiceConfig obj) {
    writer.writeString(obj.endpoint);
    writer.writeString(obj.key);
  }
}

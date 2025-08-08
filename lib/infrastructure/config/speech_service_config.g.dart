// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'speech_service_config.dart';

// **************************************************************************
// TypeAdapterGenerator
// **************************************************************************

class SpeechServiceConfigAdapter extends TypeAdapter<SpeechServiceConfig> {
  @override
  final int typeId = 0;

  @override
  SpeechServiceConfig read(BinaryReader reader) {
    final numOfFields = reader.readByte();
    final fields = <int, dynamic>{
      for (int i = 0; i < numOfFields; i++) reader.readByte(): reader.read(),
    };
    return SpeechServiceConfig(
      endpoint: fields[0] as String,
      key: fields[1] as String,
    );
  }

  @override
  void write(BinaryWriter writer, SpeechServiceConfig obj) {
    writer
      ..writeByte(2)
      ..writeByte(0)
      ..write(obj.endpoint)
      ..writeByte(1)
      ..write(obj.key);
  }

  @override
  int get hashCode => typeId.hashCode;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is SpeechServiceConfigAdapter &&
          runtimeType == other.runtimeType &&
          typeId == other.typeId;
}

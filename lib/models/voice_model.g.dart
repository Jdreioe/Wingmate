// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'voice_model.dart';

// **************************************************************************
// TypeAdapterGenerator
// **************************************************************************

class VoiceAdapter extends TypeAdapter<Voice> {
  @override
  final int typeId = 1;

  @override
  @override
  Voice read(BinaryReader reader) {
    final numOfFields = reader.readByte();
    final fields = <int, dynamic>{
      for (int i = 0; i < numOfFields; i++) reader.readByte(): reader.read(),
    };
    return Voice(
      name: fields[0] as String? ?? '', // Provide a default value if null
      supportedLanguages: (fields[1] as List?)?.cast<String>() ??
          [], // Provide a default empty list if null
      selectedLanguage:
          fields[2] as String? ?? '', // Provide a default value if null
      pitch: fields[3] as double? ?? 1.0, // Provide a default value if null
      rate: fields[4] as double? ?? 1.0, // Provide a default value if null
    );
  }

  @override
  void write(BinaryWriter writer, Voice obj) {
    writer
      ..writeByte(5)
      ..writeByte(0)
      ..write(obj.name)
      ..writeByte(1)
      ..write(obj.supportedLanguages)
      ..writeByte(2)
      ..write(obj.selectedLanguage)
      ..writeByte(3)
      ..write(obj.pitch)
      ..writeByte(4)
      ..write(obj.rate);
  }

  @override
  int get hashCode => typeId.hashCode;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is VoiceAdapter &&
          runtimeType == other.runtimeType &&
          typeId == other.typeId;
}

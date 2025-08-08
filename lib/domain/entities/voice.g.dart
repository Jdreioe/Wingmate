// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'voice.dart';

// **************************************************************************
// TypeAdapterGenerator
// **************************************************************************

class VoiceAdapter extends TypeAdapter<Voice> {
  @override
  final int typeId = 1;

  @override
  Voice read(BinaryReader reader) {
    final numOfFields = reader.readByte();
    final fields = <int, dynamic>{
      for (int i = 0; i < numOfFields; i++) reader.readByte(): reader.read(),
    };
    return Voice(
      id: fields[0] as int?,
      name: fields[1] as String?,
      supportedLanguages: (fields[2] as List?)?.cast<String>(),
      gender: fields[3] as String?,
      primaryLanguage: fields[4] as String?,
      createdAt: fields[5] as int?,
      displayName: fields[6] as String?,
      selectedLanguage: fields[7] as String,
      pitch: fields[8] as double?,
      rate: fields[9] as double?,
      pitchForSSML: fields[10] as String?,
      rateForSSML: fields[11] as String?,
    );
  }

  @override
  void write(BinaryWriter writer, Voice obj) {
    writer
      ..writeByte(12)
      ..writeByte(0)
      ..write(obj.id)
      ..writeByte(1)
      ..write(obj.name)
      ..writeByte(2)
      ..write(obj.supportedLanguages)
      ..writeByte(3)
      ..write(obj.gender)
      ..writeByte(4)
      ..write(obj.primaryLanguage)
      ..writeByte(5)
      ..write(obj.createdAt)
      ..writeByte(6)
      ..write(obj.displayName)
      ..writeByte(7)
      ..write(obj.selectedLanguage)
      ..writeByte(8)
      ..write(obj.pitch)
      ..writeByte(9)
      ..write(obj.rate)
      ..writeByte(10)
      ..write(obj.pitchForSSML)
      ..writeByte(11)
      ..write(obj.rateForSSML);
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

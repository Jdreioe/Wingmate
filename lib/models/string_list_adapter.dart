import 'package:hive/hive.dart';
import 'package:wingmancrossplatform/models/string_list.dart';

class StringListAdapter extends TypeAdapter<StringList> {
  @override
  final int typeId = 0;

  @override
  StringList read(BinaryReader reader) {
    final numOfFields = reader.readByte();
    final fields = <int, dynamic>{
      for (int i = 0; i < numOfFields; i++) reader.readByte(): reader.read(),
    };
    return StringList(
      strings: List<String>.from(fields[0] as List), // Corrected List casting
    );
  }

  @override
  void write(BinaryWriter writer, StringList obj) {
    writer
      ..writeByte(1)
      ..writeByte(0)
      ..write(obj.strings);
  }

  @override
  int get hashCode => typeId.hashCode;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is StringListAdapter &&
          runtimeType == other.runtimeType &&
          typeId == other.typeId;
}

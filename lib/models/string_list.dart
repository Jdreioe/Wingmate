import 'package:hive/hive.dart';

@HiveType(typeId: 0)
class StringList extends HiveObject {
  @HiveField(0)
  List<String> strings;

  StringList({required this.strings});
}

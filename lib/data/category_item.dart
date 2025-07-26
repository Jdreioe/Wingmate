import 'package:flutter/material.dart';
import 'package:hive/hive.dart';

part 'category_item.g.dart';

@HiveType(typeId: 2) // Ensure unique typeId
class CategoryItem {
  @HiveField(0)
  int? id;

  @HiveField(1)
  String language;

  @HiveField(2)
  String name;

  @HiveField(3)
  String? color; // Stored as hex string

  @HiveField(4)
  int? iconCodePoint; // Stored as int from IconData.codePoint

  CategoryItem({
    this.id,
    required this.language,
    required this.name,
    this.color,
    this.iconCodePoint,
  });

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'language': language,
      'name': name,
      'color': color,
      'iconCodePoint': iconCodePoint,
    };
  }

  static CategoryItem fromMap(Map<String, dynamic> map) {
    return CategoryItem(
      id: map['id'],
      language: map['language'],
      name: map['name'],
      color: map['color'],
      iconCodePoint: map['iconCodePoint'],
    );
  }

  // Helper to convert hex string to Color object
  Color? get flutterColor {
    if (color == null) return null;
    return Color(int.parse(color!, radix: 16) | 0xFF000000);
  }

  // Helper to convert int to IconData object
  IconData? get flutterIcon {
    if (iconCodePoint == null) return null;
    return IconData(iconCodePoint!, fontFamily: 'MaterialIcons');
  }
}
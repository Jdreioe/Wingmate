
import 'package:equatable/equatable.dart';

class Category extends Equatable {
  final int? id;
  final String language;
  final String name;
  final String? color;
  final int? iconCodePoint;

  const Category({
    this.id,
    required this.language,
    required this.name,
    this.color,
    this.iconCodePoint,
  });

  @override
  List<Object?> get props => [
        id,
        language,
        name,
        color,
        iconCodePoint,
      ];
}

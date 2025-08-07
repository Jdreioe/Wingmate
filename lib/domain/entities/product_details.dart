
import 'package:equatable/equatable.dart';

class ProductDetails extends Equatable {
  final String id;
  final String title;
  final String description;
  final String price;
  final String currencyCode;

  const ProductDetails({
    required this.id,
    required this.title,
    required this.description,
    required this.price,
    required this.currencyCode,
  });

  @override
  List<Object?> get props => [
        id,
        title,
        description,
        price,
        currencyCode,
      ];
}

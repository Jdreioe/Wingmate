import 'package:wingmate/domain/entities/product_details.dart';

abstract class ProductDetailsRepository {
  Future<List<ProductDetails>> getProductDetails();
  Future<ProductDetails?> getProductDetailsById(String id);
  Future<void> saveProductDetails(ProductDetails productDetails);
  Future<void> deleteProductDetails(String id);
}

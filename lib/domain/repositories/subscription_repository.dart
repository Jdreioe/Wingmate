
import 'package:wingmate/domain/entities/product_details.dart';

abstract class SubscriptionRepository {
  Future<bool> isSubscribed();
  Future<List<ProductDetails>> getAvailableProducts();
  Future<void> buySubscription(ProductDetails productDetails);
  Future<void> restorePurchases();
}

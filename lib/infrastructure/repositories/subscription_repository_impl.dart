
import 'dart:async';

import 'package:in_app_purchase/in_app_purchase.dart' as iap;
import 'package:wingmate/domain/entities/product_details.dart' as domain_product_details;
import 'package:wingmate/domain/repositories/subscription_repository.dart';
import 'package:wingmate/infrastructure/services/subscription_manager.dart';

class SubscriptionRepositoryImpl implements SubscriptionRepository {
  final SubscriptionManager subscriptionManager;

  SubscriptionRepositoryImpl(this.subscriptionManager);

  @override
  Future<List<domain_product_details.ProductDetails>> getAvailableProducts() async {
    final products = await subscriptionManager.getProducts();
    return products.map((e) => domain_product_details.ProductDetails(
      id: e.id,
      title: e.title,
      description: e.description,
      price: e.price,
      currencyCode: e.currencyCode,
    )).toList();
  }

  @override
  Future<bool> isSubscribed() async {
    // This needs to be implemented based on your subscription logic
    // For now, returning false as a placeholder
    return false;
  }

  @override
  Future<void> buySubscription(domain_product_details.ProductDetails productDetails) async {
    final product = iap.ProductDetails(
      id: productDetails.id,
      title: productDetails.title,
      description: productDetails.description,
      price: productDetails.price,
      rawPrice: double.parse(productDetails.price.replaceAll(RegExp(r'[^0-9.]'), '')),
      currencyCode: productDetails.currencyCode,
    );
    await subscriptionManager.buySubscription(product);
  }

  @override
  Future<void> restorePurchases() async {
    await subscriptionManager.restorePurchases();
  }
}

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:in_app_purchase/in_app_purchase.dart';

class SubscriptionManager {
  final void Function(bool isSubscribed) onSubscriptionStatusChanged;
  late final InAppPurchase _inAppPurchase;
  StreamSubscription<List<PurchaseDetails>>? _subscription;
  List<ProductDetails> _products = [];

  SubscriptionManager({required this.onSubscriptionStatusChanged});

  void initialize() {
    _inAppPurchase = InAppPurchase.instance;
    _initializeInAppPurchase();
    _fetchProducts();
  }

  void _initializeInAppPurchase() {
    final purchaseUpdated = _inAppPurchase.purchaseStream;
    _subscription = purchaseUpdated.listen(_listenToPurchaseUpdated, onDone: () => _subscription?.cancel(), onError: (error) {
      // handle error here.
    });
  }

  Future<void> _fetchProducts() async {
    const Set<String> _kIds = {'azuremanager'};
    final ProductDetailsResponse response = await _inAppPurchase.queryProductDetails(_kIds);
    if (response.notFoundIDs.isNotEmpty) {
      // Handle the error.
      debugPrint('Product not found: ${response.notFoundIDs}');
    }
    _products = response.productDetails;
  }

  void _listenToPurchaseUpdated(List<PurchaseDetails> purchaseDetailsList) {
    for (var purchaseDetails in purchaseDetailsList) {
      if (purchaseDetails.status == PurchaseStatus.purchased) {
        _verifyPurchase(purchaseDetails);
      }
    }
  }

  Future<void> _verifyPurchase(PurchaseDetails purchaseDetails) async {
    // Verify the purchase with your server and unlock the subscription
    onSubscriptionStatusChanged(true);
  }

  void showSubscriptionDialog(BuildContext context) {
    showDialog(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: Text('Subscribe'),
          content: Text('Subscribe to get access to the Subscription Key and region.'),
          actions: [
            TextButton(
              onPressed: () {
                Navigator.of(context).pop();
              },
              child: Text('Cancel'),
            ),
            TextButton(
              onPressed: () {
                _buySubscription();
                Navigator.of(context).pop();
              },
              child: Text('Subscribe'),
            ),
          ],
        );
      },
    );
  }

  void _buySubscription() {
    if (_products.isEmpty) {
      debugPrint('Products not fetched yet.');
      return;
    }
    final productDetails = _products.firstWhere((product) => product.id == 'azuremanager');
    final purchaseParam = PurchaseParam(productDetails: productDetails);
    _inAppPurchase.buyNonConsumable(purchaseParam: purchaseParam);
  }

  void dispose() {
    _subscription?.cancel();
  }
}

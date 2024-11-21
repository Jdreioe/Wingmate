package com.hoejmoseit.wingman.wingmanapp.backgroundtask;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.google.common.collect.ImmutableList;
import com.hoejmoseit.wingman.wingmanapp.FirstTimeLaunchDialog;
import com.hoejmoseit.wingman.wingmanapp.MainActivity;

import java.util.List;
import java.util.ArrayList;


public class InAppPurchaseManager {

	private BillingClient billingClient;

	private final PurchasesUpdatedListener purchasesUpdatedListener = new PurchasesUpdatedListener() {
		@Override
		public void onPurchasesUpdated(@NonNull BillingResult billingResult, @Nullable List<Purchase> purchases) {
			System.out.println(billingResult);
			if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
				for (Purchase purchase : purchases) {
					// Handle the purchase
					// ... (e.g., acknowledge purchase, grant entitlement) ...
				}
			} else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
				// Handle user cancellation
				// ...
			} else {
				// Handle other error codes
				// ...
			}
		}
	};

	// ... other methods ...
	public void initializeBillingClient(Context context) {
		billingClient = BillingClient.newBuilder(context)
				.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts()  .build())
				.setListener(purchasesUpdatedListener).build();

		billingClient.startConnection(new BillingClientStateListener() {
			@Override
			public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
				if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
					// The BillingClient is ready. You can query purchases here.
					queryAzureSubscriptionDetails();

				} else {
					// Handle error
				}
			}

			@Override
			public void onBillingServiceDisconnected() {
				// Try to restart the connection on the next request to
				// Google Play by calling the startConnection() method.
			}
		});
	}

	private void queryAzureSubscriptionDetails() {
		List<QueryProductDetailsParams.Product> productList = new ArrayList<>();
		productList.add(
				QueryProductDetailsParams.Product.newBuilder()
						.setProductId("azuremanager") // Replace with your actual product ID
						.setProductType(BillingClient.ProductType.SUBS)
						.build()
		);


		// 2. Query for product details
		QueryProductDetailsParams queryProductDetailsParams = QueryProductDetailsParams.newBuilder()
				.setProductList(
						ImmutableList.of(
								QueryProductDetailsParams.Product.newBuilder()
										.setProductId("azuremanager")
										.setProductType(BillingClient.ProductType.SUBS)
										.build()))
				.build();

		billingClient.queryProductDetailsAsync(
				queryProductDetailsParams,
				new ProductDetailsResponseListener() {
					public void onProductDetailsResponse(BillingResult billingResult,
					                                     List<ProductDetails> productDetailsList) {
						System.out.println(productDetailsList.get(0).getOneTimePurchaseOfferDetails());
						// check billingResult
						// process returned productDetailsList


					}

					public void queryProductDetails(List<String> productIds, ProductDetailsListener listener, Context context) {
						// ... (code for querying ProductDetails using billingClient) ...

						List<QueryProductDetailsParams.Product> productList = new ArrayList<>();
						productList.add(
								QueryProductDetailsParams.Product.newBuilder()
										.setProductId("azuremanager") // Replace with your actual product ID
										.setProductType(BillingClient.ProductType.SUBS) // Or BillingClient.ProductType.INAPP

										.build()
						);

						QueryProductDetailsParams queryProductDetailsParams =
								QueryProductDetailsParams.newBuilder()
										.setProductList(productList)
										.build();

						billingClient.queryProductDetailsAsync(
								queryProductDetailsParams,
								(billingResult, productDetailsList) -> {
									if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK
											&& !productDetailsList.isEmpty()) {
										listener.onProductDetailsReceived(productDetailsList);
									} else {
										listener.onProductDetailsQueryFailed(billingResult);
									}
								}
						);
					}


					// ... other methods ...


				});
	}
}
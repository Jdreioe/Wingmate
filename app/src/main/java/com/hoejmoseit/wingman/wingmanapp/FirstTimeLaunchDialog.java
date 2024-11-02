package com.hoejmoseit.wingman.wingmanapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.hoejmoseit.wingman.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;



public class FirstTimeLaunchDialog {
	private static boolean isFirstLaunch = true;
	private static BillingClient billingClient;
	private static List<SkuDetails> skuDetailsList;


	public static void showFirstTimeLaunchDialog(Context context) {
		SharedPreferences sharedPrefs = context.getSharedPreferences("MyPrefs", Context.MODE_PRIVATE);
		boolean hasLaunchedBefore = sharedPrefs.getBoolean("has_launched_before", false);



		if (!hasLaunchedBefore && sharedPrefs.getString("sub_key", "def") == "def") {
			MaterialAlertDialogBuilder materialDialogBuilder = new MaterialAlertDialogBuilder(context);
			LayoutInflater inflater = LayoutInflater.from(context);
			View dialogView = inflater.inflate(R.layout.first_time_launch, null);
			final EditText subkeyInput = dialogView.findViewById(R.id.subkey_input);
			final EditText subLocalInput = dialogView.findViewById(R.id.sub_local_input);

			materialDialogBuilder.setView(dialogView)
					.setPositiveButton("Save", (dialog, which) -> {
						String subkey = subkeyInput.getText().toString();
						String subLocal = subLocalInput.getText().toString();

						SharedPreferences.Editor editor = sharedPrefs.edit();
						editor.putString("sub_key", subkey);
						editor.putString("sub_locale", subLocal);
						editor.putBoolean("has_launched_before", true);
						editor.commit(); // Use apply() instead of commit()

					})
					.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
					.show();
			dialogView.findViewById(R.id.subButton).setOnClickListener(v -> {
				BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()

						.build();
				BillingClient billingClient  = BillingClient.newBuilder(context)
						.enablePendingPurchases()
						.build();
				billingClient.startConnection(new BillingClientStateListener() {
					@Override
					public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
						if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
							// Billing client is ready, query for SKU details
							querySkuDetails(billingClient);
						} else {
							// Handle billing setup failure
							// ...
						}
					}
					@Override
					public void onBillingServiceDisconnected() {
						// Handle billing service disconnection
						// ...
					}
				});




				billingClient.startConnection(new BillingClientStateListener() {
					@Override
					public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
						if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
							// Billing client is ready, query for SKU details
							querySkuDetails(billingClient);
						} else {
							// Handle billing setup failure
							// ...
						}
					}

					@Override
					public void onBillingServiceDisconnected() {
						// Handle billing service disconnection
						// ...
					}
				});
			});
		}

	}
	private static void querySkuDetails(BillingClient billingClient) {


		SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();


		billingClient.querySkuDetailsAsync(params.build(), (billingResult, skuDetailsList) -> {
			if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && skuDetailsList != null) {
				// SKU details retrieved successfully, store them or use them as needed
				// ...
			} else {
				// Handle SKU details query failure
				// ...
			}
		});

	}

	public static BillingClient getBillingClient() {return billingClient;}
	public static List<SkuDetails> getSkuDetailsList() {return skuDetailsList;}
	public static void purchasesUpdatedListener () {

	}
}


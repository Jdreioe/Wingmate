package com.hoejmoseit.wingman.wingmanapp;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.hoejmoseit.wingman.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.collect.ImmutableList;
import com.hoejmoseit.wingman.wingmanapp.backgroundtask.InAppPurchaseManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;



public class FirstTimeLaunchDialog {
	private static boolean isFirstLaunch = true;
	private static BillingClient billingClient;
	private static List<SkuDetails> skuDetailsList;


	public static void showFirstTimeLaunchDialog(Context context) {
		SharedPreferences sharedPrefs = context.getSharedPreferences("MyPrefs", Context.MODE_PRIVATE);
		boolean hasLaunchedBefore = sharedPrefs.getBoolean("has_launched_before", false);


		if (sharedPrefs.getString("sub_key", "") == "") {
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
					.setCancelable(false)
					.show();
			dialogView.findViewById(R.id.subButton).setOnClickListener(v -> {
				Toast.makeText(context, R.string.not_yet_implemented_will_cost_5_99_usd_49_dkk_it_will_hopefully_be_cheap_enough, Toast.LENGTH_SHORT).show();
				InAppPurchaseManager purchaseManager = new InAppPurchaseManager();
				List<String> productIds = Arrays.asList("azuremanager");
			});
		}

	}
}
package com.hoejmoseit.wingman.wingmanapp.backgroundtask;

import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;

import java.util.List;

public interface ProductDetailsListener {

		void onProductDetailsReceived(List<ProductDetails> productDetailsList);

		void onProductDetailsQueryFailed(BillingResult billingResult);
	}

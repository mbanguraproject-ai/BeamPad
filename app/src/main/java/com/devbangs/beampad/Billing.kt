package com.devbangs.beampad

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * One-time purchase to remove ads.
 *
 * The entitlement is only ever granted here, never revoked: a paying user
 * must not see ads because the connection was slow or the device offline.
 */
class Billing(
    context: Context,
    private val entitlements: Entitlements,
    private val onEntitlementChanged: () -> Unit
) {

    var priceText: String? = null
        private set

    private var details: ProductDetails? = null

    private val purchasesUpdated = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { handle(it) }
        }
    }

    private val client = BillingClient.newBuilder(context)
        .setListener(purchasesUpdated)
        .enablePendingPurchases(
            com.android.billingclient.api.PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                queryProduct()
                restore()
            }

            override fun onBillingServiceDisconnected() {
                // Auto reconnection is enabled; nothing to do here.
            }
        })
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(Entitlements.PRODUCT_REMOVE_ADS)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        client.queryProductDetailsAsync(params) { result, response ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val product = response.productDetailsList.firstOrNull() ?: return@queryProductDetailsAsync
            details = product
            priceText = product.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    /** Re-checks Play for an existing purchase. Safe to call on every launch. */
    fun restore() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            purchases.forEach { handle(it) }
        }
    }

    fun launch(activity: Activity): Boolean {
        val product = details ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .build()
                )
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun handle(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!purchase.products.contains(Entitlements.PRODUCT_REMOVE_ADS)) return

        if (!entitlements.adsRemoved) {
            entitlements.adsRemoved = true
            onEntitlementChanged()
        }

        // Unacknowledged purchases are refunded by Play after three days.
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client.acknowledgePurchase(params) { }
        }
    }

    fun stop() = client.endConnection()
}

package com.devbangs.beampad

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Pro (subscription or lifetime) and the original remove-ads purchase.
 *
 * Remove ads is no longer sold from the app, since Pro includes it, but
 * existing purchases are still restored and acknowledged here.
 *
 * Plans are built from whatever Play returns, so a base plan or the lifetime
 * product that has not been created in Play Console simply does not appear
 * on the paywall rather than breaking it.
 */
class Billing(
    context: Context,
    private val entitlements: Entitlements,
    private val onEntitlementChanged: (Entitlements.Change) -> Unit
) {

    enum class Period { WEEKLY, MONTHLY, YEARLY, LIFETIME }

    /** One option on the paywall, resolved to the exact offer that will be bought. */
    class Plan(
        val period: Period,
        val product: ProductDetails,
        val offerToken: String?,
        val price: String,
        val priceMicros: Long,
        val currencyCode: String,
        /** ISO 8601 length of a free trial such as P3D, or null for none. */
        val freeTrial: String?
    )

    enum class Outcome { PURCHASED, PENDING, CANCELLED, FAILED }

    /** Paywall options, shortest period first. Only touched on the main thread. */
    var plans: List<Plan> = emptyList()
        private set

    /** True once Play has answered at least one product query. */
    var plansLoaded = false
        private set

    /** Paywall hooks, always invoked on the main thread. */
    var onPlansChanged: (() -> Unit)? = null
    var onPurchaseOutcome: ((Outcome) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var subPlans: List<Plan> = emptyList()
    private var lifetimePlan: Plan? = null

    private val purchasesUpdated = PurchasesUpdatedListener { result, purchases ->
        val outcome = when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases.orEmpty().forEach { handle(it) }
                val pending = purchases.orEmpty()
                    .any { it.purchaseState == Purchase.PurchaseState.PENDING }
                if (pending) Outcome.PENDING else Outcome.PURCHASED
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Outcome.CANCELLED
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                restore()
                Outcome.PURCHASED
            }
            else -> Outcome.FAILED
        }
        main.post { onPurchaseOutcome?.invoke(outcome) }
    }

    private val client = BillingClient.newBuilder(context)
        .setListener(purchasesUpdated)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                queryProducts()
                restore()
            }

            override fun onBillingServiceDisconnected() {
                // Auto reconnection is enabled; nothing to do here.
            }
        })
    }

    private fun queryProducts() {
        query(BillingClient.ProductType.SUBS, Entitlements.PRODUCT_PRO_SUB) { list ->
            val built = list.flatMap { subscriptionPlans(it) }
            main.post {
                subPlans = built
                publishPlans()
            }
        }
        query(BillingClient.ProductType.INAPP, Entitlements.PRODUCT_PRO_LIFETIME) { list ->
            val built = list.firstOrNull()?.let { lifetime(it) }
            main.post {
                lifetimePlan = built
                publishPlans()
            }
        }
    }

    private fun publishPlans() {
        plans = subPlans.sortedBy { it.period.ordinal } + listOfNotNull(lifetimePlan)
        plansLoaded = true
        onPlansChanged?.invoke()
    }

    private fun query(type: String, productId: String, done: (List<ProductDetails>) -> Unit) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(type)
                        .build()
                )
            )
            .build()

        client.queryProductDetailsAsync(params) { result, response ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                main.post {
                    plansLoaded = true
                    onPlansChanged?.invoke()
                }
                return@queryProductDetailsAsync
            }
            done(response.productDetailsList)
        }
    }

    /**
     * One plan per base plan. Play only returns offers this user is eligible
     * for, so a free-trial offer, when present, is the one to show; otherwise
     * the base plan itself. The billing period decides weekly, monthly or
     * yearly, so base plan IDs in Play Console can be named freely.
     */
    private fun subscriptionPlans(product: ProductDetails): List<Plan> {
        val offers = product.subscriptionOfferDetails ?: return emptyList()
        return offers.groupBy { it.basePlanId }.values.mapNotNull { forBase ->
            val offer = forBase.firstOrNull { o ->
                o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
            } ?: forBase.firstOrNull { it.offerId == null } ?: forBase.first()

            val phases = offer.pricingPhases.pricingPhaseList
            val recurring = phases.lastOrNull() ?: return@mapNotNull null
            val period = when (recurring.billingPeriod) {
                "P1W", "P7D" -> Period.WEEKLY
                "P1M", "P4W" -> Period.MONTHLY
                "P1Y", "P12M" -> Period.YEARLY
                else -> return@mapNotNull null
            }
            Plan(
                period = period,
                product = product,
                offerToken = offer.offerToken,
                price = recurring.formattedPrice,
                priceMicros = recurring.priceAmountMicros,
                currencyCode = recurring.priceCurrencyCode,
                freeTrial = phases.firstOrNull { it.priceAmountMicros == 0L }?.billingPeriod
            )
        }
    }

    private fun lifetime(product: ProductDetails): Plan? {
        val offer = product.oneTimePurchaseOfferDetails ?: return null
        return Plan(
            period = Period.LIFETIME,
            product = product,
            offerToken = null,
            price = offer.formattedPrice,
            priceMicros = offer.priceAmountMicros,
            currencyCode = offer.priceCurrencyCode,
            freeTrial = null
        )
    }

    private var lastRefresh = 0L

    /**
     * Re-checks purchases and, if they never arrived, the plans. Called as
     * screens return to the foreground, so a lapsed subscription ends Pro
     * without a restart and plans appear once Play is reachable again
     * after an offline launch. At most once a minute.
     */
    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (lastRefresh != 0L && now - lastRefresh < REFRESH_INTERVAL_MS) return
        lastRefresh = now
        restore()
        if (plans.isEmpty()) queryProducts()
    }

    /**
     * Re-checks Play for existing purchases. Safe to call on every launch.
     * [onDone] receives whether Play answered both queries.
     */
    fun restore(onDone: ((Boolean) -> Unit)? = null) {
        queryPurchases(BillingClient.ProductType.INAPP) { inappOk, inapp ->
            inapp.forEach { handle(it) }
            queryPurchases(BillingClient.ProductType.SUBS) { subsOk, subs ->
                subs.forEach { handle(it) }
                if (subsOk) revokeLapsedSubscription(subs)
                if (onDone != null) main.post { onDone(inappOk && subsOk) }
            }
        }
    }

    private fun queryPurchases(type: String, done: (Boolean, List<Purchase>) -> Unit) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(type)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            val ok = result.responseCode == BillingClient.BillingResponseCode.OK
            done(ok, if (ok) purchases else emptyList())
        }
    }

    /** Only after a successful answer: an error must never cost a subscriber Pro. */
    private fun revokeLapsedSubscription(subs: List<Purchase>) {
        val active = subs.any {
            it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                it.products.contains(Entitlements.PRODUCT_PRO_SUB)
        }
        if (active || !entitlements.proSubscription) return
        entitlements.proSubscription = false
        if (!entitlements.isPro) notify(Entitlements.Change.PRO_ENDED)
    }

    fun launch(activity: Activity, plan: Plan): Boolean {
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(plan.product)
        plan.offerToken?.let { productParams.setOfferToken(it) }

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams.build()))
            .build()
        val result = client.launchBillingFlow(activity, params)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun handle(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val products = purchase.products
        val known = products.any {
            it == Entitlements.PRODUCT_REMOVE_ADS ||
                it == Entitlements.PRODUCT_PRO_LIFETIME ||
                it == Entitlements.PRODUCT_PRO_SUB
        }
        if (!known) return

        val wasPro = entitlements.isPro
        val hadNoAds = entitlements.adsRemoved

        if (products.contains(Entitlements.PRODUCT_REMOVE_ADS) && !entitlements.removeAdsPurchased) {
            entitlements.removeAdsPurchased = true
        }
        if (products.contains(Entitlements.PRODUCT_PRO_LIFETIME) && !entitlements.proLifetime) {
            entitlements.proLifetime = true
        }
        if (products.contains(Entitlements.PRODUCT_PRO_SUB) && !entitlements.proSubscription) {
            entitlements.proSubscription = true
        }

        when {
            !wasPro && entitlements.isPro -> notify(Entitlements.Change.PRO_STARTED)
            !hadNoAds && entitlements.adsRemoved -> notify(Entitlements.Change.ADS_REMOVED)
        }

        // Unacknowledged purchases are refunded by Play after three days.
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client.acknowledgePurchase(params) { }
        }
    }

    private fun notify(change: Entitlements.Change) {
        main.post { onEntitlementChanged(change) }
    }

    fun stop() = client.endConnection()

    private companion object {
        const val REFRESH_INTERVAL_MS = 60_000L
    }
}

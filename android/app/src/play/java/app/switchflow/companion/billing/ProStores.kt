package app.switchflow.companion.billing

import android.app.Activity
import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ProStores {
    fun create(context: Context): ProStore = PlayProStore(context.applicationContext)
}

/**
 * Google Play Billing: one-time product [PRODUCT] ("SwitchFlow Pro"), created in Play Console.
 * The unlock is remembered on the phone so it works offline; Play is asked again on every start.
 */
private class PlayProStore(private val context: Context) : ProStore, PurchasesUpdatedListener {
    companion object {
        const val PRODUCT = "switchflow_pro"
    }

    private val prefs = context.getSharedPreferences("switchflow_pro", Context.MODE_PRIVATE)
    private val _pro = MutableStateFlow(prefs.getBoolean("pro", false))
    private val _price = MutableStateFlow<String?>(null)
    private val _busy = MutableStateFlow(false)
    override val pro: StateFlow<Boolean> = _pro
    override val price: StateFlow<String?> = _price
    override val busy: StateFlow<Boolean> = _busy

    @Volatile private var details: ProductDetails? = null

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override fun start() {
        if (client.isReady) {
            refresh()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) refresh()
            }

            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun refresh() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { result, found ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val d = found.productDetailsList.firstOrNull { it.productId == PRODUCT } ?: return@queryProductDetailsAsync
            details = d
            _price.value = d.oneTimePurchaseOfferDetails?.formattedPrice
        }
        restore()
    }

    override fun buy(activity: Activity) {
        val d = details ?: run { start(); return }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
            .build()
        _busy.value = true
        val r = client.launchBillingFlow(activity, params)
        if (r.responseCode != BillingClient.BillingResponseCode.OK) _busy.value = false
    }

    override fun restore() {
        if (!client.isReady) return
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases, fromRestore = true)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        _busy.value = false
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) handle(purchases, fromRestore = false)
        if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) restore()
    }

    private fun handle(purchases: List<Purchase>, fromRestore: Boolean) {
        val owned = purchases.filter { PRODUCT in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        for (p in owned) {
            // Unacknowledged purchases are refunded by Play after three days.
            if (!p.isAcknowledged) {
                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) {}
            }
        }
        // A restore that finds nothing means the purchase was refunded: lock again.
        if (owned.isNotEmpty() || fromRestore) set(owned.isNotEmpty())
    }

    private fun set(on: Boolean) {
        _pro.value = on
        prefs.edit().putBoolean("pro", on).apply()
    }
}

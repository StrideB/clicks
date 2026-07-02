package com.fran.clicks

import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// Play product ID — swap this for the real one when ready to go live
const val PRO_PRODUCT_ID = "com.fran.clicks.pro"

// Features that require Pro
enum class ProFeature(val label: String) {
    GEMINI_FULL_CONTEXT("Gemini full context"),
    GEMINI_SMART_COMPOSE("Gemini smart compose"),
    SPOTIFY_LIBRARY("Spotify library"),
    SKEUO_THEME("Skeuo keyboard theme"),
    AI_CHAT("AI chat"),
    TRAVEL_SEARCH("Flights & boarding passes"),
}

object ProManager {

    private const val PREF_DEV_PRO = "dev_pro_unlocked"
    private const val PREF_PURCHASE_TOKEN = "pro_purchase_token"

    // ── Status ───────────────────────────────────────────────────────────────

    fun isUnlocked(context: Context): Boolean = isDev(context) || hasPro(context)

    fun isDev(context: Context): Boolean =
        context.getSharedPreferences("pro", Context.MODE_PRIVATE)
            .getBoolean(PREF_DEV_PRO, false)

    fun hasPro(context: Context): Boolean =
        !context.getSharedPreferences("pro", Context.MODE_PRIVATE)
            .getString(PREF_PURCHASE_TOKEN, null).isNullOrBlank()

    // ── Dev unlock (secret code typed in launcher search) ────────────────────

    fun activateDev(context: Context) {
        context.getSharedPreferences("pro", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_DEV_PRO, true).apply()
    }

    fun deactivateDev(context: Context) {
        context.getSharedPreferences("pro", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_DEV_PRO, false).apply()
    }

    // ── Purchase persistence ─────────────────────────────────────────────────

    fun savePurchase(context: Context, token: String) {
        context.getSharedPreferences("pro", Context.MODE_PRIVATE)
            .edit().putString(PREF_PURCHASE_TOKEN, token).apply()
    }

    fun clearPurchase(context: Context) {
        context.getSharedPreferences("pro", Context.MODE_PRIVATE)
            .edit().remove(PREF_PURCHASE_TOKEN).apply()
    }

    // ── Billing ──────────────────────────────────────────────────────────────

    fun buildBillingClient(context: Context, onPurchaseVerified: (String) -> Unit): BillingClient {
        return BillingClient.newBuilder(context)
            .setListener { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    purchases.forEach { purchase ->
                        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                            purchase.products.contains(PRO_PRODUCT_ID)) {
                            savePurchase(context, purchase.purchaseToken)
                            onPurchaseVerified(purchase.purchaseToken)
                        }
                    }
                }
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
    }

    suspend fun restorePurchases(billingClient: BillingClient, context: Context): Boolean {
        if (!billingClient.isReady) return false
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val result = suspendCancellableCoroutine<PurchasesResult> { cont ->
            billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
                cont.resume(PurchasesResult(billingResult, purchases))
            }
        }
        val active = result.purchasesList.find {
            it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                it.products.contains(PRO_PRODUCT_ID)
        }
        if (active != null) {
            savePurchase(context, active.purchaseToken)
            return true
        }
        return false
    }

    suspend fun launchBillingFlow(
        billingClient: BillingClient,
        activity: android.app.Activity
    ): BillingResult {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRO_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        val queryParams = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        val productDetails = suspendCancellableCoroutine<ProductDetails?> { cont ->
            billingClient.queryProductDetailsAsync(queryParams) { _, detailsList ->
                cont.resume(detailsList.firstOrNull())
            }
        } ?: return BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE).build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .build()
            )).build()

        return billingClient.launchBillingFlow(activity, flowParams)
    }
}

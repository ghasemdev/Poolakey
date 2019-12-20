package com.phelat.poolakey

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ServiceConnection
import android.os.IBinder
import androidx.fragment.app.Fragment
import com.android.vending.billing.IInAppBillingService
import com.phelat.poolakey.billing.BillingFunction
import com.phelat.poolakey.billing.consume.ConsumeFunctionRequest
import com.phelat.poolakey.billing.purchase.PurchaseFunctionRequest
import com.phelat.poolakey.billing.query.QueryFunctionRequest
import com.phelat.poolakey.callback.ConnectionCallback
import com.phelat.poolakey.callback.ConsumeCallback
import com.phelat.poolakey.callback.PurchaseIntentCallback
import com.phelat.poolakey.callback.PurchaseQueryCallback
import com.phelat.poolakey.config.PaymentConfiguration
import com.phelat.poolakey.constant.BazaarIntent
import com.phelat.poolakey.constant.Billing
import com.phelat.poolakey.exception.BazaarNotFoundException
import com.phelat.poolakey.exception.DisconnectException
import com.phelat.poolakey.exception.IAPNotSupportedException
import com.phelat.poolakey.exception.SubsNotSupportedException
import com.phelat.poolakey.request.PurchaseRequest
import com.phelat.poolakey.thread.PoolakeyThread

internal class BillingConnection(
    private val context: Context,
    private val paymentConfiguration: PaymentConfiguration,
    private val backgroundThread: PoolakeyThread<Runnable>,
    private val purchaseFunction: BillingFunction<PurchaseFunctionRequest>,
    private val consumeFunction: BillingFunction<ConsumeFunctionRequest>,
    private val queryFunction: BillingFunction<QueryFunctionRequest>
) : ServiceConnection {

    private var callback: ConnectionCallback? = null

    private var billingService: IInAppBillingService? = null

    internal fun startConnection(connectionCallback: ConnectionCallback.() -> Unit): Connection {
        callback = ConnectionCallback(disconnect = ::stopConnection).apply(connectionCallback)
        Intent(BILLING_SERVICE_ACTION).apply { `package` = BAZAAR_PACKAGE_NAME }
            .takeIf(
                thisIsTrue = {
                    context.packageManager.queryIntentServices(it, 0).isNullOrEmpty().not()
                },
                andIfNot = {
                    callback?.connectionFailed?.invoke(BazaarNotFoundException())
                }
            )
            ?.also {
                try {
                    context.bindService(it, this, Context.BIND_AUTO_CREATE)
                } catch (e: SecurityException) {
                    callback?.connectionFailed?.invoke(e)
                }
            }
        return requireNotNull(callback)
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        IInAppBillingService.Stub.asInterface(service)
            ?.takeIf(
                thisIsTrue = {
                    isPurchaseTypeSupported(
                        purchaseType = PurchaseType.IN_APP,
                        inAppBillingService = it
                    )
                },
                andIfNot = {
                    callback?.connectionFailed?.invoke(IAPNotSupportedException())
                }
            )
            ?.takeIf(
                thisIsTrue = {
                    !paymentConfiguration.shouldSupportSubscription || isPurchaseTypeSupported(
                        purchaseType = PurchaseType.SUBSCRIPTION,
                        inAppBillingService = it
                    )
                },
                andIfNot = {
                    callback?.connectionFailed?.invoke(SubsNotSupportedException())
                }
            )
            ?.also { billingService = it }
            ?.also { callback?.connectionSucceed?.invoke() }
    }

    private fun isPurchaseTypeSupported(
        purchaseType: PurchaseType,
        inAppBillingService: IInAppBillingService
    ): Boolean {
        val supportState = inAppBillingService.isBillingSupported(
            Billing.IN_APP_BILLING_VERSION,
            context.packageName,
            purchaseType.type
        )
        return supportState == BazaarIntent.RESPONSE_RESULT_OK
    }

    fun purchase(
        activity: Activity,
        purchaseRequest: PurchaseRequest,
        purchaseType: PurchaseType,
        callback: PurchaseIntentCallback.() -> Unit
    ) {
        purchase(purchaseRequest, purchaseType, callback) { intentSender ->
            activity.startIntentSenderForResult(
                intentSender,
                purchaseRequest.requestCode,
                Intent(),
                0,
                0,
                0
            )
            PurchaseIntentCallback().apply(callback).purchaseFlowBegan.invoke()
        }
    }

    fun purchase(
        fragment: Fragment,
        purchaseRequest: PurchaseRequest,
        purchaseType: PurchaseType,
        callback: PurchaseIntentCallback.() -> Unit
    ) {
        purchase(purchaseRequest, purchaseType, callback) { intentSender ->
            fragment.startIntentSenderForResult(
                intentSender,
                purchaseRequest.requestCode,
                Intent(),
                0,
                0,
                0,
                null
            )
            PurchaseIntentCallback().apply(callback).purchaseFlowBegan.invoke()
        }
    }

    private fun purchase(
        purchaseRequest: PurchaseRequest,
        purchaseType: PurchaseType,
        callback: PurchaseIntentCallback.() -> Unit,
        fireIntent: (IntentSender) -> Unit
    ) = withService {
        purchaseFunction.function(
            billingService = this,
            request = PurchaseFunctionRequest(purchaseRequest, purchaseType, callback, fireIntent)
        )
    } ifServiceIsDisconnected {
        PurchaseIntentCallback().apply(callback).failedToBeginFlow.invoke(DisconnectException())
    }

    fun consume(
        purchaseToken: String,
        callback: ConsumeCallback.() -> Unit
    ) = withService(runOnBackground = true) {
        consumeFunction.function(
            billingService = this,
            request = ConsumeFunctionRequest(purchaseToken, callback)
        )
    } ifServiceIsDisconnected {
        ConsumeCallback().apply(callback).consumeFailed.invoke(DisconnectException())
    }

    fun queryPurchasedProducts(
        purchaseType: PurchaseType,
        callback: PurchaseQueryCallback.() -> Unit
    ) = withService(runOnBackground = true) {
        queryFunction.function(
            billingService = this,
            request = QueryFunctionRequest(purchaseType, callback)
        )
    } ifServiceIsDisconnected {
        PurchaseQueryCallback().apply(callback).queryFailed.invoke(DisconnectException())
    }

    private fun stopConnection() {
        if (billingService != null) {
            context.unbindService(this)
            disconnect()
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        disconnect()
    }

    private fun disconnect() {
        billingService = null
        callback?.disconnected?.invoke()
        callback = null
        backgroundThread.dispose()
    }

    private inline fun withService(
        runOnBackground: Boolean = false,
        crossinline service: IInAppBillingService.() -> Unit
    ): ConnectionState {
        return billingService?.also {
            if (runOnBackground) {
                backgroundThread.execute(Runnable { service.invoke(it) })
            } else {
                service.invoke(it)
            }
        }?.let { ConnectionState.Connected }
            ?: run { ConnectionState.Disconnected }
    }

    private inline infix fun ConnectionState.ifServiceIsDisconnected(block: () -> Unit) {
        if (this is ConnectionState.Disconnected) {
            block.invoke()
        }
    }

    companion object {
        private const val BILLING_SERVICE_ACTION = "ir.cafebazaar.pardakht.InAppBillingService.BIND"
        private const val BAZAAR_PACKAGE_NAME = "com.farsitel.bazaar"
    }
}

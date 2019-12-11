package vip.qsos.im.lib

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build

import vip.qsos.im.lib.constant.CIMConstant
import vip.qsos.im.lib.model.Message
import vip.qsos.im.lib.model.ReplyBody
import vip.qsos.im.lib.model.SentBody

/**
 * 消息入口，所有消息都会经过这里
 */
abstract class CIMEventBroadcastReceiver : BroadcastReceiver() {
    private lateinit var context: Context

    override fun onReceive(context: Context, intent: Intent) {
        this.context = context
        /*操作事件广播，用于提高 PushService 存活率*/
        if (intent.action == Intent.ACTION_USER_PRESENT
            || intent.action == Intent.ACTION_POWER_CONNECTED
            || intent.action == Intent.ACTION_POWER_DISCONNECTED
        ) {
            startPushService()
        }

        /*设备网络状态变化事件*/
        if (
            intent.action == CIMConstant.IntentAction.ACTION_NETWORK_CHANGED
            || intent.action == ConnectivityManager.CONNECTIVITY_ACTION
        ) {
            onDevicesNetworkChanged()
        }

        /*断开服务器事件*/
        if (intent.action == CIMConstant.IntentAction.ACTION_CONNECTION_CLOSED) {
            onInnerConnectionClosed()
        }

        /*连接服务器失败事件*/
        if (intent.action == CIMConstant.IntentAction.ACTION_CONNECTION_FAILED) {
            val interval = intent.getLongExtra("interval", CIMConstant.RECONN_INTERVAL_TIME)
            onConnectionFailed(interval)
        }

        /*连接服务器成功事件*/
        if (intent.action == CIMConstant.IntentAction.ACTION_CONNECTION_SUCCESSED) {
            onInnerConnectionSuccessed()
        }

        /*收到推送消息事件*/
        if (intent.action == CIMConstant.IntentAction.ACTION_MESSAGE_RECEIVED) {
            onInnerMessageReceived(
                intent.getSerializableExtra(Message::class.java.name) as Message,
                intent
            )
        }

        /*获取收到 ReplyBody 成功事件*/
        if (intent.action == CIMConstant.IntentAction.ACTION_REPLY_RECEIVED) {
            onReplyReceived(intent.getSerializableExtra(ReplyBody::class.java.name) as ReplyBody)
        }


        /*获取 SentBody 发送成功事件*/
        if (intent.action == CIMConstant.IntentAction.ACTION_SENT_SUCCESSED) {
            onSentSucceed(intent.getSerializableExtra(SentBody::class.java.name) as SentBody)
        }

        /*重新连接，如果断开的话*/
        if (intent.action == CIMConstant.IntentAction.ACTION_CONNECTION_RECOVERY) {
            connect(0)
        }
    }

    private fun startPushService() {
        val intent = Intent(context, CIMPushService::class.java)
        intent.action = CIMPushManager.ACTION_ACTIVATE_PUSH_SERVICE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun onInnerConnectionClosed() {
        CIMCacheManager.putBoolean(context, CIMCacheManager.KEY_CIM_CONNECTION_STATE, false)
        if (CIMPushManager.isNetworkConnected(context)) {
            connect(0)
        }
        onConnectionClosed()
    }

    private fun onConnectionFailed(reinterval: Long) {
        if (CIMPushManager.isNetworkConnected(context)) {
            onConnectionFailed()
            connect(reinterval)
        }
    }

    private fun onInnerConnectionSuccessed() {
        CIMCacheManager.putBoolean(context, CIMCacheManager.KEY_CIM_CONNECTION_STATE, true)
        val autoBind = CIMPushManager.autoBindAccount(context)
        onConnectionSuccessed(autoBind)
    }

    private fun onDevicesNetworkChanged() {
        if (CIMPushManager.isNetworkConnected(context)) {
            connect(0)
        }
        onNetworkChanged()
    }

    private fun connect(delay: Long) {
        val serviceIntent = Intent(context, CIMPushService::class.java)
        serviceIntent.putExtra(CIMPushService.KEY_DELAYED_TIME, delay)
        serviceIntent.action = CIMPushManager.ACTION_CREATE_CIM_CONNECTION
        CIMPushManager.startService(context, serviceIntent)
    }

    private fun onInnerMessageReceived(message: Message, intent: Intent) {
        if (isForceOfflineMessage(message.action)) {
            CIMPushManager.stop(context)
        }
        onMessageReceived(message, intent)
    }

    private fun isForceOfflineMessage(action: String): Boolean {
        return CIMConstant.MessageAction.ACTION_999 == action
    }

    abstract fun onMessageReceived(message: Message, intent: Intent)

    fun onNetworkChanged() {
        CIMListenerManager.notifyOnNetworkChanged(CIMPushManager.getNetworkInfo(context)!!)
    }

    open fun onConnectionSuccessed(hasAutoBind: Boolean) {
        CIMListenerManager.notifyOnConnectionSuccessed(hasAutoBind)
    }

    open fun onConnectionClosed() {
        CIMListenerManager.notifyOnConnectionClosed()
    }

    open fun onConnectionFailed() {
        CIMListenerManager.notifyOnConnectionFailed()
    }

    open fun onReplyReceived(body: ReplyBody) {
        CIMListenerManager.notifyOnReplyReceived(body)
    }

    fun onSentSucceed(body: SentBody) {
        CIMListenerManager.notifyOnSentSucceed(body)
    }
}
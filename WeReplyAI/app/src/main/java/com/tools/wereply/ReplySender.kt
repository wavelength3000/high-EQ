package com.tools.wereply

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 回复不是"模拟点击微信"，而是用通知自带的快捷回复通道
 * （Notification.Action + RemoteInput）把文本交回给微信自己去发。
 * 全程系统 API，不碰微信进程。
 */
object ReplySender {

    private const val TAG = "ReplySender"

    /** notification key -> 快捷回复 action。进程被杀会丢，属于正常损耗。 */
    private val registry = ConcurrentHashMap<String, Notification.Action>()

    /**
     * 微信的回复 action 有时挂在 notification.actions 上，
     * 有时只在 WearableExtender 里（不同版本、不同 ROM 行为不一致），两边都找一遍。
     */
    fun extractReplyAction(sbn: StatusBarNotification): Notification.Action? {
        val n = sbn.notification

        n.actions?.forEach { a ->
            if (!a.remoteInputs.isNullOrEmpty()) return a
        }

        runCatching {
            Notification.WearableExtender(n).actions.forEach { a ->
                if (!a.remoteInputs.isNullOrEmpty()) return a
            }
        }

        return null
    }

    fun remember(key: String, action: Notification.Action) {
        if (registry.size > 200) registry.clear()
        registry[key] = action
    }

    fun forget(key: String) {
        registry.remove(key)
    }

    /** 返回 true 表示已经交给微信去发了 */
    fun send(ctx: Context, key: String, text: String): Boolean {
        val action = registry[key] ?: run {
            Log.w(TAG, "找不到回复通道，通知可能已经被清掉了：$key")
            return false
        }
        val inputs = action.remoteInputs ?: return false

        return try {
            val intent = Intent()
            val bundle = Bundle()
            inputs.forEach { bundle.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, intent, bundle)
            // 有些版本要求带上这个 flag，否则微信会把消息当成语音回复处理
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            action.actionIntent.send(ctx, 0, intent)
            registry.remove(key)
            true
        } catch (e: Exception) {
            Log.e(TAG, "发送失败", e)
            false
        }
    }
}

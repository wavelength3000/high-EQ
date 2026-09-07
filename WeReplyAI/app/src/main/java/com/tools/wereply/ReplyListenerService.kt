package com.tools.wereply

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.random.Random

class ReplyListenerService : NotificationListenerService() {

    companion object {
        const val PKG_WECHAT = "com.tencent.mm"
        const val PKG_DOUYIN = "com.ss.android.ugc.aweme"
        private const val TAG = "ReplyListener"

        /** 合并通知、系统提示这些抓不到内容，直接跳过 */
        private val NOISE = listOf(
            "条新消息", "位联系人", "微信支付", "正在运行", "语音通话",
            "视频通话", "[图片]", "[语音]", "[视频]", "[文件]", "[链接]",
            "[转账]", "[红包]", "[位置]", "拍了拍"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handled = HashSet<String>()

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        SuggestionNotifier.ensureChannels(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.enabled) return
        if (Prefs.useAccessibility) return  // 无障碍模式接管，通知模式让路

        val pkg = sbn.packageName
        val watching = (pkg == PKG_WECHAT && Prefs.watchWeChat) ||
            (pkg == PKG_DOUYIN && Prefs.watchDouyin)
        if (!watching) return

        // 自己发的、以及本 App 自己推的通知不要处理
        if (pkg == packageName) return

        val extras = sbn.notification.extras
        val contact = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

        if (contact.isEmpty() || body.isEmpty()) return

        // 同一条通知可能被 post 多次（微信刷新未读数），去重
        val dedupe = "$contact|$body"
        synchronized(handled) {
            if (!handled.add(dedupe)) return
            if (handled.size > 300) handled.clear()
        }

        if (NOISE.any { body.contains(it) || contact.contains(it) }) return

        // 群聊：微信群消息的正文是 "昵称: 内容"，一律不碰。
        // 只认前 12 个字以内的冒号，避免把正常聊天里的冒号误判成群消息。
        val colon = listOf(body.indexOf("："), body.indexOf(": "))
            .filter { it > 0 }.minOrNull() ?: -1
        if (colon in 1..12) {
            Log.d(TAG, "疑似群消息，跳过")
            return
        }

        if (!Prefs.allowContact(contact)) return

        val action = ReplySender.extractReplyAction(sbn)
        if (action == null) {
            Log.d(TAG, "这条通知没有快捷回复通道")
            return
        }
        ReplySender.remember(sbn.key, action)

        ContextStore.append(this, contact, "them", body)

        // 敏感话题：不生成，只提醒
        val hit = SensitiveFilter.checkIncoming(body)
        if (hit != null) {
            SuggestionNotifier.showAlert(this, contact, "涉及「$hit」，AI 不接这类话题：$body")
            return
        }

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (Prefs.inQuietHours(hour)) return
        if (!Prefs.consumeQuota()) {
            Log.d(TAG, "本小时已达上限")
            return
        }

        scope.launch { handle(sbn.key, contact, body) }
    }

    private suspend fun handle(key: String, contact: String, body: String) {
        val history = ContextStore.load(this, contact).dropLast(1)

        val raw = try {
            AiClient.suggest(contact, history, body, Prefs.activeToneSpecs())
        } catch (e: Exception) {
            SuggestionNotifier.showAlert(this, contact, "AI 调用失败：${e.message}")
            return
        }

        val candidates = SensitiveFilter.filterSuggestions(raw)
        if (candidates.isEmpty()) {
            SuggestionNotifier.showAlert(this, contact, "生成的内容都被规则拦下了，这条自己回")
            return
        }

        // 尖酸这类风格不参与全自动 —— 让机器自己决定要不要呛人，出事概率太高
        val autoPool = candidates.filter { it.autoSendable }

        if (Prefs.autoSend && autoPool.isNotEmpty()) {
            // 随机延迟：秒回是风控最明显的特征
            val lo = Prefs.minDelaySec.coerceAtLeast(0)
            val hi = Prefs.maxDelaySec.coerceAtLeast(lo + 1)
            delay(Random.nextInt(lo, hi) * 1000L)

            val pick = autoPool.random()
            if (ReplySender.send(this, key, pick.text)) {
                ContextStore.append(this, contact, "me", pick.text)
            } else {
                SuggestionNotifier.showSuggestions(this, key, contact, body, candidates)
            }
        } else {
            SuggestionNotifier.showSuggestions(this, key, contact, body, candidates)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        ReplySender.forget(sbn.key)
    }
}

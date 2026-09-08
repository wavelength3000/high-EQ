package com.tools.wereply

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 无障碍版。跟通知版的区别：
 *   - 能读到屏幕上的完整聊天记录，上下文质量高很多
 *   - 抖音私信也能覆盖（抖音的通知不一定带快捷回复通道）
 *   - 代价是行为特征更明显，风险比通知版高
 *
 * 刻意没做的事：不遍历会话列表、不批量群发、不自动加好友、不自动通过好友申请。
 * 这个服务只在你自己打开某个聊天窗口时工作，是被动的。
 * 那些批量能力属于群控工具的范畴，封号最快，性质也完全不同。
 */
class ChatAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ChatA11y"
        var instance: ChatAccessibilityService? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var panel: FloatingPanel? = null

    private var currentContact: String = ""
    private var lastIncoming: String = ""
    private var busy = false
    private var lastScanAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        instance = this
        panel = FloatingPanel(this).apply {
            onBubbleClick = { onBubbleTapped() }
            onPick = { sug -> fillAndMaybeSend(sug.text) }
        }
        Log.d(TAG, "无障碍服务已连接")
    }

    override fun onDestroy() {
        panel?.destroy()
        panel = null
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!Prefs.enabled || !Prefs.useAccessibility) {
            panel?.hideBubble()
            return
        }

        val pkg = event?.packageName?.toString() ?: return
        val watching = (pkg == ReplyListenerService.PKG_WECHAT && Prefs.watchWeChat) ||
            (pkg == ReplyListenerService.PKG_DOUYIN && Prefs.watchDouyin)
        if (!watching) {
            panel?.hideBubble()
            return
        }

        // 内容变化事件在滚动时每秒能来几十个，整棵树遍历一遍不便宜，节流
        val now = System.currentTimeMillis()
        if (now - lastScanAt < 800) return
        lastScanAt = now

        val root = rootInActiveWindow ?: return
        val dm = resources.displayMetrics

        // 判定「当前是不是一个聊天窗口」：有输入框 + 有消息列表 + 标题栏有昵称
        val input = NodeUtils.findInput(root)
        val title = NodeUtils.extractTitle(root, dm.heightPixels)
        val msgs = NodeUtils.extractMessages(root, dm.widthPixels)

        if (input == null || title.isBlank() || msgs.isEmpty()) {
            panel?.hideBubble()
            return
        }

        if (!Prefs.allowContact(title)) {
            panel?.hideBubble()
            return
        }

        currentContact = title

        // 最后一条是对方发的，才有回复的必要
        val last = msgs.lastOrNull() ?: return
        if (last.fromMe) {
            panel?.hideBubble()
            return
        }

        lastIncoming = last.text

        // 把屏幕上读到的记录同步进上下文库（比通知版准得多）
        syncContext(title, msgs)

        panel?.showBubble()
        panel?.setBubbleText("AI")

        if (Prefs.autoSend && !busy) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (!Prefs.inQuietHours(hour) && Prefs.consumeQuota()) {
                generate(auto = true)
            }
        }
    }

    /** 屏幕上读到的记录覆盖式写回，避免重复累加 */
    private fun syncContext(contact: String, msgs: List<NodeUtils.ChatMsg>) {
        val known = ContextStore.load(this, contact).map { it.text }.toSet()
        msgs.takeLast(8).forEach { m ->
            if (m.text !in known) {
                ContextStore.append(this, contact, if (m.fromMe) "me" else "them", m.text)
            }
        }
    }

    private fun onBubbleTapped() {
        if (busy) return
        generate(auto = false)
    }

    private fun generate(auto: Boolean) {
        val contact = currentContact
        val incoming = lastIncoming
        if (contact.isBlank() || incoming.isBlank()) return

        // 敏感话题：不生成，只把球变成提示
        val hit = SensitiveFilter.checkIncoming(incoming)
        if (hit != null) {
            panel?.setBubbleText("!")
            if (!auto) {
                panel?.showNotice("这条涉及「$hit」，AI 不接这类话题，你自己回")
            }
            return
        }

        busy = true
        panel?.setBubbleText("...")

        scope.launch {
            val history = ContextStore.load(this@ChatAccessibilityService, contact).dropLast(1)
            val raw = try {
                AiClient.suggest(contact, history, incoming, Prefs.activeToneSpecs())
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    panel?.setBubbleText("×")
                    if (!auto) panel?.showNotice("调用失败：${e.message}")
                }
                busy = false
                return@launch
            }

            val candidates = SensitiveFilter.filterSuggestions(raw)
            withContext(Dispatchers.Main) { panel?.setBubbleText("AI") }

            if (candidates.isEmpty()) {
                withContext(Dispatchers.Main) {
                    if (!auto) panel?.showNotice("生成的内容都被规则拦下了，这条自己回")
                }
                busy = false
                return@launch
            }

            // 尖酸这类风格不参与全自动
            val autoPool = candidates.filter { it.autoSendable }

            if (auto && autoPool.isNotEmpty()) {
                val lo = Prefs.minDelaySec.coerceAtLeast(0)
                val hi = Prefs.maxDelaySec.coerceAtLeast(lo + 1)
                delay((lo until hi).random() * 1000L)
                withContext(Dispatchers.Main) { fillAndMaybeSend(autoPool.random().text) }
            } else {
                withContext(Dispatchers.Main) { panel?.showCandidates(candidates) }
            }
            busy = false
        }
    }

    /**
     * 把文本填进输入框。
     * 是否顺手点发送由 Prefs.autoClickSend 决定，默认关着 —— 填好了你自己按发送，
     * 这样既留了一道人工闸门，行为特征也最不像机器。
     */
    private fun fillAndMaybeSend(text: String) {
        if (!SensitiveFilter.isOutgoingSafe(text)) return

        val root = rootInActiveWindow ?: return
        val input = NodeUtils.findInput(root) ?: return

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            Log.w(TAG, "填字失败，这个输入框不吃 ACTION_SET_TEXT")
            return
        }

        ContextStore.append(this, currentContact, "me", text)

        if (!Prefs.autoClickSend) return

        // 微信的发送键要输入框有内容后才渲染出来，得重新取一次根节点
        scope.launch {
            delay(400)
            withContext(Dispatchers.Main) {
                val fresh = rootInActiveWindow ?: return@withContext
                NodeUtils.findSendButton(fresh)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }
}

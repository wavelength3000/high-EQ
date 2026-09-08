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
        // 只有自动模式才需要自己的悬浮球；剪贴板模式下本服务纯粹当按需读屏器用，
        // 球归 ClipboardBallService 管，这里什么都不建。
        if (Prefs.useAccessibility) {
            panel = FloatingPanel(this).apply {
                onBubbleClick = { onBubbleTapped() }
                onPick = { sug -> fillAndMaybeSend(sug.text) }
            }
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

    // ==================== 按需接口 ====================
    // 剪贴板模式下悬浮球会调这几个方法。不点球就完全不动，
    // 没有自动监听、没有自动发送。

    /** 当前聊天页的一次完整快照 */
    data class ChatSnapshot(
        /** 聊天对象昵称，读不到就是空串 */
        val title: String,
        /** 最后一条对方消息之前的全部对话，已按屏幕顺序排好 */
        val history: List<ContextStore.Turn>,
        /** 要回复的那条 */
        val lastIncoming: String,
        /** 屏幕上可见的对话总条数 */
        val total: Int,
        /** 这条你已经回过了（最后一条是你自己发的） */
        val alreadyReplied: Boolean
    )

    /** 读屏结果。失败时带上具体卡在哪一步，方便你在球上直接看到，而不是静默降级。 */
    sealed class ReadResult {
        data class Ok(val snapshot: ChatSnapshot) : ReadResult()
        data class Failed(val reason: String) : ReadResult()
    }

    /**
     * 读整屏对话，不是只读最后一条。
     * 上下文越全，AI 越知道你们聊到哪儿了、语气该怎么接。
     */
    fun readChat(): ReadResult {
        val root = rootInActiveWindow
            ?: return ReadResult.Failed(
                "取不到当前窗口。可能刚开完权限还没生效，去设置里把无障碍关掉再打开一次。"
            )

        val dm = resources.displayMetrics
        val pkg = root.packageName?.toString().orEmpty()

        val msgs = NodeUtils.extractMessages(root, dm.widthPixels, dm.heightPixels)
        if (msgs.isEmpty()) {
            return ReadResult.Failed(
                "在「$pkg」里没找到聊天记录。确认你是在某个人的聊天页里长按的，" +
                    "不是在消息列表页或者朋友圈。"
            )
        }

        val idx = msgs.indexOfLast { !it.fromMe }
        if (idx < 0) {
            return ReadResult.Failed(
                "读到 ${msgs.size} 条，但都判成你自己发的了。往上滑一点让对方的消息露出来再试。"
            )
        }

        val history = msgs.subList(0, idx).map {
            ContextStore.Turn(if (it.fromMe) "me" else "them", it.text)
        }

        return ReadResult.Ok(
            ChatSnapshot(
                title = NodeUtils.extractTitle(root, dm.heightPixels),
                history = history,
                lastIncoming = msgs[idx].text,
                total = msgs.size,
                alreadyReplied = idx < msgs.size - 1
            )
        )
    }

    /** 自检：把这一屏读成什么样原样吐出来 */
    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return "拿不到当前窗口。去把无障碍关掉再打开一次。"
        val dm = resources.displayMetrics
        return NodeUtils.dump(root, dm.widthPixels, dm.heightPixels)
    }

    /** 把文本填进当前页面的输入框。返回是否填成功。 */
    fun fillInput(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        // 微信切到「按住 说话」时页面上没有可编辑节点，这里会是 null
        val input = NodeUtils.findInput(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 剪贴板模式下本服务只做按需读屏，事件一律不处理
        if (!Prefs.useAccessibility) return
        if (!Prefs.enabled) {
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
        val msgs = NodeUtils.extractMessages(root, dm.widthPixels, dm.heightPixels)

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

        panel?.showBubble()
        panel?.setBubbleText("AI")

        if (Prefs.autoSend && !busy) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (!Prefs.inQuietHours(hour) && Prefs.consumeQuota()) {
                generate(auto = true)
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

        busy = true
        panel?.setBubbleText("...")

        scope.launch {
            // 屏幕就是上下文，不用自己攒 —— 跟悬浮球那条路走同一套
            val history = (readChat() as? ReadResult.Ok)?.snapshot?.history ?: emptyList()
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

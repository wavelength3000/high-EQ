package com.tools.wereply

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 最简模式：一个悬浮球，读剪贴板，出候选，选中后写回剪贴板。
 *
 * 只要两个权限：悬浮窗 + 通知（通知是前台服务必须的，Android 13+ 还要用户点同意）。
 * 不碰微信、不读通知、不用无障碍，微信那边看不到任何异常，封号风险为零。
 *
 * 用法：微信里长按对方消息「复制」→ 点悬浮球 → 挑一条 → 长按输入框粘贴。
 */
class ClipboardBallService : Service() {

    companion object {
        const val ACTION_STOP = "com.tools.wereply.STOP_BALL"
        private const val CHANNEL = "ball"
        private const val NOTIF_ID = 1001

        /** 上下文都存在这个固定 key 下，换人聊天时清掉 */
        private const val CTX_KEY = "clipboard"

        @Volatile
        var running = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var panel: FloatingPanel? = null
    private var busy = false
    private var lastRead = ""

    /** 这一轮上下文是从屏幕读的还是从剪贴板来的 */
    private var fromScreen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        startForegroundSelf()

        panel = FloatingPanel(this).apply {
            onBubbleClick = { onBallTapped() }
            onBubbleLongClick = { onBallLongPressed() }
            onPanelFocused = { onFocusReady() }
            onPick = { sug -> pick(sug) }
            onClearContext = {
                ContextStore.clear(this@ClipboardBallService, CTX_KEY)
                lastRead = ""
                toast("上下文清空了，可以聊下一个人")
            }
            showBubble()
        }
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        panel?.destroy()
        panel = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- 前台服务 ----------------

    private fun startForegroundSelf() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "悬浮球", NotificationManager.IMPORTANCE_LOW)
        )

        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, ClipboardBallService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("悬浮球运行中")
            .setContentText("轻点球读剪贴板，长按球读整屏对话")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "关闭",
                    stopPi
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------- 剪贴板 ----------------

    private fun readClipboard(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun writeClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("reply", text))
    }

    // ---------------- 主流程 ----------------

    /**
     * 点球。
     *
     * 开了读屏增强就直接从当前聊天页读最后一条对方消息，省掉复制那一步。
     * 没开、或者读不到（不在聊天页、微信改版了），就退回剪贴板那条路 ——
     * 弹一个能拿焦点的面板，焦点落定后才读得到剪贴板。
     */
    /** 短按：走剪贴板。你自己复制了什么就用什么，最可控。 */
    private fun onBallTapped() {
        if (busy) return
        fromScreen = false
        panel?.showLoadingAndGrabFocus()
    }

    /**
     * 长按：读当前聊天页整屏对话。
     *
     * 失败一律明说卡在哪一步，不再静默退回剪贴板 ——
     * 之前那样降级，读屏失灵你根本发现不了，只会觉得"这功能没用"。
     */
    private fun onBallLongPressed() {
        if (busy) return

        if (!Prefs.useScreenReader) {
            panel?.showNotice("读屏增强没打开。去 App 里「悬浮球」那张卡片打开它。")
            return
        }

        val svc = ChatAccessibilityService.instance
        if (svc == null) {
            panel?.showNotice(
                "无障碍服务没生效。\n" +
                    "在 App 里点「去开无障碍」，找到「私信助理」把开关打开。\n" +
                    "已经开过的话，关掉再打开一次。"
            )
            return
        }

        // 自检模式：只看读成什么样，不调 AI
        if (Prefs.screenReaderDebug) {
            panel?.showNotice(svc.dumpScreen())
            return
        }

        when (val r = svc.readChat()) {
            is ChatAccessibilityService.ReadResult.Failed -> {
                panel?.showNotice(r.reason)
            }
            is ChatAccessibilityService.ReadResult.Ok -> {
                val snap = r.snapshot
                lastRead = snap.lastIncoming
                fromScreen = true
                val who = snap.title.ifBlank { "对方" }
                val note = buildString {
                    append("读屏 · ${snap.total} 条对话")
                    if (snap.title.isNotBlank()) append(" · $who")
                    if (snap.alreadyReplied) append(" · 这条你已经回过了")
                }
                generate(who, snap.history, snap.lastIncoming, note)
            }
        }
    }

    /** 面板拿到焦点了，这时候才能读剪贴板 */
    private fun onFocusReady() {
        if (busy) return

        val text = readClipboard()
        if (text == null) {
            panel?.showNotice(
                "剪贴板是空的。\n" +
                    "先在微信里长按对方那条消息选「复制」，再轻点球。\n" +
                    "或者直接在聊天页里长按球，让它自己读整屏。"
            )
            return
        }
        if (text.length > 500) {
            panel?.showNotice("复制的内容太长了（${text.length} 字），只复制对方最后一条消息就行。")
            return
        }

        lastRead = text
        fromScreen = false
        val history = ContextStore.load(this, CTX_KEY)
        generate("对方", history, text, "来自剪贴板 · 上下文 ${history.size} 条")
    }

    private fun generate(
        contact: String,
        history: List<ContextStore.Turn>,
        incoming: String,
        sourceNote: String
    ) {
        busy = true
        panel?.showLoadingAndGrabFocus("生成中…\n$sourceNote", notifyFocus = false)

        scope.launch {
            val raw = try {
                AiClient.suggest(contact, history, incoming, Prefs.activeToneSpecs())
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    panel?.showNotice("调用失败：${e.message}")
                }
                busy = false
                return@launch
            }

            val candidates = SensitiveFilter.filterSuggestions(raw)
            busy = false

            withContext(Dispatchers.Main) {
                if (candidates.isEmpty()) {
                    panel?.showNotice("这次没生成出东西，再点一次试试。")
                } else {
                    // 读屏模式下屏幕本身就是上下文，不用自己攒；剪贴板模式才需要累积
                    if (!fromScreen) {
                        ContextStore.append(this@ClipboardBallService, CTX_KEY, "them", incoming)
                    }
                    val action = if (fromScreen)
                        "点一条直接填进输入框"
                    else
                        "点一条复制，然后去微信长按粘贴"
                    panel?.showCandidates(candidates, hint = "$sourceNote\n$action")
                }
            }
        }
    }

    private fun pick(sug: AiClient.Suggestion) {
        if (!fromScreen) ContextStore.append(this, CTX_KEY, "me", sug.text)
        panel?.hidePanel()

        // 读屏那条路才填输入框；剪贴板那条路老老实实复制
        if (fromScreen) {
            if (ChatAccessibilityService.instance?.fillInput(sug.text) == true) {
                toast("已填进输入框，按发送就行")
                return
            }
            writeClipboard(sug.text)
            toast("填不进去（可能在语音模式），已复制，切成键盘后粘贴")
            return
        }

        writeClipboard(sug.text)
        toast("已复制，去输入框长按粘贴")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

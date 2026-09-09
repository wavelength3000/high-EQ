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
 * 悬浮球。核心是一个「对话缓冲区」：你往里塞对话，塞够了让 AI 一次性看完再回。
 *
 * 塞的方式有三种，都不碰微信进程：
 *   1. 复制一条 → 点球（最快，一条也能直接生成）
 *   2. 面板上「读剪贴板」反复点（安卓微信没有一键多选复制，只能一条条来）
 *   3. 面板上的粘贴框，整段贴进来（电脑版多选复制、收藏转笔记都能整段拿到）
 *
 * 塞进来的东西交给 TranscriptParser 拆成一轮一轮，认得出「昵称：内容」、
 * 时间戳抬头、昵称独占一行三种常见形状。谁是谁判错了，面板上点一下就改。
 *
 * 权限只要两个：悬浮窗 + 通知。微信那边看不到任何异常。
 */
class ClipboardBallService : Service() {

    companion object {
        const val ACTION_STOP = "com.tools.wereply.STOP_BALL"
        private const val CHANNEL = "ball"
        private const val NOTIF_ID = 1001

        /** 对话缓冲区存这个 key 下，服务被系统杀掉再拉起来也还在 */
        private const val BUF_KEY = "buffer"

        /** 剪贴板超过这个长度就只留最后一段 —— 前面的多半是历史，不影响这一轮 */
        private const val MAX_CLIP = 8000

        @Volatile
        var running = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var panel: FloatingPanel? = null
    private var busy = false

    /** 攒着的对话。生成时整段发给 AI。 */
    private val buffer = mutableListOf<ContextStore.Turn>()

    /** 对方昵称，解析出来就用解析的 */
    private var contact = ""

    /** 面板顶上那行小字：这批对话是从哪来的、解析成什么样了 */
    private var sourceNote = ""

    /** 上一条从剪贴板收进来的原文，用来挡住重复点球 */
    private var lastIngested = ""

    /** 这一轮是读屏读来的吗（是的话选中候选可以直接填进输入框） */
    private var fromScreen = false

    /** 点球之后要等面板拿到焦点才能读剪贴板，这个标记记着「拿到焦点后干嘛」 */
    private var readOnFocus = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        startForegroundSelf()

        buffer.addAll(ContextStore.load(this, BUF_KEY))

        panel = FloatingPanel(this).apply {
            onBubbleClick = { onBallTapped() }
            onBubbleLongClick = { showBuffer("攒对话") }
            onPanelFocused = { onFocusReady() }
            onPick = { sug -> pick(sug) }
            onClearContext = { clearBuffer() }
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
            .setContentText("轻点球读剪贴板，长按球打开攒对话面板")
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
        val sb = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val t = clip.getItemAt(i).coerceToText(this)?.toString().orEmpty()
            if (t.isBlank()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(t)
        }
        return sb.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun writeClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("reply", text))
    }

    // ---------------- 缓冲区 ----------------

    private fun saveBuffer() = ContextStore.save(this, BUF_KEY, buffer.toList())

    private fun clearBuffer() {
        buffer.clear()
        contact = ""
        lastIngested = ""
        sourceNote = ""
        fromScreen = false
        saveBuffer()
        panel?.hidePanel()
        toast("清空了，可以聊下一个人")
    }

    /** 攒对话面板。所有入口最后都回到这里。 */
    private fun showBuffer(note: String? = null) {
        note?.let { sourceNote = it }
        val head = buildString {
            append(sourceNote.ifBlank { "攒对话" })
            if (buffer.isNotEmpty()) {
                append("\n共 ${buffer.size} 条 · 对方 ")
                append(buffer.count { it.role == "them" })
                append(" 条 · 你 ")
                append(buffer.count { it.role == "me" })
                append(" 条")
            }
        }
        panel?.showTranscript(
            turns = buffer.toList(),
            header = head,
            canReadScreen = Prefs.useScreenReader && ChatAccessibilityService.instance != null,
            onToggleRole = { i ->
                buffer.getOrNull(i)?.let {
                    buffer[i] = ContextStore.Turn(if (it.role == "me") "them" else "me", it.text)
                    saveBuffer()
                    showBuffer()
                }
            },
            onDelete = { i ->
                if (i in buffer.indices) {
                    buffer.removeAt(i)
                    saveBuffer()
                    showBuffer()
                }
            },
            onSwapAll = {
                for (i in buffer.indices) {
                    val t = buffer[i]
                    buffer[i] = ContextStore.Turn(if (t.role == "me") "them" else "me", t.text)
                }
                saveBuffer()
                showBuffer("角色已对调")
            },
            onClear = { clearBuffer() },
            // 面板本身就持有焦点，这里可以直接读剪贴板，不用再抢一次
            onCollectMore = { ingestClipboard(auto = false) },
            onPasteText = { text -> ingest(text, auto = false, from = "粘贴框") },
            onReadScreen = { readScreenIntoBuffer() },
            onGenerate = { generate() }
        )
    }

    // ---------------- 收内容 ----------------

    /** 点球：先弹面板抢焦点，Android 10+ 只有持焦点的窗口读得到剪贴板 */
    private fun onBallTapped() {
        if (busy) return
        readOnFocus = true
        panel?.showLoadingAndGrabFocus()
    }

    private fun onFocusReady() {
        if (busy || !readOnFocus) return
        readOnFocus = false
        ingestClipboard(auto = true)
    }

    private fun ingestClipboard(auto: Boolean) {
        val text = readClipboard()
        if (text == null) {
            showBuffer("剪贴板是空的。先在微信里长按对方消息选「复制」。")
            return
        }
        // 太长就只留最后一段：对话是从后往前有用的
        val trimmed = if (text.length > MAX_CLIP) text.takeLast(MAX_CLIP) else text
        ingest(trimmed, auto, from = "剪贴板")
    }

    /**
     * 把一段文本收进缓冲区。
     *
     * 解析出多条 = 你整段贴进来了，直接换掉缓冲区（不是追加，不然会重复）。
     * 解析出一条 = 一条条攒着的常规情况，追加。
     *
     * auto 为真表示这是「点球」触发的，缓冲区本来是空的话就一路生成到底，
     * 不多让你点一次。角色判定拿不准（没填昵称）就停在面板上让你确认。
     */
    private fun ingest(raw: String, auto: Boolean, from: String) {
        val r = TranscriptParser.parse(raw, Prefs.myNickname)
        if (r.turns.isEmpty()) {
            showBuffer("$from 里没有能用的文字。")
            return
        }
        fromScreen = false

        if (r.multi) {
            buffer.clear()
            buffer.addAll(r.turns)
            contact = r.them
            lastIngested = raw
            saveBuffer()

            val note = "$from · ${r.summary()}"
            if (auto && r.byNickname) {
                sourceNote = note
                generate()
            } else {
                showBuffer(
                    note + (if (r.byNickname) "" else "\n角色是猜的 —— 不对就点「角色对调」，或者去 App 里填上你的昵称")
                )
            }
            return
        }

        // 单条
        val one = r.turns.first().text
        if (one == lastIngested) {
            showBuffer("剪贴板还是刚才那条。去微信复制下一条再点「读剪贴板」。")
            return
        }
        lastIngested = one
        buffer.add(ContextStore.Turn("them", one))
        saveBuffer()

        if (auto && buffer.size == 1) {
            sourceNote = "$from · 单条"
            generate()
        } else {
            showBuffer("$from · 已收 ${buffer.size} 条")
        }
    }

    /** 面板上的「读屏幕」按钮。读屏能用就用，读不到会明说卡在哪一步。 */
    private fun readScreenIntoBuffer() {
        val svc = ChatAccessibilityService.instance
        if (svc == null) {
            panel?.showNotice(
                "无障碍服务没生效。\n" +
                    "在 App 里点「去开无障碍」，找到「私信助理」把开关打开。\n" +
                    "已经开过的话，关掉再打开一次。"
            )
            return
        }
        if (Prefs.screenReaderDebug) {
            panel?.showNotice(svc.dumpScreen())
            return
        }
        when (val r = svc.readChat()) {
            is ChatAccessibilityService.ReadResult.Failed -> panel?.showNotice(
                r.reason + "\n\n读屏一直不行就别用它了 —— 复制消息再点球，效果一样。"
            )
            is ChatAccessibilityService.ReadResult.Ok -> {
                val snap = r.snapshot
                buffer.clear()
                buffer.addAll(snap.history)
                buffer.add(ContextStore.Turn("them", snap.lastIncoming))
                contact = snap.title
                fromScreen = true
                saveBuffer()
                showBuffer("读屏 · ${snap.total} 条 · ${snap.title.ifBlank { "对方" }}")
            }
        }
    }

    // ---------------- 生成 ----------------

    private fun generate() {
        if (busy) return
        if (buffer.isEmpty()) {
            showBuffer("还没收到内容。")
            return
        }
        busy = true
        val who = contact.ifBlank { "对方" }
        panel?.showLoadingAndGrabFocus("生成中…\n$sourceNote", notifyFocus = false)

        scope.launch {
            val raw = try {
                AiClient.suggest(who, buffer.toList(), Prefs.activeToneSpecs())
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
                    val action = if (fromScreen)
                        "点一条直接填进输入框"
                    else
                        "点一条复制，然后去微信长按粘贴"
                    panel?.showCandidates(
                        candidates,
                        hint = "$sourceNote\n$action · 长按球回到攒对话面板"
                    )
                }
            }
        }
    }

    private fun pick(sug: AiClient.Suggestion) {
        // 选中的这条算你说的，留在缓冲区里，下一轮 AI 就知道你刚回了什么
        buffer.add(ContextStore.Turn("me", sug.text))
        saveBuffer()
        panel?.hidePanel()

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

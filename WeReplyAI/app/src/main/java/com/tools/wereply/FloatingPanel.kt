package com.tools.wereply

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * 悬浮球 + 候选面板。用普通 View 画的，没用 Compose
 * （Compose 挂 WindowManager 上要自己补 Lifecycle / SavedState / ViewModelStore 三个 Owner）。
 *
 * 焦点这块是关键：
 *   悬浮球常驻，必须 NOT_FOCUSABLE，否则你没法正常用微信。
 *   但 Android 10 之后只有持有焦点的窗口才能读剪贴板，
 *   所以面板做成可获焦点的，点球先弹面板抢到焦点，再读剪贴板。
 */
class FloatingPanel(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var bubble: TextView? = null
    private var panel: LinearLayout? = null

    var onBubbleClick: (() -> Unit)? = null
    var onBubbleLongClick: (() -> Unit)? = null
    var onPick: ((AiClient.Suggestion) -> Unit)? = null
    var onClearContext: (() -> Unit)? = null

    /** 面板真正拿到焦点之后回调，用来读剪贴板 */
    var onPanelFocused: (() -> Unit)? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    // ---------------- 悬浮球 ----------------

    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() {
        if (bubble != null) return

        val tv = TextView(ctx).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 13f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC1F6FEB"))
            }
        }

        val lp = WindowManager.LayoutParams(
            dp(48), dp(48),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = ctx.resources.displayMetrics.heightPixels / 2
        }

        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragged = false
        var longFired = false

        // 按住不动 450ms 算长按。拖动会取消，免得挪个位置也触发。
        val longPress = Runnable {
            if (!dragged) {
                longFired = true
                onBubbleLongClick?.invoke()
            }
        }

        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragged = false
                    longFired = false
                    tv.postDelayed(longPress, 450)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        dragged = true
                        tv.removeCallbacks(longPress)
                    }
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(tv, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    tv.removeCallbacks(longPress)
                    if (!dragged && !longFired) onBubbleClick?.invoke()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    tv.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }

        runCatching { wm.addView(tv, lp) }
        bubble = tv
    }

    fun setBubbleText(t: String) {
        bubble?.post { bubble?.text = t }
    }

    fun hideBubble() {
        hidePanel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    val isBubbleShown: Boolean get() = bubble != null

    // ---------------- 面板 ----------------

    /** 返回时关掉面板，而不是把事件丢给微信 */
    private inner class PanelRoot(c: Context) : LinearLayout(c) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                hidePanel()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /** ScrollView 没有 setMaxHeight，只能重写 onMeasure 把高度压到半屏以内 */
    private inner class CappedScroll(c: Context) : ScrollView(c) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val cap = MeasureSpec.makeMeasureSpec(
                (ctx.resources.displayMetrics.heightPixels * 0.5).toInt(),
                MeasureSpec.AT_MOST
            )
            super.onMeasure(widthSpec, cap)
        }
    }

    private fun newContainer(): LinearLayout = PanelRoot(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#F2202124"))
        }
        isFocusableInTouchMode = true
    }

    /**
     * 注意 flags 里没有 FLAG_NOT_FOCUSABLE —— 这个面板要拿焦点，
     * 不然 Android 10+ 上读不到剪贴板。
     */
    private fun attach(container: LinearLayout, focusable: Boolean, notifyFocus: Boolean = false) {
        val flags = if (focusable)
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        else
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        val lp = WindowManager.LayoutParams(
            (ctx.resources.displayMetrics.widthPixels * 0.86).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(120)
        }

        runCatching { wm.addView(container, lp) }
        panel = container

        if (focusable) {
            container.requestFocus()
            // 焦点落定要一帧，post 之后再读剪贴板
            if (notifyFocus) container.post { onPanelFocused?.invoke() }
        }
    }

    /** 弹一个"正在读取"的占位面板，同时抢焦点 */
    fun showLoadingAndGrabFocus(text: String = "读取剪贴板…", notifyFocus: Boolean = true) {
        hidePanel()
        val c = newContainer()
        c.addView(TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(6), dp(8), dp(6), dp(8))
        })
        attach(c, focusable = true, notifyFocus = notifyFocus)
    }

    /** 提示面板。自检输出可能十几行，套一层限高滚动免得撑出屏幕。 */
    fun showNotice(text: String) {
        hidePanel()
        val c = newContainer()

        val tv = TextView(ctx).apply {
            this.text = text
            setTextColor(Color.parseColor("#FFFFB4A9"))
            textSize = 14f
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        val sv = CappedScroll(ctx).apply { addView(tv) }

        c.addView(
            sv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        c.addView(closeButton())
        attach(c, focusable = false)
    }

    /** 候选列表。点一条走 onPick。 */
    fun showCandidates(candidates: List<AiClient.Suggestion>, hint: String? = null) {
        hidePanel()
        if (candidates.isEmpty()) return

        val c = newContainer()

        hint?.let {
            c.addView(TextView(ctx).apply {
                text = it
                setTextColor(Color.parseColor("#99FFFFFF"))
                textSize = 11f
                setPadding(dp(4), 0, dp(4), dp(4))
            })
        }

        candidates.forEach { sug ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#33FFFFFF"))
                }
                setOnClickListener { onPick?.invoke(sug) }
            }
            row.addView(TextView(ctx).apply {
                text = sug.toneLabel
                setTextColor(Color.parseColor("#FF8AB4F8"))
                textSize = 11f
            })
            row.addView(TextView(ctx).apply {
                text = sug.text
                setTextColor(Color.WHITE)
                textSize = 15f
            })
            c.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            )
        }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        bar.addView(textButton("换个人聊") {
            onClearContext?.invoke()
            hidePanel()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(textButton("关掉") { hidePanel() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(bar)

        // 保持焦点：写剪贴板和后续交互都在这个窗口里发生
        attach(c, focusable = true)
    }

    // ---------------- 对话面板 ----------------

    /**
     * 攒起来的对话长什么样，一条条列出来。
     *
     * 每条点一下切换「对方 / 我」，长按删掉。解析器判错了在这里当场改，
     * 比弹一句「解析失败」有用。底下那个输入框是给整段粘贴用的 ——
     * 面板本来就持有焦点，长按就能调出系统的粘贴菜单。
     */
    fun showTranscript(
        turns: List<ContextStore.Turn>,
        header: String,
        canReadScreen: Boolean,
        onToggleRole: (Int) -> Unit,
        onDelete: (Int) -> Unit,
        onSwapAll: () -> Unit,
        onClear: () -> Unit,
        onCollectMore: () -> Unit,
        onPasteText: (String) -> Unit,
        onReadScreen: () -> Unit,
        onGenerate: () -> Unit
    ) {
        hidePanel()
        val c = newContainer()

        c.addView(TextView(ctx).apply {
            text = header
            setTextColor(Color.parseColor("#99FFFFFF"))
            textSize = 11f
            setPadding(dp(4), 0, dp(4), dp(4))
        })

        if (turns.isEmpty()) {
            c.addView(TextView(ctx).apply {
                text = "还没收到东西。\n先在微信里复制消息，再点「读剪贴板」；\n" +
                    "或者长按下面的框，把整段对话粘进来。"
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 13f
                setPadding(dp(6), dp(6), dp(6), dp(6))
            })
        } else {
            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            turns.forEachIndexed { i, t ->
                val mine = t.role == "me"
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.parseColor(if (mine) "#22FFFFFF" else "#33FFFFFF"))
                    }
                    setOnClickListener { onToggleRole(i) }
                    setOnLongClickListener { onDelete(i); true }
                }
                row.addView(TextView(ctx).apply {
                    text = if (mine) "我" else "对方"
                    setTextColor(Color.parseColor(if (mine) "#FF9AA0A6" else "#FF8AB4F8"))
                    textSize = 11f
                    width = dp(34)
                })
                row.addView(TextView(ctx).apply {
                    text = t.text.replace("\n", " ").take(60)
                    setTextColor(if (mine) Color.parseColor("#CCFFFFFF") else Color.WHITE)
                    textSize = 13f
                })
                list.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) }
                )
            }
            c.addView(
                CappedScroll(ctx).apply { addView(list) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            c.addView(TextView(ctx).apply {
                text = "点一条切换「对方 / 我」，长按删掉"
                setTextColor(Color.parseColor("#77FFFFFF"))
                textSize = 10f
                setPadding(dp(4), dp(4), dp(4), 0)
            })
        }

        // 粘贴框：整段对话最省事的入口
        val paste = EditText(ctx).apply {
            hint = "长按这里粘贴整段对话"
            setHintTextColor(Color.parseColor("#77FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 13f
            minLines = 2
            maxLines = 4
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#22FFFFFF"))
            }
        }
        c.addView(
            paste,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(textButton("读剪贴板") { onCollectMore() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(textButton("用粘贴的") {
            val t = paste.text?.toString().orEmpty()
            if (t.isBlank()) return@textButton
            onPasteText(t)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (canReadScreen) {
            row1.addView(textButton("读屏幕") { onReadScreen() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        c.addView(row1)

        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(textButton("角色对调") { onSwapAll() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(textButton("清空") { onClear() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(textButton("关掉") { hidePanel() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(row2)

        val go = TextView(ctx).apply {
            text = "生成回复"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(if (turns.isEmpty()) "#33FFFFFF" else "#CC1F6FEB"))
            }
            isEnabled = turns.isNotEmpty()
            setOnClickListener { if (turns.isNotEmpty()) onGenerate() }
        }
        c.addView(
            go,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        attach(c, focusable = true)
    }

    private fun textButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(Color.parseColor("#99FFFFFF"))
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(4))
        setOnClickListener { onClick() }
    }

    private fun closeButton() = textButton("关掉") { hidePanel() }

    fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    fun destroy() = hideBubble()
}

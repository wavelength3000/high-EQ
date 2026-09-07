package com.tools.wereply

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 用普通 View 画的悬浮层，没用 Compose。
 * Compose 挂在 WindowManager 上要自己补 Lifecycle / SavedState / ViewModelStore 三个 Owner，
 * 麻烦且容易在部分 ROM 上崩，这种小面板不值当。
 */
class FloatingPanel(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    /** 点悬浮球时回调，参数是当前状态文案的设置器 */
    var onBubbleClick: (() -> Unit)? = null

    /** 点某条候选时回调 */
    var onPick: ((AiClient.Suggestion) -> Unit)? = null

    companion object {
        fun canDraw(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)
    }

    @Suppress("DEPRECATION")
    private fun layoutType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() {
        if (bubble != null || !canDraw(ctx)) return

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
            dp(46), dp(46),
            layoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = ctx.resources.displayMetrics.heightPixels / 2
        }

        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragged = false

        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragged = true
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(tv, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onBubbleClick?.invoke()
                    true
                }
                else -> false
            }
        }

        runCatching { wm.addView(tv, lp) }
        bubble = tv
        bubbleParams = lp
    }

    fun setBubbleText(t: String) {
        bubble?.post { bubble?.text = t }
    }

    fun hideBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        hidePanel()
    }

    /** 只有一行字的提示（错误、拦截说明），不带风格标签 */
    fun showNotice(text: String) {
        showCandidates(
            listOf(AiClient.Suggestion("notice", "提示", text, false)),
            pickable = false
        )
    }

    /** 展开候选列表 */
    fun showCandidates(candidates: List<AiClient.Suggestion>, pickable: Boolean = true) {
        hidePanel()
        if (candidates.isEmpty() || !canDraw(ctx)) return

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#F2202124"))
            }
        }

        candidates.forEach { sug ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor(if (pickable) "#33FFFFFF" else "#33FF7043"))
                }
                if (pickable) setOnClickListener {
                    hidePanel()
                    onPick?.invoke(sug)
                }
            }

            if (pickable) {
                row.addView(TextView(ctx).apply {
                    text = sug.toneLabel
                    setTextColor(Color.parseColor("#FF8AB4F8"))
                    textSize = 11f
                })
            }
            row.addView(TextView(ctx).apply {
                text = sug.text
                setTextColor(Color.WHITE)
                textSize = 15f
            })

            container.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            )
        }

        val close = TextView(ctx).apply {
            text = "关掉"
            setTextColor(Color.parseColor("#99FFFFFF"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(4))
            setOnClickListener { hidePanel() }
        }
        container.addView(close)

        val lp = WindowManager.LayoutParams(
            (ctx.resources.displayMetrics.widthPixels * 0.82).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(140)
        }

        runCatching { wm.addView(container, lp) }
        panel = container
    }

    fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    fun destroy() {
        hideBubble()
    }

    val isBubbleShown: Boolean get() = bubble != null
}

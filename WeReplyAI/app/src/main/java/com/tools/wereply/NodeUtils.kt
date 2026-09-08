package com.tools.wereply

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * 从无障碍节点树里把聊天内容抠出来。
 *
 * 核心原则：**一个字都不要靠 className 去匹配。**
 * 微信的类名是混淆的，而且消息气泡用的是自家自定义控件（为了渲染表情），
 * 既不叫 TextView 也不叫 RecyclerView。按类名筛必然筛空。
 *
 * 改用不依赖命名的判据：
 *   消息   = 叶子节点 + 有文字 + 落在标题栏和输入框之间
 *   输入框 = isEditable
 *   谁说的 = 气泡贴左边还是贴右边
 */
object NodeUtils {

    private const val MAX_DEPTH = 50

    data class ChatMsg(val text: String, val fromMe: Boolean)

    // ---------------- 遍历 ----------------

    private fun walk(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: MutableList<AccessibilityNodeInfo>,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        if (node == null || depth > MAX_DEPTH) return out
        if (pred(node)) out.add(node)
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), depth + 1, out, pred)
        }
        return out
    }

    fun findAll(
        root: AccessibilityNodeInfo?,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> = walk(root, 0, mutableListOf(), pred)

    fun bounds(n: AccessibilityNodeInfo): Rect = Rect().also { n.getBoundsInScreen(it) }

    // ---------------- 输入框 / 发送键 ----------------

    /**
     * 聊天输入框。认 isEditable，不认类名。
     * 微信切到「按住 说话」语音模式时页面上没有可编辑节点，这里会返回 null，调用方要兜住。
     */
    fun findInput(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        findAll(root) { it.isEditable && it.isVisibleToUser }
            .maxByOrNull { bounds(it).top }

    fun findSendButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        findAll(root) {
            val t = it.text?.toString() ?: it.contentDescription?.toString()
            t == "发送" && it.isVisibleToUser
        }.firstOrNull()

    // ---------------- 噪声过滤 ----------------

    private val NOISE_EXACT = setOf(
        "昨天", "今天", "前天", "以下为新消息", "查看更多消息", "更多消息",
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日", "星期天",
        "已读", "未读", "对方正在输入", "正在输入…", "已发送", "按住 说话", "按住说话",
        "发送", "取消", "转发", "收藏", "删除", "引用", "多选", "撤回"
    )

    private val TIME_PATTERNS = listOf(
        Regex("^(上午|下午|凌晨|早上|中午|晚上)?\\s*\\d{1,2}[:：]\\d{2}$"),
        Regex("^\\d{4}年\\d{1,2}月\\d{1,2}日.*$"),
        Regex("^\\d{1,2}月\\d{1,2}日.*$"),
        Regex("^(昨天|今天|前天|星期.)\\s*\\d{1,2}[:：]\\d{2}$")
    )

    /** 语音消息在节点树里就是个时长，比如 14" —— 换成占位符，保住对话轮次 */
    private val VOICE = Regex("^\\d{1,3}\\s*[\"\u2033\u0027\u2019]$")

    private fun isNoise(t: String): Boolean =
        t in NOISE_EXACT || TIME_PATTERNS.any { it.matches(t) }

    // ---------------- 主体 ----------------

    private class Row(val top: Int, val left: Int, val right: Int, val text: String)

    /**
     * 读出屏幕上的聊天记录，按纵向位置排序，也就是真实的时间顺序。
     *
     * 上下边界：底部用输入框的上沿，读不到输入框（语音模式）就按屏高 88% 估；
     * 顶部按屏高 11% 切掉标题栏。这样标题、返回键、底部工具栏都不会混进来。
     */
    fun extractMessages(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        maxCount: Int = 30
    ): List<ChatMsg> {
        if (root == null || screenWidth <= 0 || screenHeight <= 0) return emptyList()

        val inputTop = findInput(root)?.let { bounds(it).top } ?: (screenHeight * 0.88).toInt()
        val topLimit = (screenHeight * 0.11).toInt()

        val rows = findAll(root) { n ->
            n.childCount == 0 && !n.text.isNullOrBlank() && !n.isEditable
        }.mapNotNull { n ->
            var text = n.text.toString().trim()
            if (text.isBlank() || text.length > 500) return@mapNotNull null

            val b = bounds(n)
            if (b.height() == 0 || b.width() == 0) return@mapNotNull null
            if (b.top < topLimit || b.bottom > inputTop) return@mapNotNull null

            if (VOICE.matches(text)) text = "[语音]"
            if (isNoise(text)) return@mapNotNull null

            Row(b.top, b.left, b.right, text)
        }.distinctBy { it.top to it.text }
            .sortedBy { it.top }

        if (rows.isEmpty()) return emptyList()

        fun center(r: Row) = (r.left + r.right) / 2.0 / screenWidth

        // 先用中心点定下明确靠左/靠右的，拿它们校准出两条泳道的边界，
        // 再回头处理那些几乎占满屏宽、中心点落在中间说明不了问题的长消息。
        val themLeft = rows.filter { center(it) < 0.45 }.minOfOrNull { it.left }
        val meRight = rows.filter { center(it) > 0.55 }.maxOfOrNull { it.right }

        return rows.map { r ->
            val c = center(r)
            val fromMe = when {
                c > 0.55 -> true
                c < 0.45 -> false
                themLeft != null && meRight != null ->
                    abs(r.right - meRight) < abs(r.left - themLeft)
                else -> c > 0.5
            }
            ChatMsg(r.text, fromMe)
        }.takeLast(maxCount)
    }

    /** 当前聊天对象的昵称：标题栏区域里最长的那段文字。同样不看类名。 */
    fun extractTitle(root: AccessibilityNodeInfo?, screenHeight: Int): String {
        val topLimit = (screenHeight * 0.11).toInt()
        return findAll(root) { n ->
            n.childCount == 0 && !n.text.isNullOrBlank() && !n.isEditable &&
                bounds(n).let { it.height() > 0 && it.bottom in 1..topLimit }
        }.mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotBlank() && it.length <= 32 && !it.all { c -> c.isDigit() } }
            .maxByOrNull { it.length }
            ?.substringBefore("(")?.substringBefore("（")
            ?.trim()
            .orEmpty()
    }

    /** 自检用：把读到的东西原样吐出来，方便在手机上一眼看出是哪一步不对 */
    fun dump(root: AccessibilityNodeInfo?, screenWidth: Int, screenHeight: Int): String {
        if (root == null) return "拿不到窗口根节点"

        val pkg = root.packageName?.toString().orEmpty()
        val input = findInput(root)
        val msgs = extractMessages(root, screenWidth, screenHeight)

        val head = buildString {
            append("包名：$pkg\n")
            append("输入框：${if (input == null) "没找到（语音模式？）" else "有"}\n")
            append("读到 ${msgs.size} 条\n")
        }

        if (msgs.isEmpty()) return head + "\n一条都没读到。"

        return head + "\n" + msgs.takeLast(12).joinToString("\n") {
            (if (it.fromMe) "我 → " else "对方 → ") + it.text.take(20)
        }
    }
}

package com.tools.wereply

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 微信的 view id 每个版本都变（而且是混淆过的），照着 id 写死必然三天两头失效。
 * 这里全部用「结构 + 几何位置 + 文本」的启发式规则来定位，抗版本更新能力强得多：
 *
 *   输入框   = 窗口里的 EditText
 *   发送键   = 文本是「发送」的可点击节点（微信里输入框有字才出现）
 *   气泡归属 = 靠右的是自己发的，靠左的是对方发的
 */
object NodeUtils {

    private const val MAX_DEPTH = 40

    data class ChatMsg(val text: String, val fromMe: Boolean)

    private fun walk(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        out: MutableList<AccessibilityNodeInfo> = mutableListOf(),
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

    /** 聊天输入框。取最靠下的那个 EditText，避开搜索框。 */
    fun findInput(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        findAll(root) { it.className?.toString()?.contains("EditText") == true && it.isVisibleToUser }
            .maxByOrNull { bounds(it).top }

    /** 发送键。微信/抖音都是文字「发送」，输入框有内容时才出现。 */
    fun findSendButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        findAll(root) {
            val t = it.text?.toString() ?: it.contentDescription?.toString()
            t == "发送" && it.isVisibleToUser
        }.firstOrNull()

    /** 消息列表容器：可滚动、或者类名是 RecyclerView / ListView。 */
    private fun findChatList(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        findAll(root) {
            val cn = it.className?.toString().orEmpty()
            (it.isScrollable || cn.contains("RecyclerView") || cn.contains("ListView")) &&
                it.isVisibleToUser
        }.maxByOrNull { val b = bounds(it); b.width() * b.height() }

    /** 时间戳、日期分隔、系统提示这些不是对话内容 */
    private val NOISE_EXACT = setOf(
        "昨天", "今天", "前天", "以下为新消息", "查看更多消息", "更多消息",
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日", "星期天",
        "已读", "未读", "对方正在输入", "正在输入…", "已发送"
    )

    private val TIME_PATTERNS = listOf(
        Regex("^(上午|下午|凌晨|早上|中午|晚上)?\\s*\\d{1,2}[:：]\\d{2}$"),
        Regex("^\\d{4}年\\d{1,2}月\\d{1,2}日.*$"),
        Regex("^\\d{1,2}月\\d{1,2}日.*$"),
        Regex("^(昨天|今天|前天|星期.)\\s*\\d{1,2}[:：]\\d{2}$")
    )

    private fun isNoise(t: String): Boolean =
        t in NOISE_EXACT || TIME_PATTERNS.any { it.matches(t) }

    /**
     * 读出屏幕上的聊天记录，按纵向位置排序，也就是真实的时间顺序。
     *
     * 几个要点：
     *   - 不能靠节点树的遍历顺序，RecyclerView 复用之后子节点顺序跟视觉顺序对不上，
     *     必须按 bounds.top 重新排。
     *   - 不筛 isVisibleToUser，这样能顺带捞到 RecyclerView 缓存住的、刚滚出屏幕
     *     一点点的那几行，白赚一些上下文。用容器上下各一屏的范围兜住，
     *     免得把回收池里 bounds 全零的脏节点也算进来。
     */
    fun extractMessages(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        maxCount: Int = 30
    ): List<ChatMsg> {
        val list = findChatList(root) ?: return emptyList()
        val lb = bounds(list)
        if (lb.height() == 0) return emptyList()

        data class Row(val top: Int, val fromMe: Boolean, val text: String)

        return findAll(list) { n ->
            val cn = n.className?.toString().orEmpty()
            n.childCount == 0 &&
                cn.contains("TextView") &&
                !cn.contains("EditText") &&
                !n.text.isNullOrBlank()
        }.mapNotNull { n ->
            val text = n.text.toString().trim()
            if (text.isBlank() || text.length > 500 || isNoise(text)) return@mapNotNull null

            val b = bounds(n)
            if (b.height() == 0 || b.width() == 0) return@mapNotNull null
            // 容器上下各放宽一屏，捞缓存行
            if (b.bottom < lb.top - lb.height() || b.top > lb.bottom + lb.height()) {
                return@mapNotNull null
            }

            Row(b.top, b.centerX() > screenWidth * 0.55, text)
        }
            .distinctBy { it.top to it.text }
            .sortedBy { it.top }
            .map { ChatMsg(it.text, it.fromMe) }
            .takeLast(maxCount)
    }

    /** 当前聊天对象的昵称：标题栏里最靠上的那个非空 TextView。 */
    fun extractTitle(root: AccessibilityNodeInfo?, screenHeight: Int): String {
        return findAll(root) { n ->
            val cn = n.className?.toString().orEmpty()
            n.childCount == 0 && cn.contains("TextView") &&
                !n.text.isNullOrBlank() && n.isVisibleToUser &&
                bounds(n).top < screenHeight * 0.12
        }.maxByOrNull { it.text!!.length }
            ?.text?.toString()?.trim()
            ?.substringBefore("(")?.substringBefore("（")
            .orEmpty()
    }

    fun bounds(n: AccessibilityNodeInfo): Rect =
        Rect().also { n.getBoundsInScreen(it) }
}

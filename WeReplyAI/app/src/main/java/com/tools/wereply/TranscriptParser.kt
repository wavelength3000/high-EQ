package com.tools.wereply

/**
 * 把一整段复制来的聊天记录，拆成一轮一轮的对话。
 *
 * 内容是你自己复制进去的，系统给什么就是什么，没有任何猜的成分。
 * 代价是格式五花八门，所以解析器要认得多。
 *
 * 支持四种形状，按可靠性从高到低依次尝试：
 *
 *   ① 时间戳抬头（微信电脑版多选复制、邮件备份都是这个）
 *        小王 2026-09-08 10:23:45
 *        在吗
 *
 *   ② 行内前缀（收藏转笔记、各种导出工具）
 *        小王：在吗
 *        我：刚下播
 *
 *   ③ 昵称独占一行
 *        小王
 *        在吗
 *
 *   ④ 都不认识 —— 整段当成对方连发的一条，起码不丢内容
 *
 * 分不清谁是谁的时候宁可判错也不要罢工：面板上有「角色对调」一键翻过来，
 * 比弹一句「解析失败」有用得多。
 */
object TranscriptParser {

    data class Result(
        /** role 只有 "me" / "them" 两种，跟 ContextStore 一致 */
        val turns: List<ContextStore.Turn>,
        /** 识别到的说话人昵称，按首次出现顺序 */
        val speakers: List<String>,
        /** 判定成对方的那个昵称，判不出来是空串 */
        val them: String,
        /** 用哪种格式解析出来的，显示在面板上让你一眼看出对不对 */
        val format: String,
        /** 角色是靠你填的昵称对上的（可信），还是靠「最后说话的是对方」猜的 */
        val byNickname: Boolean = false
    ) {
        val multi: Boolean get() = turns.size >= 2
        val fromThem: Int get() = turns.count { it.role == "them" }
        val fromMe: Int get() = turns.count { it.role == "me" }

        /** 要回复的那条：最后一条对方说的话 */
        val lastIncoming: String
            get() = turns.lastOrNull { it.role == "them" }?.text
                ?: turns.lastOrNull()?.text.orEmpty()

        /** 面板上那行小字 */
        fun summary(): String = buildString {
            append(format)
            append(" · ")
            append("${turns.size} 条")
            if (speakers.isNotEmpty()) {
                append(" · 对方=")
                append(if (them.isBlank()) "?" else them)
                append(" · 你 $fromMe 条")
            }
        }
    }

    private const val MAX_NAME = 20
    private const val MAX_TURNS = 40
    private const val MAX_TEXT = 500

    /** 昵称 + 时间戳独占一行 */
    private val TS_HEAD = Regex(
        """^(.{1,24}?)\s+(\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?)(\s+\d{1,2}[:：]\d{2}(?::\d{2})?)?$"""
    )

    /** 「昵称：内容」，冒号前不许有空格、不许再有冒号 */
    private val INLINE = Regex("""^([^\s:：][^:：]{0,19})[：:]\s*(.*)$""")

    /** 这些不可能是昵称 */
    private val NOT_NAME = Regex("""^(https?|www|ftp|\d+|[\d:：.\-/\s]+)$""", RegexOption.IGNORE_CASE)

    /** 复制聊天记录时经常混进来的系统行 */
    private val NOISE_EXACT = setOf(
        "以下为新消息", "查看更多消息", "更多消息", "昨天", "今天", "前天",
        "已读", "未读", "对方正在输入", "正在输入…", "已发送", "按住 说话", "按住说话",
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日", "星期天"
    )

    private val NOISE_PATTERNS = listOf(
        Regex("""^(上午|下午|凌晨|早上|中午|晚上)?\s*\d{1,2}[:：]\d{2}$"""),
        Regex("""^\d{4}年\d{1,2}月\d{1,2}日.*$"""),
        Regex("""^(昨天|今天|前天|星期.)\s*\d{1,2}[:：]\d{2}$"""),
        Regex(""".{0,20}撤回了一条消息$"""),
        Regex("""^-{3,}.*-{3,}$""")
    )

    private fun isNoise(t: String): Boolean =
        t in NOISE_EXACT || NOISE_PATTERNS.any { it.matches(t) }

    private data class Raw(val speaker: String, val text: String)

    // ==================== 入口 ====================

    /**
     * @param raw       剪贴板里的原文
     * @param myName    你自己的微信昵称，填了就按它分角色，最准
     */
    fun parse(raw: String, myName: String = ""): Result {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .map { it.trim() }

        val body = lines.filter { it.isNotBlank() && !isNoise(it) }
        if (body.isEmpty()) {
            return Result(emptyList(), emptyList(), "", "空")
        }

        // 三个解析器都吃过滤后的 body。喂原始 lines 会出事：
        // 「昨天 21:30」这种时间行会被「昵称：内容」认成 昵称=「昨天 21」内容=「30」。
        val items = parseTimestamped(body)?.let { it to "时间戳格式" }
            ?: parseInline(body)?.let { it to "「昵称：内容」格式" }
            ?: parseStandalone(body)?.let { it to "昵称独占一行" }

        if (items == null) {
            // 认不出说话人。多行就整段当对方连发的一段，单行就是单条。
            val text = body.joinToString("\n").take(2000)
            return Result(
                turns = listOf(ContextStore.Turn("them", text)),
                speakers = emptyList(),
                them = "",
                format = if (body.size > 1) "整段（认不出说话人）" else "单条"
            )
        }

        val (rawItems, formatName) = items
        val cleaned = rawItems
            .map { Raw(it.speaker, it.text.trim().take(MAX_TEXT)) }
            .filter { it.text.isNotBlank() && !isNoise(it.text) }
            .takeLast(MAX_TURNS)

        if (cleaned.isEmpty()) {
            return Result(emptyList(), emptyList(), "", "空")
        }

        return assign(cleaned, myName, formatName)
    }

    /** 角色判反了，一键翻过来 */
    fun swapRoles(r: Result): Result {
        val flipped = r.turns.map {
            ContextStore.Turn(if (it.role == "me") "them" else "me", it.text)
        }
        val newThem = r.speakers.firstOrNull { it != r.them } ?: r.them
        return r.copy(turns = flipped, them = newThem)
    }

    // ==================== 分角色 ====================

    private fun assign(items: List<Raw>, myName: String, formatName: String): Result {
        val speakers = items.map { it.speaker }.filter { it.isNotBlank() }.distinct()
        val mine = myName.trim()

        // 填了自己的昵称就按它来，最准；没填就退回启发式
        val meName: String? = if (mine.isBlank()) null else
            speakers.firstOrNull { it == mine }
                ?: speakers.firstOrNull { it.contains(mine) || mine.contains(it) }

        // 你是在回复对方，所以整段的最后一句默认判成对方说的
        val themName: String = when {
            meName != null -> speakers.firstOrNull { it != meName }.orEmpty()
            speakers.size == 1 -> speakers.first()
            speakers.isEmpty() -> ""
            else -> items.last { it.speaker.isNotBlank() }.speaker
        }

        // 三个人以上就是群聊，把昵称留在正文里，不然 AI 分不清谁在说什么
        val keepNames = speakers.size > 2

        val turns = items.map { item ->
            val isMe = when {
                item.speaker.isBlank() -> false
                meName != null -> item.speaker == meName
                themName.isBlank() -> false
                else -> item.speaker != themName
            }
            val text = if (keepNames && item.speaker.isNotBlank() && !isMe)
                "${item.speaker}：${item.text}"
            else
                item.text
            ContextStore.Turn(if (isMe) "me" else "them", text)
        }

        return Result(turns, speakers, themName, formatName, meName != null)
    }

    // ==================== 格式 ① 时间戳抬头 ====================

    private fun parseTimestamped(lines: List<String>): List<Raw>? {
        val out = mutableListOf<Raw>()
        val buf = StringBuilder()
        var cur: String? = null
        var heads = 0

        fun flush() {
            val name = cur
            if (name != null && buf.isNotBlank()) out.add(Raw(name, buf.toString().trim()))
            buf.setLength(0)
        }

        for (l in lines) {
            val m = TS_HEAD.matchEntire(l)
            val name = m?.let { cleanName(it.groupValues[1]) }
            if (name != null && name.isNotBlank() && name.length <= MAX_NAME &&
                !NOT_NAME.matches(name)
            ) {
                flush()
                cur = name
                heads++
                continue
            }
            if (cur != null && l.isNotBlank() && !isNoise(l)) {
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(l)
            }
        }
        flush()

        return if (heads >= 2 && out.size >= 2) out else null
    }

    // ==================== 格式 ② 行内前缀 ====================

    private fun parseInline(lines: List<String>): List<Raw>? {
        val body = lines.filter { it.isNotBlank() }
        if (body.size < 2) return null

        val out = mutableListOf<Raw>()
        var hits = 0

        for (l in body) {
            val m = INLINE.matchEntire(l)
            val name = m?.let { cleanName(it.groupValues[1]) }
            val ok = m != null && !name.isNullOrBlank() &&
                name.length <= MAX_NAME && !NOT_NAME.matches(name)

            if (ok) {
                out.add(Raw(name!!, m!!.groupValues[2]))
                hits++
            } else if (out.isNotEmpty()) {
                // 消息本身换行了，接到上一条后面
                val last = out.removeAt(out.size - 1)
                out.add(Raw(last.speaker, (last.text + "\n" + l).trim()))
            } else {
                out.add(Raw("", l))
            }
        }

        // 前缀行太少说明是误判 —— 比如正文里碰巧有几个冒号
        if (hits < 2 || hits * 3 < body.size) return null

        val counts = out.map { it.speaker }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        val names = counts.keys.toList()
        if (names.isEmpty() || names.size > 6) return null

        // 真的对话里，至少有一个人说过两次。全是一人一次的，多半根本不是对话，
        // 而是「时间：19:00」「地点：老地方」这种字段行，硬拆开反而更糟。
        // 只有两行的情况放过 —— 那本来就可能是一问一答。
        if (names.size > 2 && counts.values.max() < 2) return null

        // 开头那几行没前缀的，算在第一个说话人头上
        val first = names.first()
        return out.map { if (it.speaker.isBlank()) Raw(first, it.text) else it }
    }

    // ==================== 格式 ③ 昵称独占一行 ====================

    private fun parseStandalone(body: List<String>): List<Raw>? {
        if (body.size < 4) return null

        val counts = body.groupingBy { it }.eachCount()
        val names = counts.filterKeys { t ->
            t.length <= 16 &&
                !t.contains(':') && !t.contains('：') &&
                t.none { c -> c in "。？！，、,.?!~" } &&
                !NOT_NAME.matches(t)
        }.filterValues { it >= 2 }.keys

        // 真实的两人对话正好两个昵称；一个或者一堆都说明是碰巧重复的短句
        if (names.size !in 2..3) return null
        // 昵称行必须占到四分之一以上，否则就是「嗯嗯」「哈哈」这种重复短句被误认了
        val nameLines = body.count { it in names }
        if (nameLines * 4 < body.size) return null
        // 正经的记录一定是昵称开头
        if (body.first() !in names) return null

        val out = mutableListOf<Raw>()
        val buf = StringBuilder()
        var cur: String? = null

        fun flush() {
            val n = cur
            if (n != null && buf.isNotBlank()) out.add(Raw(n, buf.toString().trim()))
            buf.setLength(0)
        }

        for (l in body) {
            if (l in names) {
                flush()
                cur = l
            } else if (cur != null) {
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(l)
            }
        }
        flush()

        return if (out.size >= 2) out else null
    }

    // ==================== 小工具 ====================

    /** 去掉 wxid 括号和引号，「小王(wxid_abc)」→「小王」 */
    private fun cleanName(s: String): String =
        s.trim()
            .substringBefore('(')
            .substringBefore('（')
            .trim()
            .trim('"', '\u201c', '\u201d', '\u300c', '\u300d', '\u3010', '\u3011')
            .trim()
}

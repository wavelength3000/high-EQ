package com.tools.wereply

/**
 * 硬编码的拦截规则，界面上不提供关掉的开关。
 *
 * 理由很实在：这几类话题一旦让 AI 替你说话，说错了是你担责，
 * 而且涉钱 + 感情引导的对话组合在法律上性质很敏感。
 * 命中就直接不生成候选，只提醒你自己去看。
 */
object SensitiveFilter {

    /** 对方发来的消息命中这些词 -> 不自动处理 */
    private val INCOMING = listOf(
        // 钱
        "转账", "红包", "打赏", "借钱", "借点", "还钱", "汇款", "付款", "收款码",
        "银行卡", "支付宝", "余额", "充值", "提现", "手续费", "保证金",
        "投资", "理财", "炒股", "基金", "期货", "外汇", "虚拟币", "USDT", "带你赚",
        "刷单", "兼职", "内幕", "稳赚", "平台", "会员", "开通", "解锁",
        // 见面 / 线下
        "见面", "见个面", "约", "约会", "开房", "酒店", "住哪", "地址", "小区",
        "接你", "来找你", "去找你", "同城", "线下",
        // 联系方式与隐私
        "手机号", "电话号", "微信号", "加个微信", "身份证", "验证码", "密码",
        "真名", "本名", "身份证号", "学校", "上班的地方",
        // 感情与身体
        "喜欢你", "爱你", "做我女朋友", "谈恋爱", "处对象", "结婚", "老婆",
        "在一起", "养你", "包养", "睡", "裸", "视频通话", "开摄像头",
        "私密", "尺度", "身材", "三围",
        // 索要图片
        "发张照片", "发个照片", "自拍", "生活照", "素颜"
    )

    /** AI 生成的内容命中这些词 -> 这条候选直接丢掉，不给你看也不发出去 */
    private val OUTGOING = listOf(
        "转账", "红包", "打赏", "充值", "提现", "银行卡", "支付宝", "收款",
        "投资", "理财", "稳赚", "平台",
        "见面", "约会", "开房", "酒店", "我的地址", "来找我", "去找你",
        "我的手机号", "我的微信", "身份证", "验证码",
        "我爱你", "做你女朋友", "答应你", "等你", "只属于你",
        "发给你看", "拍给你", "视频通话"
    )

    /**
     * 任何风格下都不许出现的：辱骂和人身攻击。
     * 尖酸风格要的是反讽和绵里藏针，不是骂街 —— 骂街既没杀伤力，也容易被举报。
     */
    private val ABUSE = listOf(
        "傻逼", "煞笔", "沙比", "智障", "脑残", "废物", "垃圾东西", "贱",
        "滚蛋", "去死", "妈的", "尼玛", "神经病", "有病吧", "丑", "肥猪",
        "穷鬼", "屌丝", "废柴", "低能"
    )

    /** 返回命中的词，没命中返回 null */
    fun checkIncoming(text: String): String? =
        INCOMING.firstOrNull { text.contains(it, ignoreCase = true) }

    fun isOutgoingSafe(text: String): Boolean {
        if (text.isBlank() || text.length > 120) return false
        if (ABUSE.any { text.contains(it, ignoreCase = true) }) return false
        return OUTGOING.none { text.contains(it, ignoreCase = true) }
    }

    /** 过滤掉不合格的候选，最多留 5 条（风格最多勾 5 种） */
    fun filterSuggestions(list: List<AiClient.Suggestion>): List<AiClient.Suggestion> =
        list.map { it.copy(text = it.text.trim()) }
            .filter { isOutgoingSafe(it.text) }
            .distinctBy { it.text }
            .take(5)
}

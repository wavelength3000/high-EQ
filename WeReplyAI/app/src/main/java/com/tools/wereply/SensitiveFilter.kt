package com.tools.wereply

/**
 * 以前这里有三张关键词表（对方消息拦截、AI 输出拦截、辱骂拦截），现在全去掉了。
 *
 * 去掉的理由：
 *   - 对方消息里出现什么词，跟你的回复是否合适没有关系。按对方的用词决定要不要
 *     生成，本来就是错的设计，实际效果是粉丝随口提一句就整个罢工。
 *   - 输出侧静默丢弃候选更糟 —— 你根本不知道被丢了什么。
 *   - 辱骂表误伤太重，日常口语里的「丑」「贱」「有病吧」全在里面，
 *     尖酸风格基本被废掉。
 *
 * 现在只剩最基本的净化：非空、别太长。防的是模型偶尔抽风吐一大段。
 * 内容层面的把关交给你自己 —— 每条候选都是你点了才发出去的。
 *
 * 另外还有一条约束留在 AiClient 的 system prompt 里（不主动引导对方付钱），
 * 那条不拦候选、不会静默丢东西，纯粹是写给模型看的。
 */
object SensitiveFilter {

    private const val MAX_LEN = 120

    fun isOutgoingSafe(text: String): Boolean =
        text.isNotBlank() && text.length <= MAX_LEN

    fun filterSuggestions(list: List<AiClient.Suggestion>): List<AiClient.Suggestion> =
        list.map { it.copy(text = it.text.trim()) }
            .filter { isOutgoingSafe(it.text) }
            .distinctBy { it.text }
            .take(6)
}

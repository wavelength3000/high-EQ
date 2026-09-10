package com.tools.wereply

/**
 * 表达风格。每个枚举值带一段写进 system prompt 的指令。
 *
 * 想加风格就在这里加一项，界面上的选择器和 prompt 拼装都是遍历 entries 自动出来的，
 * 不用改别的地方。
 */
enum class Tone(
    val id: String,
    val label: String,
    val hint: String,
    val instruction: String,
    /** 这种风格允不允许全自动直接发出去 */
    val autoSendable: Boolean = true
) {

    EQ(
        id = "eq",
        label = "高情商",
        hint = "先接住情绪再回应，舒服但不给承诺",
        instruction = "先接住对方话里的情绪，再给回应，让人听着舒服。" +
            "但不要许诺任何事，也不要暗示你跟对方关系很近。"
    ),

    RATIONAL(
        id = "rational",
        label = "理性",
        hint = "就事论事，不带情绪",
        instruction = "就事论事，把该说的信息说清楚。不带情绪词，不寒暄，不反问，不加语气助词。"
    ),

    SHARP(
        id = "sharp",
        label = "尖酸",
        hint = "带刺、反讽，点到为止",
        instruction = "带刺、反讽、绵里藏针，可以让对方不舒服。" +
            "但绝对不许骂人、不许人身攻击、不提对方的长相收入工作家人。" +
            "点到为止，一句话结束，越短越有杀伤力。",
        autoSendable = false
    ),

    COLD(
        id = "cold",
        label = "高冷",
        hint = "极简敷衍，让话题降温",
        instruction = "极简、疏离，明确让话题降温。三到八个字，不要解释，不要反问。"
    ),

    PLAYFUL(
        id = "playful",
        label = "俏皮",
        hint = "轻松玩笑，接梗",
        instruction = "轻松、开玩笑、接对方的梗。别太用力，也别显得刻意讨好。"
    ),

    CUSTOM(
        id = "custom",
        label = "自定义",
        hint = "自己写一段风格描述",
        instruction = ""
    );

    companion object {
        fun byId(id: String): Tone? = entries.firstOrNull { it.id == id }

        val DEFAULT_SELECTION: Set<String> = setOf(EQ.id, RATIONAL.id, COLD.id)
    }
}

/** 传给 AiClient 的风格规格。CUSTOM 的 instruction 是用户自己填的，所以单独包一层。 */
data class ToneSpec(
    val id: String,
    val label: String,
    val instruction: String,
    val autoSendable: Boolean
)

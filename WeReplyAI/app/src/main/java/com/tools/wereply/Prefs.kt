package com.tools.wereply

import android.content.Context
import android.content.SharedPreferences

/**
 * 所有配置存在 SharedPreferences 里，没有账号体系，不上传任何数据。
 * 消息内容只会发给你自己填的那个 AI 接口，其余全部留在本机。
 */
object Prefs {

    private const val FILE = "wereply_prefs"

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    // ---------- AI 接口 ----------
    /** OpenAI 兼容格式的地址。DeepSeek / 通义 / Kimi / 智谱 都能用这个格式。 */
    var baseUrl: String
        get() = sp.getString("base_url", "https://api.deepseek.com/chat/completions")!!
        set(v) = sp.edit().putString("base_url", v).apply()

    var apiKey: String
        get() = sp.getString("api_key", "")!!
        set(v) = sp.edit().putString("api_key", v).apply()

    var model: String
        get() = sp.getString("model", "deepseek-chat")!!
        set(v) = sp.edit().putString("model", v).apply()

    // ---------- 人设 ----------
    var persona: String
        get() = sp.getString(
            "persona",
            "24岁，在抖音直播唱歌，性格随和但话不多。说话简短、口语化，" +
                "喜欢用「嗯嗯」「哈哈」「刚下播」这类词，很少用感叹号。"
        )!!
        set(v) = sp.edit().putString("persona", v).apply()

    /**
     * 你自己的微信昵称。整段对话粘进来时靠它分谁是谁 —— 填了最准。
     * 不填也能用：默认把整段最后说话的那个人当成对方（你是在回复他），
     * 判反了面板上有「角色对调」。
     */
    var myNickname: String
        get() = sp.getString("my_nick", "")!!
        set(v) = sp.edit().putString("my_nick", v.trim()).apply()

    // ---------- 准备工作里用户手动确认的项 ----------
    var confirmedAutoStart: Boolean
        get() = sp.getBoolean("ok_autostart", false)
        set(v) = sp.edit().putBoolean("ok_autostart", v).apply()

    var confirmedWxDetail: Boolean
        get() = sp.getBoolean("ok_wxdetail", false)
        set(v) = sp.edit().putBoolean("ok_wxdetail", v).apply()

    fun setConfirmed(key: String, value: Boolean) {
        when (key) {
            "autostart" -> confirmedAutoStart = value
            "wxdetail" -> confirmedWxDetail = value
        }
    }

    // ---------- 表达风格 ----------
    /**
     * 内置段子库：开启时把 ReplyBank 里对应风格的经典素材（土味情话、
     * 舔狗日记式自嘲、高情商话术）注入 system prompt，AI 在贴切时借用或化用。
     */
    var useReplyBank: Boolean
        get() = sp.getBoolean("reply_bank", true)
        set(v) = sp.edit().putBoolean("reply_bank", v).apply()

    /** 勾选的风格 id。选多个 = 每种风格各出一条，横向对照着挑。 */
    var selectedTones: Set<String>
        get() = sp.getStringSet("tones", Tone.DEFAULT_SELECTION)!!
        set(v) = sp.edit().putStringSet("tones", v).apply()

    /** 自定义风格的描述文字，只在勾了「自定义」时用得上。 */
    var customTone: String
        get() = sp.getString("custom_tone", "东北话，自来熟，爱用「咋」「整」「嗷」")!!
        set(v) = sp.edit().putString("custom_tone", v).apply()

    /**
     * 拼给 AiClient 的风格列表。
     * 一个都没勾时兜底成高情商，免得生成不出东西。
     */
    fun activeToneSpecs(): List<ToneSpec> {
        val ids = selectedTones.ifEmpty { setOf(Tone.EQ.id) }
        return Tone.entries
            .filter { it.id in ids }
            .map { t ->
                ToneSpec(
                    id = t.id,
                    label = t.label,
                    instruction = if (t == Tone.CUSTOM) customTone.trim() else t.instruction,
                    autoSendable = t.autoSendable
                )
            }
            .filter { it.instruction.isNotBlank() }
            .ifEmpty {
                listOf(ToneSpec(Tone.EQ.id, Tone.EQ.label, Tone.EQ.instruction, true))
            }
    }

    // ---------- 开关 ----------
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    // ---------- 工作模式 ----------
    const val MODE_CLIPBOARD = "clipboard"
    const val MODE_NOTIFICATION = "notification"

    /**
     * 二选一，默认剪贴板模式（权限最少、零封号风险）。
     * 无障碍模式已经整个删掉，老用户存的旧值自动迁回剪贴板。
     */
    var mode: String
        get() {
            val v = sp.getString("mode", MODE_CLIPBOARD)!!
            return if (v == "accessibility") MODE_CLIPBOARD else v
        }
        set(v) = sp.edit().putString("mode", v).apply()

    val useClipboard: Boolean get() = mode == MODE_CLIPBOARD
    val useNotification: Boolean get() = mode == MODE_NOTIFICATION

    /** 高级模式（通知）在界面上是折叠的，默认不展开 */
    var showAdvanced: Boolean
        get() = sp.getBoolean("show_advanced", false)
        set(v) = sp.edit().putBoolean("show_advanced", v).apply()

    /** true = AI 生成后直接发出；false = 出候选，你点一下才发（默认，推荐） */
    var autoSend: Boolean
        get() = sp.getBoolean("auto_send", false)
        set(v) = sp.edit().putBoolean("auto_send", v).apply()

    var watchWeChat: Boolean
        get() = sp.getBoolean("watch_wechat", true)
        set(v) = sp.edit().putBoolean("watch_wechat", v).apply()

    var watchDouyin: Boolean
        get() = sp.getBoolean("watch_douyin", false)
        set(v) = sp.edit().putBoolean("watch_douyin", v).apply()

    // ---------- 白名单 ----------
    /** 空 = 谁都不回。必须显式把联系人昵称加进来才会处理，避免误伤群聊和工作消息。 */
    var whitelist: Set<String>
        get() = sp.getStringSet("whitelist", emptySet())!!
        set(v) = sp.edit().putStringSet("whitelist", v).apply()

    var whitelistAll: Boolean
        get() = sp.getBoolean("whitelist_all", false)
        set(v) = sp.edit().putBoolean("whitelist_all", v).apply()

    fun allowContact(name: String): Boolean =
        whitelistAll || whitelist.contains(name.trim())

    // ---------- 节流 ----------
    var quietStartHour: Int
        get() = sp.getInt("quiet_start", 1)
        set(v) = sp.edit().putInt("quiet_start", v).apply()

    var quietEndHour: Int
        get() = sp.getInt("quiet_end", 9)
        set(v) = sp.edit().putInt("quiet_end", v).apply()

    var minDelaySec: Int
        get() = sp.getInt("min_delay", 20)
        set(v) = sp.edit().putInt("min_delay", v).apply()

    var maxDelaySec: Int
        get() = sp.getInt("max_delay", 180)
        set(v) = sp.edit().putInt("max_delay", v).apply()

    var maxPerHour: Int
        get() = sp.getInt("max_per_hour", 30)
        set(v) = sp.edit().putInt("max_per_hour", v).apply()

    // ---------- 运行时计数 ----------
    private var hourBucket: Long
        get() = sp.getLong("hour_bucket", 0L)
        set(v) = sp.edit().putLong("hour_bucket", v).apply()

    private var hourCount: Int
        get() = sp.getInt("hour_count", 0)
        set(v) = sp.edit().putInt("hour_count", v).apply()

    /** 超过每小时上限返回 false。秒回 + 24 小时在线是风控最容易抓的特征。 */
    @Synchronized
    fun consumeQuota(): Boolean {
        val bucket = System.currentTimeMillis() / 3_600_000L
        if (bucket != hourBucket) {
            hourBucket = bucket
            hourCount = 0
        }
        if (hourCount >= maxPerHour) return false
        hourCount += 1
        return true
    }

    fun inQuietHours(hour: Int): Boolean {
        val s = quietStartHour
        val e = quietEndHour
        return if (s <= e) hour in s until e else hour >= s || hour < e
    }
}

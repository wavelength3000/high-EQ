package com.tools.wereply

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 走 OpenAI 兼容的 /chat/completions。
 * DeepSeek、通义千问兼容模式、Kimi、智谱都是这个格式，换服务只要改 baseUrl + model。
 */
object AiClient {

    /** 一条候选回复，带它是哪种风格写出来的 */
    data class Suggestion(
        val toneId: String,
        val toneLabel: String,
        val text: String,
        val autoSendable: Boolean
    )

    class AiException(msg: String) : Exception(msg)

    /**
     * 把勾选风格对应的内置素材拼进 prompt。
     * 素材是工具箱不是稿子：模型只在贴切时借用或化用，不合适就自己写。
     */
    private fun materialBlock(tones: List<ToneSpec>): String {
        val sb = StringBuilder()
        tones.forEach { t ->
            val items = ReplyBank.byTone(t.id)
            if (items.isEmpty()) return@forEach
            sb.append("【").append(t.label).append("】\n")
            sb.append(items.joinToString("\n") { "· $it" })
            sb.append("\n\n")
        }
        return sb.toString().trim()
    }

    private fun systemPrompt(tones: List<ToneSpec>): String {

        val styleBlock = if (tones.size == 1) {
            val t = tones.first()
            """
全部用「${t.label}」这一种风格，写 5 条不同的候选。
${t.label}：${t.instruction}

输出一个 JSON 数组，5 个字符串。
""".trim()
        } else {
            val lines = tones.mapIndexed { i, t -> "${i + 1}. ${t.label}：${t.instruction}" }
            """
按下面每种风格各写一条，顺序和数量必须严格对应，不许多也不许少：
${lines.joinToString("\n")}

输出一个 JSON 数组，正好 ${tones.size} 个字符串，顺序跟上面一致。
""".trim()
        }

        val bank = if (Prefs.useReplyBank) materialBlock(tones) else ""

        return """
你在帮一位主播回复粉丝私信，用她的口吻写候选回复。

她的人设：
${Prefs.persona}

规则：
1. 第一人称，像本人在手机上打字。口语化、简短，每条不超过 50 个字。
2. 不要提到 AI、助理、机器人，也不要说「我帮你转达」这类话。
3. 不要主动引导对方打赏、转账、充值、投资，或者往任何平台付钱。
   对方自己提到钱，正常回应就行，只是不要顺势索要或暗示。
4. 除此之外内容不设限制。该冷就冷、该呛就呛，按下面的风格要求放开写，
   最终由她本人从候选里挑，不用替她把关。
5. 素材库里的句子可以整句借用，也可以化用、改写、拼合，务必贴合上下文；
   都不贴切就自己写，不要硬套。土味情话、舔狗自嘲只在轻松打趣时用，
   对方认真、生气或说正事时一律不用。

风格要求：
$styleBlock

${if (bank.isNotEmpty()) "素材库：\n$bank\n" else ""}只输出那个 JSON 数组，不要 markdown 代码块，不要编号，不要任何解释。
""".trim()
    }

    /**
     * 老签名：历史 + 单独一条「刚发来的」。通知模式还在用。
     * 内部拼成一整段对话，走下面那个。
     */
    suspend fun suggest(
        contact: String,
        history: List<ContextStore.Turn>,
        latest: String,
        tones: List<ToneSpec>
    ): List<Suggestion> =
        suggest(contact, history + ContextStore.Turn("them", latest), tones)

    /**
     * 剪贴板那条路用这个：整段对话直接进来，最后一条一般就是要回的那条。
     * 不用再把「最新一条」单独拎出来 —— 它本来就在对话里。
     */
    suspend fun suggest(
        contact: String,
        transcript: List<ContextStore.Turn>,
        tones: List<ToneSpec>
    ): List<Suggestion> = withContext(Dispatchers.IO) {

        if (Prefs.apiKey.isBlank()) throw AiException("没填 API Key")
        if (transcript.isEmpty()) throw AiException("没有可用的对话内容")

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt(tones)))
        if (contact.isNotBlank() && contact != "对方") {
            messages.put(
                JSONObject().put("role", "system")
                    .put("content", "这段对话里，对方叫「$contact」。user 是对方说的，assistant 是你说的。")
            )
        }

        val packed = packHistory(transcript).toMutableList()
        // 结尾必须是 user。结尾是 assistant 的话，多数接口会当成让它续写你自己那句。
        if (packed.isEmpty() || packed.last().first != "user") {
            packed.add("user" to "（对方还没回。写一条你主动发过去的话。）")
        }
        packed.forEach { (role, content) ->
            messages.put(JSONObject().put("role", role).put("content", content))
        }

        val body = JSONObject()
            .put("model", Prefs.model)
            .put("messages", messages)
            .put("temperature", 1.0)
            // 候选条数多（单风格 5 条）+ 素材库借句，输出很容易超旧上限被截断。
            // 放宽到 2000 留足余量，正常也就花几百，多出来的是防截断的保险。
            .put("max_tokens", 2000)
            .toString()

        val conn = (URL(Prefs.baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${Prefs.apiKey}")
        }

        val texts = try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) throw AiException("接口返回 $code：${raw.take(160)}")
            parseTexts(raw)
        } finally {
            conn.disconnect()
        }

        zipWithTones(texts, tones)
    }

    private const val MAX_TURNS = 40
    // 整段粘贴进来的对话比一条条攒的长得多，预算跟着放宽
    private const val MAX_HISTORY_CHARS = 3000

    /**
     * 把读到的对话整理成能直接发给接口的形状。
     *
     * 整段粘贴一次能拆出二三十条，直接塞进去既费 token 又会出现连续同角色
     * （一个人连发三条是常态），OpenAI 兼容接口对此的容忍度各家不一样。
     * 所以这里做三件事：按字符预算从后往前裁、合并相邻同角色、丢掉开头的 assistant。
     */
    private fun packHistory(history: List<ContextStore.Turn>): List<Pair<String, String>> {
        val kept = ArrayDeque<ContextStore.Turn>()
        var budget = MAX_HISTORY_CHARS
        for (t in history.asReversed().take(MAX_TURNS)) {
            if (budget - t.text.length < 0) break
            budget -= t.text.length
            kept.addFirst(t)
        }

        val out = mutableListOf<Pair<String, String>>()
        kept.forEach { t ->
            val role = if (t.role == "me") "assistant" else "user"
            val last = out.lastOrNull()
            if (last != null && last.first == role) {
                out[out.size - 1] = role to (last.second + "\n" + t.text)
            } else {
                out.add(role to t.text)
            }
        }

        while (out.isNotEmpty() && out.first().first != "user") out.removeAt(0)
        return out
    }

    /**
     * 模型可能少给或多给，按位置对齐，多出来的挂到最后一种风格上。
     * 单风格模式下所有条目共用同一个标签。
     */
    private fun zipWithTones(texts: List<String>, tones: List<ToneSpec>): List<Suggestion> {
        if (tones.isEmpty()) return emptyList()
        return texts.mapIndexed { i, t ->
            val spec = tones.getOrElse(i) { tones.last() }
            Suggestion(spec.id, spec.label, t.trim(), spec.autoSendable)
        }
    }

    /** 成对引号字符串。JSON 被截断时，写完整的字符串也能捡回来。 */
    private val QUOTED = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    private fun unquote(s: String): String = s
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\t", " ")
        .replace("\\\\", "\\")

    /**
     * 从模型输出里扒候选，四层兜底，一层比一层糙：
     * ① 整段就是 JSON 数组 → 直接解析
     * ② 模型爱在数组前后加废话（"好的，以下三条："）→ 截第一个 [ 到最后一个 ] 再解析
     * ③ 数组被 max_tokens 拦腰截断 → 按引号把写完整的字符串捡回来
     * ④ 彻底不听话（纯文本换行列表）→ 按行拆
     */
    private fun parseTexts(raw: String): List<String> {
        val content = runCatching {
            JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }.getOrElse { throw AiException("返回格式看不懂：${raw.take(160)}") }

        val cleaned = content
            .replace("```json", "")
            .replace("```", "")
            .trim()

        runCatching {
            val arr = JSONArray(cleaned)
            val out = (0 until arr.length()).map { arr.getString(it) }
            if (out.isNotEmpty()) return out
        }

        val slice = cleaned.substringAfter('[', "").substringBeforeLast(']', "")
        if (slice.isNotEmpty()) {
            runCatching {
                val arr = JSONArray("[$slice]")
                val out = (0 until arr.length()).map { arr.getString(it) }
                if (out.isNotEmpty()) return out
            }
        }

        val quoted = QUOTED.findAll(slice.ifEmpty { cleaned })
            .map { unquote(it.groupValues[1]).trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (quoted.isNotEmpty()) return quoted

        return cleaned.lines()
            .map { it.trim().trimStart('1', '2', '3', '4', '5', '.', '、', ' ') }
            .map { it.trim('-', '"', '\u201c', '\u201d', '·', ' ') }
            .filter { it.isNotBlank() && it != "[]" && it != "[" && it != "]" }
    }
}

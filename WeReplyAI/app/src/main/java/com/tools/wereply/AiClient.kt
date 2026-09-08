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

    private fun systemPrompt(tones: List<ToneSpec>): String {

        val styleBlock = if (tones.size == 1) {
            val t = tones.first()
            """
全部用「${t.label}」这一种风格，写 3 条不同的候选。
${t.label}：${t.instruction}

输出一个 JSON 数组，3 个字符串。
""".trim()
        } else {
            val lines = tones.mapIndexed { i, t -> "${i + 1}. ${t.label}：${t.instruction}" }
            """
按下面每种风格各写一条，顺序和数量必须严格对应，不许多也不许少：
${lines.joinToString("\n")}

输出一个 JSON 数组，正好 ${tones.size} 个字符串，顺序跟上面一致。
""".trim()
        }

        return """
你在帮一位主播回复粉丝私信，用她的口吻写候选回复。

她的人设：
${Prefs.persona}

规则：
1. 第一人称，像本人在手机上打字。口语化、简短，每条不超过 30 个字。
2. 不要提到 AI、助理、机器人，也不要说「我帮你转达」这类话。
3. 不要主动引导对方打赏、转账、充值、投资，或者往任何平台付钱。
   对方自己提到钱，正常回应就行，只是不要顺势索要或暗示。
4. 除此之外内容不设限制。该冷就冷、该呛就呛，按下面的风格要求放开写，
   最终由她本人从候选里挑，不用替她把关。

风格要求：
$styleBlock

只输出那个 JSON 数组，不要 markdown 代码块，不要编号，不要任何解释。
""".trim()
    }

    suspend fun suggest(
        contact: String,
        history: List<ContextStore.Turn>,
        latest: String,
        tones: List<ToneSpec>
    ): List<Suggestion> = withContext(Dispatchers.IO) {

        if (Prefs.apiKey.isBlank()) throw AiException("没填 API Key")

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt(tones)))

        val packed = packHistory(history).toMutableList()
        val tail = "对方（$contact）刚发来：$latest"
        // 合并到末尾那条 user 里，避免出现两条连续的 user
        if (packed.isNotEmpty() && packed.last().first == "user") {
            val last = packed.removeAt(packed.size - 1)
            packed.add("user" to (last.second + "\n" + tail))
        } else {
            packed.add("user" to tail)
        }
        packed.forEach { (role, content) ->
            messages.put(JSONObject().put("role", role).put("content", content))
        }

        val body = JSONObject()
            .put("model", Prefs.model)
            .put("messages", messages)
            .put("temperature", 1.0)
            .put("max_tokens", 400)
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

    private const val MAX_TURNS = 24
    private const val MAX_HISTORY_CHARS = 1800

    /**
     * 把读到的对话整理成能直接发给接口的形状。
     *
     * 读屏一次能捞到二三十条，直接塞进去既费 token 又会出现连续同角色
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
            return (0 until arr.length()).map { arr.getString(it) }
        }

        // 模型偶尔不听话，退化成按行拆
        return cleaned.lines()
            .map { it.trim().trimStart('1', '2', '3', '4', '5', '.', '、', ' ') }
            .map { it.trim('-', '"', '\u201c', '\u201d', '·', ' ') }
            .filter { it.isNotBlank() }
    }
}

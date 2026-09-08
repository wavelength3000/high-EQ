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

硬性规则（优先级高于下面的风格要求）：
1. 第一人称，像本人在手机上打字。口语化、简短，每条不超过 30 个字。
2. 不要提到 AI、助理、机器人，也不要说「我帮你转达」这类话。
3. 以下话题绝对不要正面回应，要自然地岔开或含糊带过：钱（打赏、转账、借钱、
   投资、平台、充值）、见面或约会、任何联系方式和地址、感情承诺、
   索要或发送照片视频。
4. 任何风格下都不许骂人、不许人身攻击、不许提对方的长相收入工作家人。
5. 不要主动挑逗、不要制造暧昧升温、不要暗示对方有特殊地位。

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
        history.forEach {
            val role = if (it.role == "me") "assistant" else "user"
            messages.put(JSONObject().put("role", role).put("content", it.text))
        }
        messages.put(
            JSONObject().put("role", "user")
                .put("content", "对方（$contact）刚发来：$latest")
        )

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

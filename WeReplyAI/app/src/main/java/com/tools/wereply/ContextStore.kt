package com.tools.wereply

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知栏拿不到聊天历史，只能自己一条条攒。
 * 每个联系人保留最近 MAX_TURNS 条，超出的丢掉，避免 prefs 无限膨胀、也避免 token 花冤枉钱。
 */
object ContextStore {

    private const val FILE = "wereply_ctx"
    // 整段粘贴进来的对话动辄二三十条，16 会把开头截掉
    private const val MAX_TURNS = 40

    data class Turn(val role: String, val text: String)

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(contact: String) = "c_" + contact.trim()

    fun load(ctx: Context, contact: String): List<Turn> {
        val raw = sp(ctx).getString(key(contact), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Turn(o.getString("r"), o.getString("t"))
            }
        }.getOrDefault(emptyList())
    }

    fun append(ctx: Context, contact: String, role: String, text: String) {
        save(ctx, contact, load(ctx, contact) + Turn(role, text))
    }

    /**
     * 整份覆盖。攒对话的缓冲区要删条、要改角色、要整段替换，
     * 光有 append 不够用。
     */
    fun save(ctx: Context, contact: String, turns: List<Turn>) {
        val list = turns.takeLast(MAX_TURNS)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("r", it.role).put("t", it.text)) }
        sp(ctx).edit().putString(key(contact), arr.toString()).apply()
    }

    fun clear(ctx: Context, contact: String) {
        sp(ctx).edit().remove(key(contact)).apply()
    }

    fun clearAll(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }
}

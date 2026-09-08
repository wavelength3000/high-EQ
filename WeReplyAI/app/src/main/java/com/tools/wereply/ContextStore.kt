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
    private const val MAX_TURNS = 16

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
        val list = (load(ctx, contact) + Turn(role, text)).takeLast(MAX_TURNS)
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

package com.tools.wereply

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class SendReplyReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CONTACT = "contact"
        const val EXTRA_NOTIF_ID = "nid"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Prefs.init(ctx)

        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val contact = intent.getStringExtra(EXTRA_CONTACT) ?: ""
        val nid = intent.getIntExtra(EXTRA_NOTIF_ID, 0)

        // 再过一遍出口规则，防止用户手滑点到不该发的
        if (!SensitiveFilter.isOutgoingSafe(text)) {
            Toast.makeText(ctx, "这条被规则拦下了，没发出去", Toast.LENGTH_SHORT).show()
            return
        }

        val ok = ReplySender.send(ctx, key, text)
        if (ok) {
            ContextStore.append(ctx, contact, "me", text)
            ctx.getSystemService(NotificationManager::class.java).cancel(nid)
        } else {
            Toast.makeText(
                ctx,
                "发送失败：原通知已被清除，回复通道失效了，得手动回",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

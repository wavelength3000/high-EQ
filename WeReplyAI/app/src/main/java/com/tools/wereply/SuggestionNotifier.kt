package com.tools.wereply

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

object SuggestionNotifier {

    private const val CH_SUGGEST = "suggest"
    private const val CH_ALERT = "alert"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SUGGEST, "候选回复", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "需要你自己回", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** 候选回复：点哪条就发哪条 */
    fun showSuggestions(
        ctx: Context,
        notifKey: String,
        contact: String,
        incoming: String,
        candidates: List<AiClient.Suggestion>
    ) {
        ensureChannels(ctx)
        val id = notifKey.hashCode() and 0x7fffffff

        val builder = Notification.Builder(ctx, CH_SUGGEST)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("$contact：$incoming")
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(candidates.joinToString("\n") { "【${it.toneLabel}】${it.text}" })
            )
            .setAutoCancel(true)

        candidates.forEachIndexed { i, sug ->
            val intent = Intent(ctx, SendReplyReceiver::class.java).apply {
                action = "com.tools.wereply.SEND"
                putExtra(SendReplyReceiver.EXTRA_KEY, notifKey)
                putExtra(SendReplyReceiver.EXTRA_TEXT, sug.text)
                putExtra(SendReplyReceiver.EXTRA_CONTACT, contact)
                putExtra(SendReplyReceiver.EXTRA_NOTIF_ID, id)
            }
            val pi = PendingIntent.getBroadcast(
                ctx,
                id * 10 + i,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.ic_menu_send),
                    sug.toneLabel,
                    pi
                ).build()
            )
        }

        ctx.getSystemService(NotificationManager::class.java).notify(id, builder.build())
    }

    /** 命中敏感词或出错时，只提醒，不给候选 */
    fun showAlert(ctx: Context, contact: String, reason: String) {
        ensureChannels(ctx)
        val n = Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("$contact 这条需要你自己回")
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)
            .notify((contact + reason).hashCode() and 0x7fffffff, n)
    }
}

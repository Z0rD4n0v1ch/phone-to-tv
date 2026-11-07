package com.example.phonetotv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Автозапуск приёма при включении приставки (если включён кнопкой «Автозапуск» на экране ожидания).
 * На Android 10+ для этого нужно разрешение «Поверх других приложений» — его запрашивает ReceiverActivity.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val PREFS = "tv"
        private const val KEY = "autostart"

        fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return
        if (!isEnabled(context)) return
        try {
            context.startActivity(
                Intent(context, ReceiverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // Система запретила запуск из фона — откроется вручную.
        }
    }
}

package com.example.phonetotv

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Точка входа. Одно приложение работает в двух режимах:
 *  - «Передавать» — этот телефон показывает свой экран на ТВ (SenderActivity);
 *  - «Принимать» — это устройство служит экраном (ReceiverActivity).
 * На ТВ-приставке сразу открывается приём. На телефоне — выбранный режим или экран выбора.
 */
class LauncherActivity : Activity() {

    companion object {
        const val EXTRA_CHOOSE = "choose"
        private const val KEY_MODE = "mode"
        private const val MODE_SEND = "send"
        private const val MODE_RECEIVE = "receive"

        fun isTv(context: Context): Boolean {
            val ui = context.getSystemService(UiModeManager::class.java)
            return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }

        /** Открыть экран выбора режима (кнопка «Сменить режим»). */
        fun chooseMode(activity: Activity) {
            activity.startActivity(Intent(activity, LauncherActivity::class.java).putExtra(EXTRA_CHOOSE, true))
            activity.finish()
        }
    }

    private val prefs by lazy { getSharedPreferences(CastService.PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forceChoose = intent.getBooleanExtra(EXTRA_CHOOSE, false)
        if (!forceChoose) {
            when {
                CastService.isRunning -> return open(MODE_SEND) // идёт трансляция — показываем её экран
                isTv(this) -> return open(MODE_RECEIVE)
                prefs.getString(KEY_MODE, null) == MODE_SEND -> return open(MODE_SEND)
                prefs.getString(KEY_MODE, null) == MODE_RECEIVE -> return open(MODE_RECEIVE)
            }
        }
        title = "Экран на ТВ"
        showChooser()
    }

    private fun open(mode: String) {
        val cls = if (mode == MODE_RECEIVE) ReceiverActivity::class.java else SenderActivity::class.java
        startActivity(Intent(this, cls))
        finish()
    }

    private fun showChooser() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Что будет делать это устройство?"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val remember = CheckBox(this).apply {
            text = "Запомнить выбор"
            isChecked = true
        }

        fun choice(title: String, hint: String, mode: String) {
            root.addView(Button(this).apply {
                text = title
                textSize = 18f
                setPadding(pad, pad, pad, pad)
                setOnClickListener {
                    if (remember.isChecked) prefs.edit().putString(KEY_MODE, mode).apply()
                    else prefs.edit().remove(KEY_MODE).apply()
                    open(mode)
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad / 2 })
            root.addView(TextView(this).apply {
                text = hint
                textSize = 13f
                alpha = 0.7f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        choice("📱 → 📺  Передавать экран", "Этот телефон показывает свой экран на телевизоре", MODE_SEND)
        choice("📺  Принимать", "Это устройство служит экраном: сюда транслирует другой телефон", MODE_RECEIVE)

        root.addView(remember, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = pad })
        root.addView(TextView(this).apply {
            text = "На ТВ-приставке режим приёма включается сам. Сменить режим можно в любой момент."
            textSize = 12f
            alpha = 0.6f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(ScrollView(this).apply { addView(root) })
    }
}

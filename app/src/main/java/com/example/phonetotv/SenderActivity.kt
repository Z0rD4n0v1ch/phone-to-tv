package com.example.phonetotv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class SenderActivity : Activity() {

    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIFICATIONS = 2
    }

    private lateinit var hostInput: EditText
    private lateinit var findButton: Button
    private lateinit var castButton: Button
    private lateinit var statusText: TextView
    private lateinit var resSpinner: Spinner
    private lateinit var bitrateSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var modeButton: Button

    private val prefs by lazy { getSharedPreferences(CastService.PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Экран на ТВ"

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(label("IP-адрес ТВ-приставки (показан на экране приставки):"))
        hostInput = EditText(this).apply {
            hint = "например, 192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(prefs.getString(CastService.KEY_HOST, ""))
        }
        root.addView(hostInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        findButton = Button(this).apply {
            text = "Найти приставку в сети"
            setOnClickListener { discoverReceiver() }
        }
        root.addView(findButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ----- качество -----
        val (resIdx, bitrateIdx, fpsIdx) = Quality.loadIndexes(prefs)
        root.addView(label("Разрешение:").apply { setPadding(0, pad, 0, 0) })
        resSpinner = spinner(Quality.RESOLUTIONS, resIdx)
        root.addView(resSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(label("Битрейт (качество картинки):"))
        bitrateSpinner = spinner(Quality.BITRATES, bitrateIdx)
        root.addView(bitrateSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(label("Частота кадров:"))
        fpsSpinner = spinner(Quality.FPS, fpsIdx)
        root.addView(fpsSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = "Если картинка дёргается или отстаёт — уменьшите разрешение или битрейт. " +
                "Если кодер телефона не тянет выбранное качество, оно снизится само."
            textSize = 12f
            alpha = 0.7f
        })

        castButton = Button(this).apply {
            setOnClickListener { onCastClicked() }
        }
        root.addView(castButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = pad
        })

        statusText = TextView(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            textSize = 16f
            setPadding(0, pad, 0, 0)
        }
        root.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        modeButton = Button(this).apply {
            text = "Сменить режим (принимать на этом устройстве)"
            isAllCaps = false
            alpha = 0.8f
            setOnClickListener { LauncherActivity.chooseMode(this@SenderActivity) }
        }
        root.addView(modeButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = pad * 2
        })

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    private fun label(text: String) = TextView(this).apply { this.text = text }

    private fun spinner(options: List<Quality.Option>, selected: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@SenderActivity, android.R.layout.simple_spinner_dropdown_item, options.map { it.label }
        )
        setSelection(selected)
    }

    override fun onResume() {
        super.onResume()
        CastService.statusListener = { updateUi(it) }
        updateUi(CastService.lastStatus)
    }

    override fun onPause() {
        CastService.statusListener = null
        super.onPause()
    }

    private fun updateUi(status: String) {
        statusText.text = status
        val running = CastService.isRunning
        castButton.text = if (running) "Остановить трансляцию" else "Начать трансляцию"
        hostInput.isEnabled = !running
        findButton.isEnabled = !running
        resSpinner.isEnabled = !running
        bitrateSpinner.isEnabled = !running
        fpsSpinner.isEnabled = !running
        modeButton.isEnabled = !running
        // Сервис мог сменить IP после поиска — показываем актуальный.
        if (running) {
            val saved = prefs.getString(CastService.KEY_HOST, "")
            if (!saved.isNullOrEmpty() && saved != hostInput.text.toString()) hostInput.setText(saved)
        }
    }

    private fun onCastClicked() {
        if (CastService.isRunning) {
            CastService.stop(this)
            return
        }
        val host = hostInput.text.toString().trim()
        if (host.isEmpty()) {
            Toast.makeText(this, "Введите IP-адрес приставки", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString(CastService.KEY_HOST, host).apply()
        Quality.saveIndexes(
            prefs, resSpinner.selectedItemPosition, bitrateSpinner.selectedItemPosition, fpsSpinner.selectedItemPosition
        )
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            updateUi("Захват экрана не разрешён")
            return
        }
        val quality = Quality.fromIndexes(
            resSpinner.selectedItemPosition, bitrateSpinner.selectedItemPosition, fpsSpinner.selectedItemPosition
        )
        CastService.start(this, resultCode, data, hostInput.text.toString().trim(), quality)
        updateUi("Запуск…")
    }

    /** Ищет приставку UDP-широковещанием в локальной сети. */
    private fun discoverReceiver() {
        findButton.isEnabled = false
        statusText.text = "Поиск приставки…"
        thread(name = "discovery") {
            val found = try {
                Discovery.find()
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Ошибка поиска: ${e.message}" }
                null
            }
            runOnUiThread {
                findButton.isEnabled = !CastService.isRunning
                if (found != null) {
                    hostInput.setText(found.ip)
                    prefs.edit().putString(CastService.KEY_HOST, found.ip).apply()
                    statusText.text = "Найдено: ${found.name} (${found.ip})"
                } else if (!statusText.text.startsWith("Ошибка")) {
                    statusText.text = "Приставка не найдена. Убедитесь, что приложение на ней запущено " +
                        "и оба устройства в одной Wi‑Fi сети, или введите IP вручную."
                }
            }
        }
    }
}

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
import android.view.View
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
    private lateinit var updPanel: LinearLayout
    private lateinit var updText: TextView
    private lateinit var updButton: Button
    private lateinit var updNotesButton: Button
    private lateinit var versionButton: Button
    private val updListener: () -> Unit = { renderUpdate() }

    private val prefs by lazy { getSharedPreferences(CastService.PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Экран на ТВ"

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // ----- плашка обновления (как в Органайзере) -----
        updPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            setBackgroundColor(0xFFFFF1DE.toInt())
            visibility = View.GONE
        }
        updText = TextView(this).apply { textSize = 15f; setTextColor(0xFF3A2A12.toInt()) }
        updPanel.addView(updText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val updRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        updButton = Button(this).apply { isAllCaps = false; setOnClickListener { onUpdateClicked() } }
        updNotesButton = Button(this).apply {
            text = "Что нового"
            isAllCaps = false
            setOnClickListener { Updater.showNotes(this@SenderActivity) { onUpdateClicked() } }
        }
        updRow.addView(updButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        updRow.addView(updNotesButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        updPanel.addView(updRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(updPanel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = pad })

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

        versionButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            isAllCaps = false
            textSize = 13f
            alpha = 0.7f
            setOnClickListener { Updater.check(this@SenderActivity, manual = true) }
        }
        root.addView(versionButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
        Updater.showWhatsNewIfUpdated(this)
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
        Updater.addListener(updListener)
        Updater.onResume(this)
        renderUpdate()
    }

    override fun onPause() {
        CastService.statusListener = null
        Updater.removeListener(updListener)
        super.onPause()
    }

    // ---------- обновление ----------

    private fun onUpdateClicked() {
        if (CastService.isRunning) {
            Toast.makeText(this, "Сначала остановите трансляцию", Toast.LENGTH_SHORT).show()
            return
        }
        Updater.install(this)
    }

    private fun renderUpdate() {
        val r = Updater.release
        val current = Updater.currentVersion(this)
        versionButton.text = when (Updater.stage) {
            Updater.Stage.CHECKING -> "Версия $current · проверяю обновления…"
            Updater.Stage.UP_TO_DATE -> "Версия $current — последняя · проверить ещё раз"
            Updater.Stage.ERROR -> if (r == null) "Версия $current · ${Updater.message}" else "Версия $current"
            else -> "Версия $current · проверить обновления"
        }
        if (r == null) {
            updPanel.visibility = View.GONE
            return
        }
        updPanel.visibility = View.VISIBLE
        val running = CastService.isRunning
        updNotesButton.visibility = if (Updater.notesLines(r.notes).isEmpty()) View.GONE else View.VISIBLE
        when (Updater.stage) {
            Updater.Stage.DOWNLOADING -> {
                updText.text = "Обновляю до ${r.version}: скачиваю… ${Updater.progress}%"
                updButton.text = "Подождите…"
                updButton.isEnabled = false
            }
            Updater.Stage.INSTALLING -> {
                updText.text = "Устанавливаю ${r.version}. Подтвердите установку в окне Android."
                updButton.text = "Подождите…"
                updButton.isEnabled = false
            }
            Updater.Stage.PERMISSION -> {
                updText.text = Updater.message.ifEmpty {
                    "Нужно разрешение: включите «Разрешить установку из этого источника» и вернитесь — установка продолжится сама."
                }
                updButton.text = "Открыть настройки"
                updButton.isEnabled = true
            }
            Updater.Stage.ERROR -> {
                updText.text = "Не удалось обновить до ${r.version}" +
                    (if (Updater.message.isNotEmpty()) " (${Updater.message})" else "")
                updButton.text = "Ещё раз"
                updButton.isEnabled = !running
            }
            else -> {
                updText.text = "Доступна версия ${r.version}" + (if (Updater.fetched) " — готова к установке" else "") +
                    (if (running) "\nОбновить можно после остановки трансляции." else "")
                updButton.text = "Обновить"
                updButton.isEnabled = !running
            }
        }
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
        if (::updButton.isInitialized) renderUpdate()
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

package com.example.phonetotv

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

/**
 * Протокол «Экрана на ТВ», версия 3. TCP :5000, всё big-endian (DataInput/OutputStream).
 *
 * Рукопожатие (телефон → приставка):
 *   int MAGIC, UTF id телефона, UTF имя телефона, UTF PIN (может быть пустым), int width, int height
 * Ответ (приставка → телефон):
 *   int статус (HS_*), UTF текст для пользователя
 *
 * Дальше телефон шлёт пакеты:   int flags, long ptsUs, int size, byte[size]
 *   FLAG_CONFIG    — SPS/PPS кодека H.264
 *   FLAG_KEY_FRAME — ключевой кадр
 *   FLAG_RESIZE    — новый размер (поворот): int width, int height; затем новый CONFIG и ключевой кадр
 *   FLAG_AUDIO     — звук: PCM 16 бит, 48 кГц, стерео
 *   FLAG_PING      — long время отправки (для замера задержки)
 *   FLAG_STATS     — int битрейт (бит/с), int задержка сети (мс), int кадров/с (настройка), int звук (0/1)
 *
 * Обратный канал (приставка → телефон):  int тип, long значение
 *   MSG_PONG — эхо FLAG_PING;  MSG_KEY — команда с пульта (KEY_*)
 *
 * Поиск: UDP :5001, запрос DISCOVER_REQUEST, ответ "DISCOVER_REPLY_PREFIX<имя>".
 */
object Protocol {
    const val MAGIC = 0x50545633 // "PTV3"
    const val MAGIC_V2 = 0x50545632 // "PTV2" — версии 1.1–1.3
    const val MAGIC_V1 = 0x50545643 // "PTVC" — версия 1.0
    const val VIDEO_PORT = 5000
    const val DISCOVERY_PORT = 5001
    const val DISCOVER_REQUEST = "PTV_DISCOVER"
    const val DISCOVER_REPLY_PREFIX = "PTV_HERE:"

    const val FLAG_CONFIG = 1
    const val FLAG_KEY_FRAME = 2
    const val FLAG_RESIZE = 4
    const val FLAG_AUDIO = 8
    const val FLAG_PING = 16
    const val FLAG_STATS = 32

    const val HS_OK = 0
    const val HS_NEED_PIN = 1
    const val HS_WRONG_PIN = 2
    const val HS_DECLINED = 3
    const val HS_OLD_VERSION = 4

    const val MSG_PONG = 1
    const val MSG_KEY = 2

    const val KEY_PLAY_PAUSE = 1
    const val KEY_REWIND = 2
    const val KEY_FORWARD = 3
    const val KEY_VOLUME_UP = 4
    const val KEY_VOLUME_DOWN = 5

    const val AUDIO_RATE = 48000
    const val AUDIO_CHANNELS = 2

    /** Постоянный id этого устройства (для «доверенных» телефонов на приставке). */
    fun deviceId(ctx: Context): String {
        val p = ctx.getSharedPreferences("identity", Context.MODE_PRIVATE)
        p.getString("id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString("id", id).apply()
        return id
    }

    /** Понятное имя устройства: «Redmi Note 13» или имя, заданное в настройках. */
    fun deviceName(ctx: Context): String {
        val name = if (Build.VERSION.SDK_INT >= 25) {
            try { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null }
        } else null
        if (!name.isNullOrBlank()) return name
        val model = Build.MODEL ?: "Android"
        val maker = Build.MANUFACTURER ?: ""
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }
}

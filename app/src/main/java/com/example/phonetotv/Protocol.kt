package com.example.phonetotv

/**
 * Протокол поверх TCP (big-endian, DataOutputStream). Используется и при передаче, и при приёме.
 *
 *   Заголовок:  int MAGIC, int width, int height
 *   Пакет:      int flags, long ptsUs, int size, byte[size]
 *
 * flags:
 *   FLAG_CONFIG    — SPS/PPS кодека (H.264 Annex-B)
 *   FLAG_KEY_FRAME — ключевой кадр
 *   FLAG_RESIZE    — размер видео изменился (поворот экрана); данные: int width, int height.
 *                    После него идёт новый FLAG_CONFIG и ключевой кадр.
 *
 * Обнаружение: телефон шлёт UDP-широковещание DISCOVER_REQUEST на DISCOVERY_PORT,
 * приставка отвечает строкой "DISCOVER_REPLY_PREFIX<имя устройства>".
 */
object Protocol {
    const val MAGIC = 0x50545632 // "PTV2" — вторая версия протокола (с поворотом экрана)
    const val MAGIC_V1 = 0x50545643 // "PTVC" — первая версия (два отдельных приложения)
    const val VIDEO_PORT = 5000
    const val DISCOVERY_PORT = 5001
    const val DISCOVER_REQUEST = "PTV_DISCOVER"
    const val DISCOVER_REPLY_PREFIX = "PTV_HERE:"

    const val FLAG_CONFIG = 1
    const val FLAG_KEY_FRAME = 2
    const val FLAG_RESIZE = 4
}

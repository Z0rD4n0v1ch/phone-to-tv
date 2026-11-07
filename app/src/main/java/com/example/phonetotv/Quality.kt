package com.example.phonetotv

import android.content.SharedPreferences

/** Настройки качества трансляции. */
data class Quality(val maxSide: Int, val bitRate: Int, val fps: Int) {

    class Option(val label: String, val value: Int)

    companion object {
        val RESOLUTIONS = listOf(
            Option("480p — для слабого Wi‑Fi", 854),
            Option("720p — оптимально", 1280),
            Option("1080p — Full HD", 1920)
        )
        val BITRATES = listOf(
            Option("2 Мбит/с — экономно", 2_000_000),
            Option("4 Мбит/с", 4_000_000),
            Option("6 Мбит/с — оптимально", 6_000_000),
            Option("10 Мбит/с", 10_000_000),
            Option("16 Мбит/с — максимум", 16_000_000)
        )
        val FPS = listOf(
            Option("30 кадров/с", 30),
            Option("60 кадров/с — плавнее", 60)
        )

        const val DEFAULT_RES = 1
        const val DEFAULT_BITRATE = 2
        const val DEFAULT_FPS = 0

        private const val KEY_RES = "q_res"
        private const val KEY_BITRATE = "q_bitrate"
        private const val KEY_FPS = "q_fps"

        /** Индексы выбранных пунктов: разрешение, битрейт, кадры. */
        fun loadIndexes(p: SharedPreferences) = Triple(
            p.getInt(KEY_RES, DEFAULT_RES).coerceIn(RESOLUTIONS.indices),
            p.getInt(KEY_BITRATE, DEFAULT_BITRATE).coerceIn(BITRATES.indices),
            p.getInt(KEY_FPS, DEFAULT_FPS).coerceIn(FPS.indices)
        )

        fun saveIndexes(p: SharedPreferences, res: Int, bitrate: Int, fps: Int) {
            p.edit().putInt(KEY_RES, res).putInt(KEY_BITRATE, bitrate).putInt(KEY_FPS, fps).apply()
        }

        fun fromIndexes(res: Int, bitrate: Int, fps: Int) =
            Quality(RESOLUTIONS[res].value, BITRATES[bitrate].value, FPS[fps].value)
    }
}

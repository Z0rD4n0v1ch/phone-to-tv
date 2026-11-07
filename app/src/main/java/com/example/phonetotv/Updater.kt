package com.example.phonetotv

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Обновление приложения из релизов GitHub — как в «Органайзере монтажника»:
 *  - проверка при запуске, при возврате в приложение и раз в час (не чаще, чем раз в CHECK_EVERY_MS);
 *  - найденный APK заранее тихо скачивается, поэтому «Обновить» сразу открывает установку;
 *  - установка через системный PackageInstaller; если нет разрешения — открываются настройки,
 *    а после возврата установка продолжается сама;
 *  - после обновления один раз показывается «Что нового».
 * Все слушатели вызываются в главном потоке.
 */
object Updater {

    private const val REPO = "Z0rD4n0v1ch/phone-to-tv"
    private const val UA = "phone-to-tv-app"
    private const val CHECK_EVERY_MS = 30 * 60 * 1000L
    const val ACTION_INSTALL_STATUS = "com.example.phonetotv.INSTALL_STATUS"

    private const val PREFS = "updater"
    private const val KEY_SEEN = "seen_version"
    private const val KEY_NOTES_VER = "notes_version"
    private const val KEY_NOTES = "notes"

    enum class Stage { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, PERMISSION, INSTALLING, ERROR }

    data class Release(val version: String, val notes: String, val url: String, val size: Long)

    @Volatile var stage = Stage.IDLE
        private set
    @Volatile var release: Release? = null
        private set
    @Volatile var progress = 0
        private set
    @Volatile var message = ""
        private set
    /** APK новой версии уже скачан заранее. */
    @Volatile var fetched = false
        private set

    private var lastCheck = 0L
    @Volatile private var busy = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun publish(newStage: Stage, msg: String = message) {
        main.post {
            stage = newStage
            message = msg
            listeners.toList().forEach { it() }
        }
    }

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    /** Есть ли обновление, которое можно ставить (или уже ставится). */
    val hasUpdate: Boolean
        get() = release != null && stage in setOf(Stage.AVAILABLE, Stage.DOWNLOADING, Stage.PERMISSION, Stage.INSTALLING, Stage.ERROR)

    // ---------- Проверка ----------

    /** Вызывать из onResume: проверка по расписанию или продолжение установки после выдачи разрешения. */
    fun onResume(activity: Activity) {
        if (stage == Stage.PERMISSION && canInstall(activity)) {
            install(activity)
            return
        }
        if (SystemClock.elapsedRealtime() - lastCheck > CHECK_EVERY_MS || lastCheck == 0L) check(activity, manual = false)
    }

    fun check(ctx: Context, manual: Boolean) {
        if (stage == Stage.CHECKING || busy) return
        if (!manual && stage in setOf(Stage.DOWNLOADING, Stage.INSTALLING, Stage.PERMISSION)) return
        val app = ctx.applicationContext
        lastCheck = SystemClock.elapsedRealtime()
        publish(Stage.CHECKING)
        thread(name = "update-check") {
            try {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", UA)
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                val code = c.responseCode
                if (code == 404) { // релизов ещё нет
                    release = null
                    publish(Stage.UP_TO_DATE, "")
                    return@thread
                }
                if (code != 200) throw Exception("HTTP $code")
                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val version = json.optString("tag_name").removePrefix("v").ifEmpty { json.optString("name") }
                val notes = json.optString("body").substringBefore("\n---").trim()
                val assets = json.optJSONArray("assets")
                var url = ""
                var size = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            url = a.optString("browser_download_url")
                            size = a.optLong("size")
                            break
                        }
                    }
                }
                val current = currentVersion(app)
                if (url.isNotEmpty() && compareVersions(version, current) > 0) {
                    val fresh = release?.version != version
                    release = Release(version, notes, url, size)
                    if (fresh) fetched = false
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_NOTES_VER, version).putString(KEY_NOTES, notes).apply()
                    publish(Stage.AVAILABLE, "")
                    if (!fetched) prefetch(app)
                } else {
                    release = null
                    publish(Stage.UP_TO_DATE, "")
                }
            } catch (e: Exception) {
                // Без интернета молчим; ошибку показываем только при ручной проверке.
                publish(if (release != null) Stage.AVAILABLE else if (manual) Stage.ERROR else Stage.IDLE,
                    if (manual) "Не удалось проверить обновления. Проверьте интернет." else "")
            }
        }
    }

    /** Сравнивает версии вида 1.2.3. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    // ---------- Загрузка ----------

    private fun apkFile(ctx: Context, version: String): File {
        val dir = File(ctx.cacheDir, "update")
        dir.mkdirs()
        return File(dir, "PhoneToTv-$version.apk")
    }

    /** Тихо скачивает APK заранее. */
    private fun prefetch(app: Context) {
        val r = release ?: return
        val f = apkFile(app, r.version)
        if (f.exists() && f.length() > 0 && (r.size == 0L || f.length() == r.size)) {
            fetched = true
            publish(stage)
            return
        }
        if (busy) return
        busy = true
        thread(name = "update-prefetch") {
            try {
                download(r, f) { }
                fetched = true
                publish(stage)
            } catch (_: Exception) {
                fetched = false
            } finally {
                busy = false
            }
        }
    }

    private fun download(r: Release, target: File, onProgress: (Int) -> Unit) {
        target.parentFile?.listFiles()?.forEach { if (it.name != target.name) it.delete() }
        val tmp = File(target.path + ".part")
        try {
            val c = URL(r.url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", UA)
            c.instanceFollowRedirects = true
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
            val total = c.contentLength.toLong().let { if (it > 0) it else r.size }
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw Exception("обрыв загрузки")
            if (!tmp.renameTo(target)) throw Exception("не удалось сохранить файл")
        } finally {
            tmp.delete()
        }
    }

    // ---------- Установка ----------

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Скачивает APK (или берёт заранее скачанный) и передаёт его системному установщику. */
    fun install(activity: Activity) {
        val r = release ?: return
        if (stage == Stage.DOWNLOADING || stage == Stage.INSTALLING) return
        if (!canInstall(activity)) {
            publish(Stage.PERMISSION, "")
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                )
            } catch (_: Exception) {
                // На некоторых ТВ-приставках такого экрана нет — подсказываем, где включить вручную.
                publish(Stage.PERMISSION, "Откройте Настройки → Безопасность (или Приложения → Специальный доступ) → " +
                    "«Неизвестные источники» и разрешите «Экран на ТВ».")
            }
            return
        }
        val app = activity.applicationContext
        progress = 0
        publish(Stage.DOWNLOADING, "")
        thread(name = "update-install") {
            while (busy) Thread.sleep(200) // ждём, если идёт предзагрузка
            var session: PackageInstaller.Session? = null
            val file = apkFile(app, r.version)
            try {
                if (!(file.exists() && file.length() > 0)) {
                    busy = true
                    try {
                        download(r, file) { pct -> progress = pct; publish(Stage.DOWNLOADING) }
                    } finally {
                        busy = false
                    }
                }
                fetched = true
                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                val id = pi.createSession(params)
                val s = pi.openSession(id)
                session = s
                FileInputStream(file).use { input ->
                    s.openWrite("app.apk", 0, file.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        s.fsync(out)
                    }
                }
                val intent = Intent(app, UpdateReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(app, id, intent, flags)
                publish(Stage.INSTALLING, "")
                s.commit(pending.intentSender)
                s.close()
                session = null
            } catch (e: Exception) {
                try { session?.abandon() } catch (_: Exception) {}
                file.delete()
                fetched = false
                publish(Stage.ERROR, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Результат установки от системы (см. UpdateReceiver). */
    internal fun onInstallStatus(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(confirm) } catch (_: Exception) { publish(Stage.ERROR, "не открылось окно установки") }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> publish(Stage.UP_TO_DATE, "")
            PackageInstaller.STATUS_FAILURE_ABORTED -> publish(Stage.AVAILABLE, "") // пользователь отменил
            else -> {
                val r = release
                if (r != null) apkFile(ctx, r.version).delete()
                fetched = false
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "код $status"
                publish(Stage.ERROR, if (status == PackageInstaller.STATUS_FAILURE_CONFLICT)
                    "подпись не совпадает — удалите старую версию и поставьте APK с GitHub вручную" else msg)
            }
        }
    }

    // ---------- «Что нового» после обновления ----------

    fun notesLines(notes: String): List<String> = notes.lines()
        .map { it.trim().removePrefix("- ").removePrefix("* ").replace("**", "").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Один раз после обновления показывает, что нового. */
    fun showWhatsNewIfUpdated(activity: Activity) {
        val p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = currentVersion(activity)
        val seen = p.getString(KEY_SEEN, null)
        p.edit().putString(KEY_SEEN, current).apply()
        if (seen == null || compareVersions(current, seen) <= 0) return
        if (p.getString(KEY_NOTES_VER, null) != current) return
        val lines = notesLines(p.getString(KEY_NOTES, "").orEmpty())
        if (lines.isEmpty()) return
        AlertDialog.Builder(activity)
            .setTitle("Обновлено до версии $current")
            .setMessage(lines.joinToString("\n") { "• $it" })
            .setPositiveButton("Хорошо", null)
            .show()
    }

    fun showNotes(activity: Activity, onUpdate: (() -> Unit)?) {
        val r = release ?: return
        val lines = notesLines(r.notes).ifEmpty { listOf("Исправления и улучшения") }
        val b = AlertDialog.Builder(activity)
            .setTitle("Что нового в версии ${r.version}")
            .setMessage(lines.joinToString("\n") { "• $it" })
            .setNegativeButton("Позже", null)
        if (onUpdate != null) b.setPositiveButton("Обновить") { _, _ -> onUpdate() }
        b.show()
    }
}

/** Принимает от системы результат установки обновления. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Updater.ACTION_INSTALL_STATUS) Updater.onInstallStatus(context, intent)
    }
}

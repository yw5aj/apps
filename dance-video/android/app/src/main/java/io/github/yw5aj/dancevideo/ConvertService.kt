package io.github.yw5aj.dancevideo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Runs the bundled ffmpeg as a foreground service, so the conversion keeps going
 * while she uses other apps or locks the phone.
 */
class ConvertService : Service() {
    companion object {
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
        const val ID_PROGRESS = 1
        const val ID_DONE = 2
        const val ACTION_CANCEL = "cancel"
        const val ALBUM = "Movies/舞社视频"
        private const val PREFS = "dv"
        private const val PREF_MC_BROKEN = "mc_broken"

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "转换进度", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "转换完成", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    @Volatile private var proc: Process? = null
    @Volatile private var canceled = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            canceled = true
            proc?.destroy()
            return START_NOT_STICKY
        }
        ensureChannels(this)
        startForeground(ID_PROGRESS, progressNotification("准备中…", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        if (running) return START_NOT_STICKY
        running = true
        canceled = false
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dancevideo:convert")
            .apply { acquire(3 * 60 * 60 * 1000L) }
        thread(name = "convert") { work() }
        return START_NOT_STICKY
    }

    @Volatile private var running = false

    override fun onTimeout(startId: Int, fgsType: Int) {
        canceled = true
        proc?.destroy()
    }

    private fun work() {
        val probe = Job.probe!!
        val input = Job.inputFile!!
        val out = File(cacheDir, "out.mp4")
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val encoders: List<Encoder> = when {
            Job.forceEncoder != null -> listOf(Job.forceEncoder)
            probe.canCopyVideo -> listOf(null)
            prefs.getBoolean(PREF_MC_BROKEN, false) -> listOf("x264")
            else -> listOf("mc", "x264")
        }
        var ok = false
        var tail = ""
        for (enc in encoders) {
            if (canceled) break
            Job.encoderLabel = when (enc) {
                null -> "只转声音"
                "mc" -> "硬件编码"
                else -> "软件编码"
            }
            Job.progress = 0.0; Job.fps = ""; Job.startedAt = System.currentTimeMillis()
            Job.changed()
            val r = runOnce(probe, input, out, enc)
            ok = r.first; tail = r.second
            Log.i(TAG, "encoder=$enc ok=$ok canceled=$canceled")
            if (ok) break
            if (enc == "mc" && !canceled && Job.forceEncoder == null) {
                prefs.edit().putBoolean(PREF_MC_BROKEN, true).apply()
                Log.w(TAG, "hardware encoder failed, falling back to x264:\n$tail")
            }
        }

        try {
            when {
                canceled -> {
                    Job.phase = Job.Phase.READY
                    Log.i(TAG, "DVTEST CANCELED")
                }
                ok -> {
                    saveToAlbum(out)
                    Job.phase = Job.Phase.DONE
                    notifyDone("转好了", Job.outName)
                    Log.i(TAG, "DVTEST DONE encoder=${Job.encoderLabel} path=$ALBUM/${Job.outName}")
                }
                else -> {
                    Job.error = "转换失败了"
                    Job.log = tail
                    Job.phase = Job.Phase.FAILED
                    notifyDone("转换失败", "打开 App 看看原因")
                    Log.e(TAG, "DVTEST FAIL\n$tail")
                }
            }
        } catch (e: Exception) {
            Job.error = "保存失败：${e.message}"
            Job.log = tail
            Job.phase = Job.Phase.FAILED
            notifyDone("转换失败", "打开 App 看看原因")
            Log.e(TAG, "DVTEST FAIL save", e)
        } finally {
            out.delete()
            Job.changed()
            running = false
            wakeLock?.let { if (it.isHeld) it.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Returns (success, last lines of ffmpeg's log). */
    private fun runOnce(probe: Probe, input: File, out: File, enc: Encoder): Pair<Boolean, String> {
        out.delete()
        val total = if (Job.trial) minOf(5.0, probe.durationSec) else probe.durationSec
        val args = Ff.args(this, input, out, probe, Job.preset, Job.audioIndex, Job.trial, enc)
        Log.i(TAG, "run: " + args.drop(1).joinToString(" "))
        val p = try {
            ProcessBuilder(args).start()
        } catch (e: Exception) {
            return false to "启动 ffmpeg 失败：${e.message}"
        }
        proc = p
        p.outputStream.close()
        val lines = ArrayDeque<String>()
        val errThread = thread(name = "ffmpeg-log") {
            p.errorStream.bufferedReader().forEachLine { line ->
                synchronized(lines) {
                    lines.addLast(line)
                    if (lines.size > 60) lines.removeFirst()
                }
            }
        }
        // Watchdog: a hardware encoder that never produces a frame, or any run that stops
        // moving, is killed so the fallback (or the error screen) can take over.
        val startedAt = System.currentTimeMillis()
        var lastOut = -1L
        val lastAdvance = AtomicLong(0L)
        val stalled = AtomicReference("")
        val watchdog = thread(name = "ffmpeg-watchdog") {
            try {
                while (true) {
                    Thread.sleep(5000)
                    val now = System.currentTimeMillis()
                    if (enc == "mc" && lastAdvance.get() == 0L && now - startedAt > 45_000) {
                        stalled.set("硬件编码 45 秒没有出画面"); p.destroy(); break
                    }
                    if (now - maxOf(lastAdvance.get(), startedAt) > 180_000) {
                        stalled.set("3 分钟没有进展"); p.destroy(); break
                    }
                }
            } catch (_: InterruptedException) {}
        }
        p.inputStream.bufferedReader().forEachLine { line ->
            val k = line.substringBefore('=')
            val v = line.substringAfter('=', "")
            when (k) {
                "out_time_us" -> v.toLongOrNull()?.let {
                    if (it > lastOut && it > 0) { lastOut = it; lastAdvance.set(System.currentTimeMillis()) }
                    if (total > 0) Job.progress = (it / 1e6 / total).coerceIn(0.0, 1.0)
                }
                "fps" -> Job.fps = v
                "progress" -> tick()
            }
        }
        val code = p.waitFor()
        watchdog.interrupt()
        errThread.join(2000)
        proc = null
        val tail = synchronized(lines) { lines.joinToString("\n") }
        val why = stalled.get()
        if (why.isNotEmpty()) Log.w(TAG, "watchdog: $why")
        val head = if (why.isNotEmpty()) "已停止：$why\n" else ""
        return (code == 0 && out.length() > 0 && why.isEmpty()) to "${head}退出码 $code\n$tail"
    }

    private fun tick() {
        Job.changed()
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        val pct = (Job.progress * 100).toInt()
        val eta = Job.etaSec()?.let { " · 还要约 " + fmtTime(it) } ?: ""
        getSystemService(NotificationManager::class.java)
            .notify(ID_PROGRESS, progressNotification("$pct%$eta", pct))
    }

    private fun saveToAlbum(out: File) {
        val base = Job.inputName.substringBeforeLast('.').ifBlank { "舞社视频" }
        val name = base + (if (Job.trial) "_试转5秒" else "_转换") + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, ALBUM)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val col = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(col, values) ?: error("没法在相册里新建文件")
        contentResolver.openOutputStream(uri)!!.use { os -> out.inputStream().use { it.copyTo(os) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        // Read back the name MediaStore actually used (it adds " (1)" for duplicates).
        var finalName = name
        contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) finalName = it.getString(0)
        }
        Job.outUri = uri
        Job.outName = finalName
        Job.outSize = out.length()
        Job.outWasTrial = Job.trial
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(text: String, pct: Int): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ConvertService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFFB1283A.toInt())
            .setContentTitle(if (Job.trial) "正在试转前 5 秒" else "正在转换视频")
            .setContentText(text)
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "取消", cancel).build())
            .build()
    }

    private fun notifyDone(title: String, text: String) {
        val n = Notification.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFFB1283A.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_DONE, n)
    }
}

package io.github.yw5aj.dancevideo

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

const val TAG = "DanceVideo"

enum class Preset(val label: String, val maxSide: Int, val crf: Int, val bitrate: String, val mbPerSec: Double) {
    SMALL("小文件 · 720p", 1280, 24, "3M", 0.37),
    STD("标准 · 1080p", 1920, 23, "6M", 0.8),
    HIGH("高画质 · 1080p", 1920, 18, "14M", 1.8),
}

/** "mc" = phone's hardware H.264 encoder, "x264" = software encoder, null = copy the picture as-is. */
typealias Encoder = String?

data class AudioStream(val index: Int, val codec: String, val layout: String, val rate: Int)

data class Probe(
    val durationSec: Double,
    val vCodec: String?,
    val vPixFmt: String?,
    val width: Int,
    val height: Int,
    val fps: String?,
    val audio: List<AudioStream>,
) {
    /** Pictures every Android phone can play: plain H.264, or 4:2:0 H.265 (8 or 10 bit). */
    val canCopyVideo: Boolean
        get() {
            val pix = vPixFmt ?: return false
            return when (vCodec) {
                "h264" -> pix == "yuv420p" || pix == "yuvj420p"
                "hevc" -> pix == "yuv420p" || pix == "yuv420p10le"
                else -> false
            }
        }

    fun videoText(): String {
        if (vCodec == null) return "没有画面"
        val pix = vPixFmt ?: ""
        val depth = when {
            pix.contains("12") -> "12-bit"
            pix.contains("10") -> "10-bit"
            else -> "8-bit"
        }
        val sub = when {
            pix.contains("422") -> "4:2:2"
            pix.contains("444") -> "4:4:4"
            pix.contains("420") || pix.startsWith("nv12") -> "4:2:0"
            else -> pix
        }
        val name = when (vCodec) {
            "hevc" -> "H.265"
            "h264" -> "H.264"
            "prores" -> "ProRes"
            "dnxhd" -> "DNxHR"
            else -> vCodec
        }
        val parts = mutableListOf(name)
        if (width > 0) parts += "${width}×${height}"
        parts += "$depth $sub"
        if (fps != null) parts += "$fps fps"
        return parts.joinToString(" · ")
    }
}

fun audioText(a: AudioStream): String {
    val ch = when {
        a.layout == "mono" -> "单声道"
        a.layout == "stereo" -> "双声道"
        a.layout.endsWith(" channels") -> a.layout.removeSuffix(" channels") + " 声道"
        a.layout.isBlank() -> "声道未知"
        else -> a.layout.substringBefore('(') + " 声道"
    }
    val codec = when {
        a.codec.startsWith("pcm") -> "PCM"
        a.codec == "aac" -> "AAC"
        else -> a.codec
    }
    val khz = if (a.rate > 0) " · ${a.rate / 1000} kHz" else ""
    return "$codec · $ch$khz"
}

fun fmtTime(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    else String.format("%d:%02d", s / 60, s % 60)
}

fun fmtMb(bytes: Long): String = String.format("%.0f MB", bytes / 1048576.0)

object Ff {
    fun bin(ctx: Context): String = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath

    fun probe(ctx: Context, file: File): Probe {
        val p = ProcessBuilder(bin(ctx), "-hide_banner", "-nostdin", "-i", file.absolutePath)
            .redirectErrorStream(true).start()
        p.outputStream.close()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
        android.util.Log.i(TAG, "probe:\n$text")
        return parse(text)
    }

    /** Split "a (x, y), b, c" on commas that are not inside parentheses. */
    private fun splitTop(s: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in s) {
            when (c) {
                '(' -> depth++
                ')' -> depth--
            }
            if (c == ',' && depth == 0) {
                out += cur.toString().trim(); cur.clear()
            } else cur.append(c)
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    fun parse(text: String): Probe {
        var dur = 0.0
        Regex("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").find(text)?.let { m ->
            val (h, mi, s) = m.destructured
            dur = h.toInt() * 3600 + mi.toInt() * 60 + s.toDouble()
        }
        var vc: String? = null
        var pix: String? = null
        var w = 0
        var h = 0
        var fps: String? = null
        val audio = mutableListOf<AudioStream>()
        for (line in text.lines()) {
            val t = line.trim()
            if (!t.startsWith("Stream #")) continue
            val vi = t.indexOf(": Video: ")
            val ai = t.indexOf(": Audio: ")
            if (vi >= 0 && vc == null && !t.contains("attached pic")) {
                val parts = splitTop(t.substring(vi + 9))
                vc = parts.getOrNull(0)?.substringBefore(' ')
                pix = parts.getOrNull(1)?.substringBefore('(')?.trim()
                Regex("(\\d{2,5})x(\\d{2,5})").find(parts.drop(1).joinToString(", "))?.let {
                    w = it.groupValues[1].toInt(); h = it.groupValues[2].toInt()
                }
                fps = Regex("([\\d.]+) fps").find(t)?.groupValues?.get(1)
            } else if (ai >= 0) {
                val parts = splitTop(t.substring(ai + 9))
                audio += AudioStream(
                    audio.size,
                    parts.getOrNull(0)?.substringBefore(' ') ?: "?",
                    parts.getOrNull(2) ?: "",
                    Regex("(\\d+) Hz").find(t)?.groupValues?.get(1)?.toInt() ?: 0,
                )
            }
        }
        return Probe(dur, vc, pix, w, h, fps, audio)
    }

    fun args(
        ctx: Context, input: File, output: File, probe: Probe,
        preset: Preset, audioIndex: Int, trial: Boolean, enc: Encoder,
    ): List<String> {
        val a = mutableListOf(bin(ctx), "-hide_banner", "-nostdin", "-y", "-i", input.absolutePath)
        if (trial) a += listOf("-t", "5")
        a += listOf("-map", "0:v:0", "-map", "0:a:$audioIndex?")
        if (enc == null) {
            a += listOf("-c:v", "copy")
            if (probe.vCodec == "hevc") a += listOf("-tag:v", "hvc1")
        } else {
            val s = preset.maxSide
            val scale = if (probe.width >= probe.height) "scale='min($s,iw)':-2" else "scale=-2:'min($s,ih)'"
            if (enc == "mc") {
                a += listOf("-vf", "$scale,format=nv12", "-c:v", "h264_mediacodec", "-b:v", preset.bitrate)
            } else {
                a += listOf(
                    "-vf", "$scale,format=yuv420p", "-c:v", "libx264",
                    "-preset", "veryfast", "-crf", preset.crf.toString(),
                )
            }
        }
        a += listOf("-c:a", "aac", "-b:a", "192k", "-ac", "2")
        val rate = probe.audio.getOrNull(audioIndex)?.rate ?: 0
        if (rate > 48000) a += listOf("-ar", "48000")
        a += listOf(
            "-map_metadata", "0", "-write_tmcd", "0", "-movflags", "+faststart",
            "-progress", "pipe:1", "-nostats", output.absolutePath,
        )
        return a
    }
}

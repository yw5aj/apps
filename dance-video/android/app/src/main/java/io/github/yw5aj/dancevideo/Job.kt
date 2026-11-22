package io.github.yw5aj.dancevideo

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/** The one video being worked on. Shared by the screen and the background service (same process). */
object Job {
    enum class Phase { EMPTY, LOADING, READY, RUNNING, DONE, FAILED }

    @Volatile var phase = Phase.EMPTY

    // Input
    var inputFile: File? = null
    var inputName = ""
    var inputSize = 0L
    var loadPct = -1
    var probe: Probe? = null

    // Choices
    var preset = Preset.HIGH
    var audioIndex = 0
    var forceEncoder: String? = null // tests only

    // Running
    var trial = false
    var progress = 0.0
    var fps = ""
    var startedAt = 0L
    var encoderLabel = ""

    // Result
    var outUri: Uri? = null
    var outName = ""
    var outSize = 0L
    var outWasTrial = false
    var error = ""
    var log = ""

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun changed() { main.post { listeners.forEach { it() } } }

    fun resetForNewInput() {
        inputFile?.delete()
        inputFile = null; inputName = ""; inputSize = 0; loadPct = -1; probe = null
        audioIndex = 0; outUri = null; outName = ""; outSize = 0; error = ""; log = ""
        progress = 0.0; fps = ""
    }

    fun elapsedSec(): Double = (System.currentTimeMillis() - startedAt) / 1000.0

    fun etaSec(): Double? {
        if (progress < 0.02) return null
        return elapsedSec() * (1 - progress) / progress
    }
}

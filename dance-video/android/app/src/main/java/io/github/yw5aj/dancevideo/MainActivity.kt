package io.github.yw5aj.dancevideo

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val pickRequest = 1

    private lateinit var fileName: TextView
    private lateinit var pickBtn: Button
    private lateinit var infoCard: LinearLayout
    private lateinit var videoInfo: TextView
    private lateinit var audioInfo: TextView
    private lateinit var verdict: TextView
    private lateinit var audioGroup: RadioGroup
    private lateinit var qualityCard: LinearLayout
    private lateinit var qualityGroup: RadioGroup
    private lateinit var actions: LinearLayout
    private lateinit var trialBtn: Button
    private lateinit var fullBtn: Button
    private lateinit var runCard: LinearLayout
    private lateinit var runTitle: TextView
    private lateinit var bar: ProgressBar
    private lateinit var runStats: TextView
    private lateinit var doneCard: LinearLayout
    private lateinit var doneTitle: TextView
    private lateinit var doneText: TextView
    private lateinit var failCard: LinearLayout
    private lateinit var failText: TextView
    private lateinit var failLog: TextView

    private var testStart: String? = null
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConvertService.ensureChannels(this)
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Job.addListener(listener)
        render()
    }

    override fun onStop() {
        Job.removeListener(listener)
        super.onStop()
    }

    // ---------- Input ----------

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val testInput = i.getStringExtra("test_input")
        if (testInput != null) {
            // Automated tests only: the file must be inside this app's own folder.
            val f = File(testInput)
            val own = getExternalFilesDir(null)
            if (own != null && f.canonicalPath.startsWith(own.canonicalPath) && f.isFile) {
                testStart = i.getStringExtra("test_start")
                Job.forceEncoder = i.getStringExtra("test_encoder")
                i.getStringExtra("test_preset")?.let { p -> Job.preset = Preset.valueOf(p) }
                i.removeExtra("test_input")
                load(Uri.fromFile(f))
            }
            return
        }
        val uri: Uri? = when (i.action) {
            Intent.ACTION_SEND -> i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        if (uri != null) {
            i.action = Intent.ACTION_MAIN
            load(uri)
        }
    }

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickRequest && resultCode == RESULT_OK) data?.data?.let { load(it) }
    }

    private fun load(uri: Uri) {
        if (Job.phase == Job.Phase.RUNNING) {
            Toast.makeText(this, "正在转换，等它转完再选", Toast.LENGTH_SHORT).show()
            return
        }
        Job.resetForNewInput()
        Job.phase = Job.Phase.LOADING
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "视频"
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    it.getString(0)?.let { n -> name = n }
                    if (!it.isNull(1)) size = it.getLong(1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "query failed", e)
        }
        Job.inputName = name
        Job.changed()
        thread(name = "load") {
            try {
                cacheDir.listFiles()?.forEach { if (it.name.startsWith("input")) it.delete() }
                val dst = File(cacheDir, "input." + name.substringAfterLast('.', "mov").take(5))
                contentResolver.openInputStream(uri)!!.use { ins ->
                    dst.outputStream().use { os ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            if (size > 0) {
                                val pct = (done * 100 / size).toInt()
                                if (pct != lastPct) { lastPct = pct; Job.loadPct = pct; Job.changed() }
                            }
                        }
                    }
                }
                Job.inputFile = dst
                Job.inputSize = dst.length()
                val p = Ff.probe(this, dst)
                if (p.vCodec == null) error("这个文件里没找到画面")
                Job.probe = p
                Job.phase = Job.Phase.READY
            } catch (e: Exception) {
                Log.e(TAG, "load failed", e)
                Job.error = "读不了这个视频：${e.message}"
                Job.log = ""
                Job.phase = Job.Phase.FAILED
            }
            Job.changed()
            runOnUiThread {
                testStart?.let { s ->
                    testStart = null
                    if (Job.phase == Job.Phase.READY) start(trial = s == "trial")
                    else Log.e(TAG, "DVTEST FAIL load: ${Job.error}")
                }
            }
        }
    }

    private fun start(trial: Boolean) {
        if (Job.phase != Job.Phase.READY && Job.phase != Job.Phase.DONE) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        Job.trial = trial
        Job.progress = 0.0
        Job.fps = ""
        Job.startedAt = System.currentTimeMillis()
        Job.encoderLabel = ""
        Job.phase = Job.Phase.RUNNING
        Job.changed()
        startForegroundService(Intent(this, ConvertService::class.java))
    }

    // ---------- UI ----------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(size: Float = 16f, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (muted) alpha = 0.7f
        setLineSpacing(0f, 1.2f)
    }

    private fun card(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), 0x40808080)
        }
        addView(text(17f, bold = true).apply { this.text = title })
    }

    private fun LinearLayout.gap(v: View, top: Int = 10) {
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        minHeight = dp(52)
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(col); isFillViewport = true }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left + dp(16), b.top + dp(12), b.right + dp(16), b.bottom + dp(24))
            insets
        }
        scroll.clipToPadding = false

        col.addView(text(26f, bold = true).apply { text = "舞社视频转换" })
        col.gap(text(15f, muted = true).apply {
            text = "把舞社的视频转成手机相册能放的 MP4。转的时候可以切去别的 App 或锁屏，转好了会通知你。"
        }, 6)

        val pickCard = card("1  选视频")
        pickBtn = button("选一个舞社下载的视频") {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), pickRequest,
            )
        }
        pickCard.gap(pickBtn)
        fileName = text(15f, muted = true)
        pickCard.gap(fileName, 8)
        col.gap(pickCard, 18)

        infoCard = card("2  看看是什么视频")
        videoInfo = text(15f)
        audioInfo = text(15f)
        verdict = text(15f, muted = true)
        audioGroup = RadioGroup(this)
        infoCard.gap(videoInfo, 8)
        infoCard.gap(audioInfo, 4)
        infoCard.gap(audioGroup, 4)
        infoCard.gap(verdict, 10)
        col.gap(infoCard, 14)

        qualityCard = card("3  画质")
        qualityGroup = RadioGroup(this)
        Preset.entries.forEachIndexed { idx, p ->
            qualityGroup.addView(RadioButton(this).apply {
                id = 100 + idx
                tag = p
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                minHeight = dp(44)
            })
        }
        qualityGroup.setOnCheckedChangeListener { g, id ->
            (g.findViewById<View>(id)?.tag as? Preset)?.let { p ->
                Job.preset = p
                getSharedPreferences("dv", MODE_PRIVATE).edit().putString("preset", p.name).apply()
            }
        }
        getSharedPreferences("dv", MODE_PRIVATE).getString("preset", null)?.let { n ->
            runCatching { Job.preset = Preset.valueOf(n) }
        }
        qualityCard.gap(qualityGroup, 4)
        col.gap(qualityCard, 14)

        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        trialBtn = button("先试转前 5 秒") { start(trial = true) }
        fullBtn = button("转换整条") { start(trial = false) }
        actions.gap(trialBtn, 0)
        actions.gap(fullBtn, 8)
        actions.gap(text(14f, muted = true).apply { text = "第一次用建议先试转，看画面和声音都正常再转整条。" }, 6)
        col.gap(actions, 16)

        runCard = card("正在转换")
        runTitle = runCard.getChildAt(0) as TextView
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        runStats = text(15f)
        runCard.gap(bar, 12)
        runCard.gap(runStats, 8)
        runCard.gap(text(14f, muted = true).apply { text = "可以切去别的 App 或锁屏，别从最近任务里把这个 App 划掉。" }, 8)
        runCard.gap(button("取消") {
            startService(Intent(this, ConvertService::class.java).setAction(ConvertService.ACTION_CANCEL))
        }, 10)
        col.gap(runCard, 16)

        doneCard = card("转好了")
        doneTitle = doneCard.getChildAt(0) as TextView
        doneText = text(15f)
        doneCard.gap(doneText, 8)
        val doneBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val play = button("播放") {
            Job.outUri?.let { u ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            }
        }
        val share = button("分享") {
            Job.outUri?.let { u ->
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, u)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享视频",
                ))
            }
        }
        doneBtns.addView(play, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        doneBtns.addView(share, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        doneCard.gap(doneBtns, 10)
        col.gap(doneCard, 16)

        failCard = card("没转成")
        failText = text(15f)
        failLog = text(12f, muted = true).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE }
        failCard.gap(failText, 8)
        failCard.gap(button("复制出错信息") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", Job.error + "\n" + Job.log))
            Toast.makeText(this, "已复制，发给 Claude 看看", Toast.LENGTH_SHORT).show()
        }, 10)
        failCard.gap(failLog, 10)
        col.gap(failCard, 16)

        setContentView(scroll)
    }

    private fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }

    private fun render() {
        val phase = Job.phase
        val p = Job.probe
        val running = phase == Job.Phase.RUNNING

        pickBtn.isEnabled = !running && phase != Job.Phase.LOADING
        fileName.text = when {
            phase == Job.Phase.EMPTY -> "先在舞社网站把视频下载到手机。也可以在文件 App 里点「分享」发到这里。"
            phase == Job.Phase.LOADING && Job.loadPct >= 0 -> "${Job.inputName}\n正在读取… ${Job.loadPct}%"
            phase == Job.Phase.LOADING -> "${Job.inputName}\n正在读取…"
            Job.inputSize > 0 -> "${Job.inputName} · ${fmtMb(Job.inputSize)}"
            else -> Job.inputName
        }

        show(infoCard, p != null)
        show(qualityCard, p != null && !p.canCopyVideo)
        show(actions, p != null && (phase == Job.Phase.READY || phase == Job.Phase.DONE))
        if (p != null) {
            videoInfo.text = "画面：" + p.videoText() + " · 时长 " + fmtTime(p.durationSec)
            audioInfo.text = when {
                p.audio.isEmpty() -> "声音：没有声音轨"
                p.audio.size == 1 -> "声音：" + audioText(p.audio[0])
                else -> "声音：有 ${p.audio.size} 轨，选一个："
            }
            if (audioGroup.childCount != p.audio.size || p.audio.size < 2) {
                audioGroup.setOnCheckedChangeListener(null)
                audioGroup.removeAllViews()
                if (p.audio.size > 1) {
                    p.audio.forEach { a ->
                        audioGroup.addView(RadioButton(this).apply {
                            id = 200 + a.index
                            text = "第 ${a.index + 1} 轨 · " + audioText(a)
                            minHeight = dp(44)
                        })
                    }
                    audioGroup.check(200 + Job.audioIndex)
                    audioGroup.setOnCheckedChangeListener { _, id -> Job.audioIndex = id - 200 }
                }
            }
            show(audioGroup, p.audio.size > 1)
            verdict.text = if (p.canCopyVideo) "手机能直接放这种画面，只需要转声音，几秒就好。"
            else "这种画面手机放不了，要把画面重新编码一遍，需要几分钟。"

            for (i in 0 until qualityGroup.childCount) {
                val rb = qualityGroup.getChildAt(i) as RadioButton
                val preset = rb.tag as Preset
                rb.text = preset.label + "  约 " + String.format("%.0f MB", preset.mbPerSec * p.durationSec)
            }
            val want = 100 + Preset.entries.indexOf(Job.preset)
            if (qualityGroup.checkedRadioButtonId != want) qualityGroup.check(want)
            trialBtn.isEnabled = !running
            fullBtn.isEnabled = !running
        }

        show(runCard, running)
        if (running) {
            runTitle.text = if (Job.trial) "正在试转前 5 秒" else "正在转换"
            bar.progress = (Job.progress * 1000).toInt()
            val parts = mutableListOf("${(Job.progress * 100).toInt()}%", "已用 " + fmtTime(Job.elapsedSec()))
            Job.etaSec()?.let { parts += "还要约 " + fmtTime(it) }
            Job.fps.toDoubleOrNull()?.let { if (it > 0) parts += "每秒 ${it.toInt()} 帧" }
            if (Job.encoderLabel.isNotEmpty()) parts += Job.encoderLabel
            runStats.text = parts.joinToString(" · ")
        }

        show(doneCard, phase == Job.Phase.DONE)
        if (phase == Job.Phase.DONE) {
            doneTitle.text = if (Job.outWasTrial) "试转好了" else "转好了"
            val where = "存在手机的 ${ConvertService.ALBUM} 文件夹，也可以点「分享」发到 Google Photos 或微信。"
            doneText.text = Job.outName + " · " + fmtMb(Job.outSize) + "\n" + where +
                (if (Job.outWasTrial) "\n点「播放」看看画面和声音都正常，再点上面的「转换整条」。" else "")
        }

        show(failCard, phase == Job.Phase.FAILED)
        if (phase == Job.Phase.FAILED) {
            failText.text = Job.error
            failLog.text = Job.log
            show(failLog, Job.log.isNotBlank())
        }

        if (running) {
            // Keep the elapsed time ticking even between ffmpeg updates.
            bar.removeCallbacks(tickRunnable)
            bar.postDelayed(tickRunnable, 1000)
        }
    }

    private val tickRunnable = Runnable { if (Job.phase == Job.Phase.RUNNING) render() }
}

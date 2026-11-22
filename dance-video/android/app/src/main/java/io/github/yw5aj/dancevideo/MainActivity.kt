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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/**
 * One left-aligned column that shows only the current stage, styled like the web version:
 * 月白 ground, 黛 ink, 胭脂 accent, 站酷小薇 for titles, progress counted as an eight-count.
 */
class MainActivity : Activity() {
    private val pickRequest = 1

    private lateinit var pal: Pal
    private lateinit var titleFont: Typeface

    // Stage: pick
    private lateinit var stagePick: LinearLayout
    private lateinit var pickBtn: TextView
    private lateinit var pickError: TextView

    // Stage: info
    private lateinit var stageInfo: LinearLayout
    private lateinit var fileName: TextView
    private lateinit var verdict: TextView
    private lateinit var qualityBox: LinearLayout
    private val qualityTiles = mutableListOf<Pair<Preset, LinearLayout>>()
    private lateinit var goBtn: TextView
    private lateinit var advToggle: TextView
    private lateinit var advBox: LinearLayout
    private lateinit var vInfo: TextView
    private lateinit var aInfo: TextView
    private lateinit var audioBox: LinearLayout
    private lateinit var trialBtn: TextView
    private lateinit var logView: TextView
    private lateinit var infoError: TextView
    private var advOpen = false
    private var audioBoxFor: Probe? = null

    // Stage: run
    private lateinit var stageRun: LinearLayout
    private lateinit var pct: TextView
    private lateinit var beats: BeatsView
    private lateinit var runLabel: TextView
    private lateinit var runStats: TextView

    // Stage: done
    private lateinit var stageDone: LinearLayout
    private lateinit var doneTitle: TextView
    private lateinit var doneNote: TextView
    private lateinit var doneInfo: TextView
    private lateinit var fullBtn: TextView
    private lateinit var againBtn: TextView

    private var testStart: String? = null
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConvertService.ensureChannels(this)
        pal = Pal.of(this)
        titleFont = resources.getFont(R.font.xiaowei)
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

    private fun pick() {
        if (Job.phase == Job.Phase.RUNNING || Job.phase == Job.Phase.LOADING) return
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), pickRequest,
        )
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
                                val p = (done * 100 / size).toInt()
                                if (p != lastPct) { lastPct = p; Job.loadPct = p; Job.changed() }
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
        if (Job.phase != Job.Phase.READY && Job.phase != Job.Phase.DONE && Job.phase != Job.Phase.FAILED) return
        if (Job.probe == null) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        Job.trial = trial
        Job.progress = 0.0
        Job.fps = ""
        Job.startedAt = System.currentTimeMillis()
        Job.encoderLabel = ""
        Job.error = ""
        Job.phase = Job.Phase.RUNNING
        Job.changed()
        startForegroundService(Intent(this, ConvertService::class.java))
    }

    // ---------- UI building blocks ----------

    private fun text(size: Float = 17f, color: Int = pal.ink, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.create(null, 600, false)
        setLineSpacing(0f, 1.35f)
    }

    private fun title(size: Float) = TextView(this).apply {
        typeface = titleFont
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(pal.ink)
        setLineSpacing(0f, 1.1f)
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun LinearLayout.add(v: View, gapDp: Int = 18) {
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = if (childCount == 0) 0 else dp(gapDp)
        })
    }

    private fun line(): View = View(this).apply { setBackgroundColor(pal.line) }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Float = 1.5f) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        setStroke(dp(strokeDp), stroke)
    }

    // ---------- UI ----------

    private fun buildUi() {
        window.decorView.setBackgroundColor(pal.bg)
        window.insetsController?.setSystemBarsAppearance(
            if (pal.night) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )

        val col = column()
        val scroll = ScrollView(this).apply { addView(col); isFillViewport = true; clipToPadding = false }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left + dp(20), b.top + dp(28), b.right + dp(20), b.bottom + dp(40))
            insets
        }

        // Header
        val header = column()
        header.add(title(40f).apply { text = "舞社视频转换" })
        header.add(text(17f, pal.muted).apply {
            text = "舞社的视频在手机上放不了？在这里转成能放的 MP4。转的时候可以切去别的 App 或锁屏，转好了会通知你。"
        }, 8)
        col.add(header)

        // ---- Stage: pick ----
        stagePick = column()
        pickBtn = pill(pal, "选一个舞社下载的视频", primary = true) { pick() }
        stagePick.add(pickBtn)
        val how = column()
        listOf(
            "先在舞社网站把视频下载到手机。",
            "在这里选它，点“转换并保存”。也可以在文件 App 里点“分享”发过来。",
            "转好的 MP4 会存进手机的 Movies/舞社视频。",
        ).forEachIndexed { i, s ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            row.addView(TextView(this).apply {
                typeface = titleFont
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(pal.accent)
                text = (i + 1).toString()
                includeFontPadding = false
            }, LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(text(15f, pal.muted).apply { text = s },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            how.add(row, 10)
        }
        stagePick.add(how)
        pickError = text(15f, pal.bad)
        stagePick.add(pickError)
        col.add(stagePick, 28)

        // ---- Stage: info ----
        stageInfo = column()
        val fileRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fileName = text(17f, bold = true)
        fileRow.addView(fileName, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fileRow.addView(textLink(pal, "换一个") { pick() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        stageInfo.add(fileRow)
        stageInfo.addView(line(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10) })
        verdict = text(17f)
        stageInfo.add(verdict)

        qualityBox = column()
        qualityBox.add(text(15f, pal.muted).apply { text = "画质。大小是按这条视频的时长估的，动作越多越大。" })
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Preset.HIGH to "高画质", Preset.STD to "标准", Preset.SMALL to "小文件").forEachIndexed { i, (p, name) ->
            val tile = column().apply {
                setPadding(dp(12), dp(12), dp(12), dp(11))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Job.preset = p
                    getSharedPreferences("dv", MODE_PRIVATE).edit().putString("preset", p.name).apply()
                    render()
                }
            }
            tile.addView(text(16f, bold = true).apply { this.text = name })
            tile.addView(text(13f, pal.muted).apply { tag = "est" })
            tiles.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
            qualityTiles += p to tile
        }
        qualityBox.add(tiles, 8)
        getSharedPreferences("dv", MODE_PRIVATE).getString("preset", null)?.let { n ->
            runCatching { Job.preset = Preset.valueOf(n) }
        }
        stageInfo.add(qualityBox)

        goBtn = pill(pal, "转换并保存", primary = true) { start(trial = false) }
        stageInfo.add(goBtn)

        // 高级
        stageInfo.addView(line(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(18) })
        val advRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            isClickable = true
            setOnClickListener { advOpen = !advOpen; render() }
        }
        advRow.addView(text(15f, pal.muted).apply { text = "高级" }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        advToggle = text(13f, pal.muted)
        advRow.addView(advToggle)
        stageInfo.addView(advRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        advBox = column()
        vInfo = text(14f)
        aInfo = text(14f)
        advBox.add(detailRow("画面", vInfo), 0)
        advBox.add(detailRow("声音", aInfo), 4)
        audioBox = column()
        advBox.add(audioBox, 8)
        trialBtn = pill(pal, "先试转前 5 秒", primary = false) { start(trial = true) }
        advBox.add(trialBtn, 12)
        logView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextColor(pal.ink)
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(pal.paper, pal.line, 10, 1f)
        }
        advBox.add(logView, 12)
        advBox.add(pill(pal, "复制日志", primary = false) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", Job.error + "\n" + Job.log))
            Toast.makeText(this, "已复制，发给 Claude 看看", Toast.LENGTH_SHORT).show()
        }, 12)
        stageInfo.add(advBox, 4)

        infoError = text(15f, pal.bad)
        stageInfo.add(infoError)
        col.add(stageInfo, 28)

        // ---- Stage: run ----
        stageRun = column()
        pct = TextView(this).apply {
            typeface = titleFont
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 84f)
            setTextColor(pal.ink)
            includeFontPadding = false
            fontFeatureSettings = "tnum"
        }
        stageRun.add(pct)
        beats = BeatsView(this, pal, titleFont)
        stageRun.add(beats)
        val runText = column()
        runLabel = text(17f)
        runStats = text(15f, pal.muted)
        runText.add(runLabel)
        runText.add(runStats, 2)
        runText.add(text(15f, pal.muted).apply { text = "可以切去别的 App 或锁屏，别从最近任务里把这个 App 划掉。" }, 8)
        stageRun.add(runText)
        stageRun.add(pill(pal, "取消", primary = false) {
            startService(Intent(this, ConvertService::class.java).setAction(ConvertService.ACTION_CANCEL))
        })
        col.add(stageRun, 28)

        // ---- Stage: done ----
        stageDone = column()
        doneTitle = title(26f)
        stageDone.add(doneTitle)
        val doneText = column()
        doneNote = text(17f)
        doneInfo = text(15f, pal.muted)
        doneText.add(doneNote)
        doneText.add(doneInfo, 2)
        stageDone.add(doneText)
        val doneBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        doneBtns.addView(pill(pal, "播放", primary = false) {
            Job.outUri?.let { u ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        doneBtns.addView(pill(pal, "分享", primary = false) {
            Job.outUri?.let { u ->
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, u)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享视频",
                ))
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        stageDone.add(doneBtns)
        fullBtn = pill(pal, "画面和声音都正常，转换整条并保存", primary = true) { start(trial = false) }
        stageDone.add(fullBtn)
        againBtn = pill(pal, "再转一个", primary = true) { pick() }
        stageDone.add(againBtn, 10)
        col.add(stageDone, 28)

        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        col.add(text(12f, pal.muted).apply { text = "版本 $ver" }, 28)

        setContentView(scroll)
    }

    private fun detailRow(label: String, value: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(text(14f, pal.muted).apply { text = label }, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }

    private fun enable(v: View, on: Boolean) { v.isEnabled = on; v.alpha = if (on) 1f else 0.5f }

    private fun render() {
        val phase = Job.phase
        val p = Job.probe
        val running = phase == Job.Phase.RUNNING

        // Which stage is on screen.
        val onPick = phase == Job.Phase.EMPTY || phase == Job.Phase.LOADING || (phase == Job.Phase.FAILED && p == null)
        val onInfo = phase == Job.Phase.READY || (phase == Job.Phase.FAILED && p != null)
        show(stagePick, onPick)
        show(stageInfo, onInfo)
        show(stageRun, running)
        show(stageDone, phase == Job.Phase.DONE)

        // Pick
        pickBtn.text = when {
            phase == Job.Phase.LOADING && Job.loadPct >= 0 -> "正在读取… ${Job.loadPct}%"
            phase == Job.Phase.LOADING -> "正在读取…"
            else -> "选一个舞社下载的视频"
        }
        enable(pickBtn, phase != Job.Phase.LOADING)
        pickError.text = if (phase == Job.Phase.FAILED && p == null) Job.error else ""
        show(pickError, pickError.text.isNotEmpty())

        // Info
        if (p != null) {
            fileName.text = Job.inputName + if (Job.inputSize > 0) " · " + fmtMb(Job.inputSize) else ""
            verdict.text = if (p.canCopyVideo) "手机能直接放这种画面，只需要转声音，几秒就好。"
            else "这种画面手机放不了，要把画面重新编码一遍，需要几分钟。"
            show(qualityBox, !p.canCopyVideo)
            for ((preset, tile) in qualityTiles) {
                val on = preset == Job.preset
                tile.background = rounded(if (on) pal.soft else pal.paper, if (on) pal.accent else pal.line, 14)
                val res = if (preset.maxSide >= 1920) "1080p" else "720p"
                (tile.findViewWithTag<TextView>("est")).text = res + " · 约 " + String.format("%.0f MB", preset.mbPerSec * p.durationSec)
            }
            vInfo.text = p.videoText() + " · " + fmtTime(p.durationSec)
            aInfo.text = when {
                p.audio.isEmpty() -> "没有声音轨"
                p.audio.size == 1 -> audioText(p.audio[0])
                else -> "有 ${p.audio.size} 轨。转完没声音就换一条再试。"
            }
            if (audioBoxFor !== p) {
                audioBoxFor = p
                audioBox.removeAllViews()
            }
            if (p.audio.size > 1 && audioBox.childCount == 0) {
                p.audio.forEach { a ->
                    audioBox.add(text(15f).apply {
                        tag = a.index
                        text = "第 ${a.index + 1} 轨 · " + audioText(a)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        isClickable = true
                        setOnClickListener { Job.audioIndex = a.index; render() }
                    }, 6)
                }
            }
            for (i in 0 until audioBox.childCount) {
                val v = audioBox.getChildAt(i)
                val on = v.tag == Job.audioIndex
                v.background = rounded(if (on) pal.soft else pal.paper, if (on) pal.accent else pal.line, 12)
            }
            show(audioBox, p.audio.size > 1)
        }
        advToggle.text = if (advOpen) "收起" else "展开"
        show(advBox, advOpen)
        logView.text = Job.log.ifBlank { "转换出问题时，这里会显示 ffmpeg 的输出。" }
        logView.setTextColor(if (Job.log.isBlank()) pal.muted else pal.ink)
        infoError.text = if (phase == Job.Phase.FAILED && p != null) Job.error + "。点开“高级”可以复制日志。" else ""
        show(infoError, infoError.text.isNotEmpty())

        // Run
        if (running) {
            val n = (Job.progress * 100).toInt()
            pct.text = SpannableString("$n%").apply {
                val i = length - 1
                setSpan(RelativeSizeSpan(34f / 84f), i, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(pal.muted), i, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            beats.progress = Job.progress
            runLabel.text = if (Job.trial) "正在试转前 5 秒" else "正在转换"
            val parts = mutableListOf("已用 " + fmtTime(Job.elapsedSec()))
            Job.etaSec()?.let { parts += "还要约 " + fmtTime(it) }
            Job.fps.toDoubleOrNull()?.let { if (it > 0) parts += "每秒 ${it.toInt()} 帧" }
            if (Job.encoderLabel.isNotEmpty()) parts += Job.encoderLabel
            runStats.text = parts.joinToString(" · ")
            pct.removeCallbacks(tickRunnable)
            pct.postDelayed(tickRunnable, 1000)
        }

        // Done
        if (phase == Job.Phase.DONE) {
            doneTitle.text = if (Job.outWasTrial) "试转好了" else "转好了"
            doneNote.text = if (Job.outWasTrial) "点“播放”看看画面和声音是不是都正常。"
            else "存进了手机的 ${ConvertService.ALBUM}，也可以点“分享”发到 Google Photos 或微信。"
            doneInfo.text = listOf(Job.outName, fmtMb(Job.outSize), Job.encoderLabel).filter { it.isNotBlank() }.joinToString(" · ")
            show(fullBtn, Job.outWasTrial)
            // Only one filled button at a time.
            againBtn.background = (if (Job.outWasTrial) pill(pal, "", false) {} else pill(pal, "", true) {}).background
            againBtn.setTextColor(if (Job.outWasTrial) pal.ink else pal.onAccent)
        }
    }

    private val tickRunnable = Runnable { if (Job.phase == Job.Phase.RUNNING) render() }
}

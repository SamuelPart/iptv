package com.samuelpart.iptvplayer

import android.app.AlertDialog
import android.content.Context
import android.media.AudioManager
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Skin estilo Apple TV (ver captura del usuario): controles de vidrio,
 * -10 / play / +10 al centro, volumen arriba, titulo abajo, barra fina
 * y pastillas Info / Continue Watching.
 *
 * Es SOLO la vista: quien la monta (WebVideoPlayerActivity con su puente JS,
 * o PlayerActivity con libVLC) implementa [Delegate] y traduce cada accion
 * al reproductor real. El usuario nunca toca el iframe ni los controles del host.
 */
class ApplePlayerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface Delegate {
        /** Estado actual del reproductor real (se consulta cada 500ms). */
        fun snapshot(): Snapshot

        /** true para canales en vivo: sin barra, sin ±10, sin velocidad. */
        fun isLive(): Boolean = false

        fun onPlayPause()
        fun onSeekBy(deltaSec: Int)
        fun onSeekTo(positionMs: Long)
        fun onSpeedPicked(speed: Float) {}
        fun onSubtitlesPicked(index: Int) {} // -1 = apagado
        fun onAudioPicked(index: Int) {}
        fun subtitleTrackCount(): Int = 0
        fun audioTrackCount(): Int = 0

        fun onClose()
        fun onPip()
        fun onCast()
        fun onShare()
        fun onInfo()
        fun onContinueWatching()
    }

    data class Snapshot(
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val bufferedMs: Long = 0L,
        val speed: Float = 1f
    )

    var delegate: Delegate? = null

    private val topBar: LinearLayout
    private val bottomBlock: LinearLayout
    private val scrimTop: View
    private val scrimBottom: View
    private val centerRow: LinearLayout
    private val imgPlayPause: ImageView
    private val btnRew: View
    private val btnFwd: View
    private val txtSubtitle: TextView
    private val txtTitle: TextView
    private val txtSpeed: TextView
    private val sbProgress: SeekBar
    private val sbVolume: SeekBar
    private val txtTimeLeft: TextView
    private val txtTimeRight: TextView
    private val bubbleL: TextView
    private val bubbleR: TextView
    private val btnCC: View
    private val btnAudio: View
    private val btnSpeed: View

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var controlsVisible = true
    private var scrubbing = false
    private var hideRunnable: Runnable? = null
    private var pollRunnable: Runnable? = null
    private var lastSpeed = 1f
    private var liveMode = false

    companion object {
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        private const val AUTO_HIDE_MS = 4000L
        private const val POLL_MS = 500L

        /** El BOT separa "Serie T1 Capítulo 2" -> (titulo, linea pequeña).
         *  Tambien "Capítulo 12", "Episodio 3", "T1 E2", etc. */
        fun splitTitleInfo(raw: String): Pair<String, String> {
            val txt = raw.trim()
            val full = Regex("""(?i)\b(?:temporada|t)\s*(\d{1,2})\b[\s\-–:.]*(?:cap[ií]tulo|episodio|ep|cap|e)?\s*(\d{1,4})\b""")
            val onlyEp = Regex("""(?i)\b(?:cap[ií]tulo|episodio|ep|cap)\s*(\d{1,4})\b""")
            val m = full.find(txt)
            val sub = when {
                m != null -> "T${m.groupValues[1]} · Capítulo ${m.groupValues[2]}"
                else -> onlyEp.find(txt)?.let { "Capítulo ${it.groupValues[1]}" } ?: ""
            }
            val match = m ?: onlyEp.find(txt) ?: return txt to ""
            val main = txt.replaceRange(match.range, " ")
                .replace(Regex("""[\s\-–:.]+$"""), "")
                .replace(Regex("""^[\s\-–:.]+"""), "")
                .trim()
            return (main.ifBlank { txt }) to sub
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_apple_player, this, true)
        elevation = 20f

        scrimTop = findViewById(R.id.appleScrimTop)
        scrimBottom = findViewById(R.id.appleScrimBottom)
        topBar = findViewById(R.id.appleTopBar)
        bottomBlock = findViewById(R.id.appleBottomBlock)
        centerRow = findViewById(R.id.appleCenterRow)
        imgPlayPause = findViewById(R.id.imgApplePlayPause)
        btnRew = findViewById(R.id.btnAppleRew)
        btnFwd = findViewById(R.id.btnAppleFwd)
        txtSubtitle = findViewById(R.id.txtAppleSubtitle)
        txtTitle = findViewById(R.id.txtAppleTitle)
        txtSpeed = findViewById(R.id.txtAppleSpeed)
        sbProgress = findViewById(R.id.sbAppleProgress)
        sbVolume = findViewById(R.id.sbAppleVolume)
        txtTimeLeft = findViewById(R.id.txtAppleTimeLeft)
        txtTimeRight = findViewById(R.id.txtAppleTimeRight)
        bubbleL = findViewById(R.id.txtAppleBubbleL)
        bubbleR = findViewById(R.id.txtAppleBubbleR)
        btnCC = findViewById(R.id.btnAppleCC)
        btnAudio = findViewById(R.id.btnAppleAudio)
        btnSpeed = findViewById(R.id.btnAppleSpeed)

        // Tacto iOS en todos los botones
        listOf<View>(
            findViewById(R.id.btnAppleClose), findViewById(R.id.btnApplePip),
            findViewById(R.id.btnAppleCast), findViewById(R.id.btnAppleShare),
            findViewById(R.id.btnApplePlay), btnRew, btnFwd,
            findViewById(R.id.btnAppleInfo), findViewById(R.id.btnAppleResume),
            btnCC, btnAudio, btnSpeed
        ).forEach { it.springPress() }

        findViewById(R.id.btnAppleClose).setOnClickListener { delegate?.onClose() }
        findViewById(R.id.btnApplePip).setOnClickListener { delegate?.onPip() }
        findViewById(R.id.btnAppleCast).setOnClickListener { delegate?.onCast() }
        findViewById(R.id.btnAppleShare).setOnClickListener { delegate?.onShare() }
        findViewById(R.id.btnAppleInfo).setOnClickListener { showControls(); delegate?.onInfo() }
        findViewById(R.id.btnAppleResume).setOnClickListener { showControls(); delegate?.onContinueWatching() }
        btnCC.setOnClickListener { showControls(); showSubtitlesMenu() }
        btnAudio.setOnClickListener { showControls(); showAudioMenu() }
        btnSpeed.setOnClickListener { showControls(); showSpeedMenu() }
        btnRew.setOnClickListener { delegate?.onSeekBy(-10); flashBubble(false) }
        btnFwd.setOnClickListener { delegate?.onSeekBy(10); flashBubble(true) }
        findViewById(R.id.btnApplePlay).setOnClickListener { delegate?.onPlayPause() }

        setupVolumeBar()
        setupProgressScrubber()
        setupTapGestures()

        // Empieza escondido: el primer toque sobre el video lo revela.
        post { applyControlsVisible(false, animated = false) }
    }

    // ───────────────────────── API para el host ─────────────────────────

    /** El BOT rellena los detalles: titulo grande y linea pequeña (cap/servidor). */
    fun setTitleInfo(mainTitle: String?, subLine: String?) {
        txtTitle.text = mainTitle ?: "Reproduciendo"
        txtSubtitle.text = subLine ?: ""
        txtSubtitle.visibility = if (subLine.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun refreshVolumeBadge() {
        sbVolume.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    fun notifyPlayingStateChanged() = showControls()

    // ───────────────────────── Volumen ─────────────────────────

    private fun setupVolumeBar() {
        sbVolume.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        sbVolume.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
                scheduleHide()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ───────────────────────── Scrubber ─────────────────────────

    private fun setupProgressScrubber() {
        sbProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = delegate?.snapshot()?.durationMs ?: 0L
                    if (dur > 0) {
                        txtTimeLeft.text = formatTime((progress * dur) / 1000)
                        txtTimeRight.text = "-" + formatTime(dur - (progress * dur) / 1000)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                scrubbing = true
                cancelHide()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                scrubbing = false
                val dur = delegate?.snapshot()?.durationMs ?: 0L
                if (dur > 0 && seekBar != null) {
                    delegate?.onSeekTo((seekBar.progress.toLong() * dur) / 1000)
                }
                scheduleHide()
            }
        })
    }

    // ───────────────────────── Gestos (tap / doble tap) ─────────────────────────

    private fun setupTapGestures() {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsVisible) hideControls() else showControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (liveMode) return true
                val left = e.x < width / 2f
                delegate?.onSeekBy(if (left) -10 else 10)
                flashBubble(!left)
                return true
            }
        })
        // El overlay consume TODO toque: al usuario jamas le llega al embed/host.
        setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && controlsVisible) scheduleHide()
            true
        }
    }

    private fun flashBubble(right: Boolean) {
        val bubble = if (right) bubbleR else bubbleL
        bubble.visibility = View.VISIBLE
        bubble.alpha = 0f
        bubble.animate().alpha(1f).setDuration(120).withEndAction {
            bubble.animate().alpha(0f).setStartDelay(450).setDuration(260)
                .withEndAction { bubble.visibility = View.GONE }.start()
        }.start()
    }

    // ───────────────────────── Mostrar / ocultar ─────────────────────────

    fun showControls() {
        applyControlsVisible(true, animated = true)
        scheduleHide()
    }

    private fun hideControls() = applyControlsVisible(false, animated = true)

    private fun applyControlsVisible(visible: Boolean, animated: Boolean) {
        controlsVisible = visible
        cancelHide()
        val views = listOf<View>(topBar, bottomBlock, centerRow, scrimTop, scrimBottom, bubbleL, bubbleR)
        if (visible) {
            views.forEach {
                it.visibility = View.VISIBLE
                if (animated) {
                    it.alpha = 0f
                    it.animate().alpha(1f).setDuration(220).start()
                } else {
                    it.animate().cancel()
                    it.alpha = 1f
                }
            }
            refreshVolumeBadge()
        } else {
            // INVISIBLE (no GONE): el fondo sigue capturando el toque para
            // poder volver a mostrar, pero los botones ocultos NO reciben clicks.
            views.forEach {
                it.animate().cancel()
                it.visibility = View.INVISIBLE
                it.alpha = 0f
            }
        }
    }

    private fun scheduleHide() {
        cancelHide()
        val r = Runnable { if (controlsVisible && !scrubbing) hideControls() }
        hideRunnable = r
        postDelayed(r, AUTO_HIDE_MS)
    }

    private fun cancelHide() {
        hideRunnable?.let { removeCallbacks(it) }
        hideRunnable = null
    }

    // ───────────────────────── Polling del estado ─────────────────────────

    private fun startPolling() {
        val r = object : Runnable {
            override fun run() {
                try {
                    delegate?.snapshot()?.let { applyState(it) }
                } catch (_: Exception) {
                }
                postDelayed(this, POLL_MS)
            }
        }
        pollRunnable = r
        post(r)
    }

    private fun stopPolling() {
        pollRunnable?.let { removeCallbacks(it) }
        pollRunnable = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startPolling()
    }

    override fun onDetachedFromWindow() {
        stopPolling()
        cancelHide()
        super.onDetachedFromWindow()
    }

    private fun applyState(s: Snapshot) {
        // Play/pausa
        imgPlayPause.setImageResource(if (s.playing) R.drawable.ic_ios_pause else R.drawable.ic_ios_play)

        // Vivo vs VOD (un VOD real fija el modo; canales en vivo no tienen duracion)
        val live = delegate?.isLive() ?: (s.durationMs <= 0L)
        if (live != liveMode) {
            liveMode = live
            val extra = if (live) View.GONE else View.VISIBLE
            btnRew.visibility = extra
            btnFwd.visibility = extra
            sbProgress.visibility = extra
            txtTimeLeft.visibility = extra
            txtTimeRight.visibility = extra
            btnSpeed.visibility = if (live) View.GONE else View.VISIBLE
        }

        if (!live) {
            if (!scrubbing && s.durationMs > 0) {
                val pos = ((s.positionMs.coerceAtLeast(0) * 1000) / s.durationMs).toInt().coerceIn(0, 1000)
                sbProgress.progress = pos
                sbProgress.secondaryProgress = if (s.bufferedMs > 0)
                    ((s.bufferedMs.coerceAtMost(s.durationMs) * 1000) / s.durationMs).toInt() else 0
                txtTimeLeft.text = formatTime(s.positionMs)
                txtTimeRight.text = "-" + formatTime((s.durationMs - s.positionMs).coerceAtLeast(0))
            } else if (s.durationMs <= 0) {
                txtTimeLeft.text = formatTime(s.positionMs)
                txtTimeRight.text = ""
            }
        }

        // Velocidad
        lastSpeed = if (s.speed > 0f) s.speed else 1f
        txtSpeed.text = if (lastSpeed == 1f) "1x" else formatSpeed(lastSpeed)

        // Disponibilidad de pistas (gris = nada que mostrar)
        val ccN = delegate?.subtitleTrackCount() ?: 0
        val atN = delegate?.audioTrackCount() ?: 0
        btnCC.alpha = if (ccN > 0) 1f else 0.35f
        btnAudio.alpha = if (atN > 0) 1f else 0.35f
    }

    // ───────────────────────── Menus ─────────────────────────

    private fun showSpeedMenu() {
        val labels = SPEEDS.map { if (it == 1f) "Normal (1x)" else "${formatSpeed(it)}x" }.toTypedArray()
        val checked = SPEEDS.indices.minByOrNull { kotlin.math.abs(SPEEDS[it] - lastSpeed) } ?: 2
        AlertDialog.Builder(context, R.style.Theme_AppCompat_Dialog).apply {
            title = "Velocidad"
            setSingleChoiceItems(labels, checked) { dlg, which ->
                delegate?.onSpeedPicked(SPEEDS[which])
                dlg.dismiss()
            }
            setNegativeButton("Cancelar", null)
        }.show()
    }

    private fun showSubtitlesMenu() {
        val n = delegate?.subtitleTrackCount() ?: 0
        if (n <= 0) {
            Toast.makeText(context, "Subtítulos no disponibles en este servidor", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = (listOf("Desactivados") + (1..n).map { "Pista $it" }).toTypedArray()
        AlertDialog.Builder(context, R.style.Theme_AppCompat_Dialog).apply {
            title = "Subtítulos"
            setSingleChoiceItems(labels, -1) { dlg, which ->
                delegate?.onSubtitlesPicked(which - 1) // -1 = apagar
                dlg.dismiss()
            }
            setNegativeButton("Cancelar", null)
        }.show()
    }

    private fun showAudioMenu() {
        val n = delegate?.audioTrackCount() ?: 0
        if (n <= 0) {
            Toast.makeText(context, "Audio no disponible en este servidor", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = (1..n).map { "Pista de audio $it" }.toTypedArray()
        AlertDialog.Builder(context, R.style.Theme_AppCompat_Dialog).apply {
            title = "Audio"
            setSingleChoiceItems(labels, -1) { dlg, which ->
                delegate?.onAudioPicked(which)
                dlg.dismiss()
            }
            setNegativeButton("Cancelar", null)
        }.show()
    }

    // ───────────────────────── Utilidades ─────────────────────────

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun formatSpeed(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
}

package com.samuelpart.iptvplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.samuelpart.iptvplayer.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    
    // LibVLC player variables - Completely replacing ExoPlayer/Media3!
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    
    private var channelName: String = "Canal"
    private var channelUrl: String? = null
    // Headers captured during real-time web extraction (required by most video hosters)
    private var streamReferer: String? = null
    private var streamUserAgent: String? = null
    private var pageResolveAttempted = false
    private var isControllerVisible = true
    private var startPosition: Long = 0L
    private var pendingSeekPosition: Long = 0L
    private var isLiveTv: Boolean = false

    // List of alternative stream sources from web browser detection
    private var allSources: ArrayList<String>? = null

    // Remote control variables
    private var activeCastDevice: CastDevice? = null
    private var isCastPlaying = true
    private var castPollingJob: kotlinx.coroutines.Job? = null

    // Google Cast variables
    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            castSession = session
            loadMediaOnTv(session)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            castSession = session
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {}

        override fun onSessionEnded(session: CastSession, error: Int) {
            castSession = null
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    // MediaRouter for Google Cast discovering inside the unified list
    private lateinit var mediaRouter: MediaRouter
    private val discoveredDevicesList = mutableListOf<CastDevice>()
    private lateinit var castDeviceAdapter: CastDeviceAdapter

    private val mediaRouterCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
            super.onRouteAdded(router, route)
            if (route.supportsControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK) ||
                route.supportsControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))) {
                
                val address = route.id
                if (discoveredDevicesList.none { it.ip == address }) {
                    discoveredDevicesList.add(CastDevice(route.name, address, 0, "Google Cast", route))
                    runOnUiThread {
                        castDeviceAdapter.updateList(discoveredDevicesList)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            binding = ActivityPlayerBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error de inicialización de pantalla", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Make activity fullscreen
        hideSystemUI()

        // Get safe parameters from intent
        channelName = intent.getStringExtra("channelName") ?: "Canal Desconocido"
        channelUrl = intent.getStringExtra("channelUrl")
        allSources = intent.getStringArrayListExtra("allSources")
        startPosition = intent.getLongExtra("startPosition", 0L)
        isLiveTv = intent.getBooleanExtra("isLiveTv", false)
        // Headers captured by the real-time extractor in the detail screen (if any)
        streamReferer = intent.getStringExtra("streamReferer")
        streamUserAgent = intent.getStringExtra("streamUserAgent")

        if (channelUrl.isNullOrEmpty()) {
            Toast.makeText(this, "Error: Enlace de reproducción no disponible", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.txtPlayingName.text = channelName
        
        if (isLiveTv) {
            // Hide rewind/forward and timeline for TV channels as requested
            binding.btnRewind.visibility = View.GONE
            binding.btnForward.visibility = View.GONE
            binding.layoutBottomTimeline.visibility = View.GONE
        }
        
        // Clicking back button should close player and return to preceding screen (inline player) with exact time!
        binding.btnBack.setOnClickListener {
            returnToSmallScreen()
        }

        // Cast / Share to TV button click listener (Checks permissions first!)
        binding.btnShareTv.setOnClickListener {
            checkCastPermissionsAndScan()
        }

        // PiP / Floating Window button click listener
        binding.btnPip.setOnClickListener {
            enterPipMode()
        }

        // Initialize source selector button visibility based on availability of options
        if (!allSources.isNullOrEmpty()) {
            binding.btnSelectSource.visibility = View.VISIBLE
            binding.btnSelectSource.setOnClickListener {
                showSourceSelectorDialog()
            }
        } else {
            binding.btnSelectSource.visibility = View.GONE
        }

        // Initialize Double-Tap Gesture Detector for 10s skip (disabled for Live TV)
        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                if (isLiveTv) return false
                val width = binding.viewTouchTarget.width
                val x = e.x
                if (x < width / 2) {
                    // Left double-tap -> Rewind 10s
                    mediaPlayer?.let { player ->
                        val target = (player.time - 10000).coerceAtLeast(0)
                        player.time = target
                        animateDoubleTapIndicator(true)
                    }
                } else {
                    // Right double-tap -> Forward 10s
                    mediaPlayer?.let { player ->
                        val total = player.length
                        val target = player.time + 10000
                        if (total > 0) {
                            player.time = target.coerceAtMost(total)
                        } else {
                            player.time = target
                        }
                        animateDoubleTapIndicator(false)
                    }
                }
                return true
            }

            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                val isVisible = binding.layoutPlayerControls.visibility == View.VISIBLE
                if (isVisible) {
                    binding.layoutPlayerControls.visibility = View.GONE
                    hideSystemUI()
                } else {
                    binding.layoutPlayerControls.visibility = View.VISIBLE
                    startAutoHideControlsTimer()
                }
                return true
            }
        })

        // Feed touch events from touch target to gesture detector
        binding.viewTouchTarget.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        // Remote control panel click listeners
        binding.btnCastPlayPause.setOnClickListener {
            toggleCastPlayPause()
        }
        binding.btnCastDisconnect.setOnClickListener {
            stopCastingAndRestoreLocalPlay()
        }

        // Local Play/Pause button click listener
        binding.btnPlayPause.setOnClickListener {
            togglePlayPauseInternal()
        }

        // Rewind and Forward 10s button listeners
        binding.btnRewind.setOnClickListener {
            mediaPlayer?.let {
                val target = (it.time - 10000).coerceAtLeast(0)
                it.time = target
                Toast.makeText(this, "-10s", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnForward.setOnClickListener {
            mediaPlayer?.let {
                val total = it.length
                val target = (it.time + 10000)
                if (total > 0) {
                    it.time = target.coerceAtMost(total)
                } else {
                    it.time = target
                }
                Toast.makeText(this, "+10s", Toast.LENGTH_SHORT).show()
            }
        }

        // Seekbar progress user tracking
        binding.vlcSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.let {
                        val totalMs = it.length
                        if (totalMs > 0) {
                            val seekTarget = (progress * totalMs) / 100
                            it.time = seekTarget
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Safely initialize Google Cast Context
        try {
            castContext = CastContext.getSharedInstance(this)
            castSession = castContext?.sessionManager?.currentCastSession
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Initialize MediaRouter for Chromecast discovery
        mediaRouter = MediaRouter.getInstance(this)

        initializePlayer()
        startAutoHideControlsTimer()
    }

    private fun initializePlayer() {
        if (mediaPlayer != null) return

        val streamUrl = channelUrl ?: return

        // If this is a streaming page / embedded player instead of a direct stream,
        // visit the source page RIGHT NOW, extract the fresh temporary video URL
        // (they expire every few hours, so nothing is stored) and then play it.
        // Live TV channels are always direct streams: never resolve them.
        if (!isLiveTv && CineScraper.shouldResolvePage(streamUrl)) {
            if (pageResolveAttempted) {
                binding.playerProgress.visibility = View.GONE
                Toast.makeText(this, "Error: No se pudo extraer el video en tiempo real", Toast.LENGTH_SHORT).show()
                return
            }
            pageResolveAttempted = true
            binding.playerProgress.visibility = View.VISIBLE
            lifecycleScope.launch {
                val resolved = CineScraper.resolveBestVideoUrl(this@PlayerActivity, streamUrl)
                if (isFinishing || isDestroyed) return@launch
                if (resolved != null) {
                    channelUrl = resolved.url
                    streamReferer = resolved.referer
                    streamUserAgent = resolved.userAgent
                    initializePlayer()
                } else {
                    binding.playerProgress.visibility = View.GONE
                    Toast.makeText(this@PlayerActivity, "Error: No se pudo extraer el video en tiempo real", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        binding.playerProgress.visibility = View.VISIBLE

        val savedPos = getSharedPreferences("iptv_pref", android.content.Context.MODE_PRIVATE)
            .getLong("pos_$channelName", 0L)
        pendingSeekPosition = if (startPosition > 0L) startPosition else savedPos

        try {
            // Optimized VLC arguments for peak Android IPTV performance
            val options = ArrayList<String>().apply {
                add("-vvv")
                add("--http-reconnect")
                add("--network-caching=800") // Ultra-low latency startup buffering (under 1 second!)
                add("--file-caching=800")
                add("--clock-jitter=0") // Instantly sync player clock
                add("--rtsp-tcp") // Force TCP for lightning fast RTSP connections
                add("--drop-late-frames")
                add("--skip-frames")
            }
            libVlc = LibVLC(this, options)
            
            mediaPlayer = MediaPlayer(libVlc).apply {
                // Bind layout surface directly
                attachViews(binding.vlcVideoLayout, null, true, false)
                
                // Add event callbacks for buffering and states
                setEventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.Buffering -> {
                            val buffering = event.getBuffering()
                            if (buffering < 100f) {
                                binding.playerProgress.visibility = View.VISIBLE
                            } else {
                                binding.playerProgress.visibility = View.GONE
                            }
                        }
                        MediaPlayer.Event.Playing -> {
                            binding.playerProgress.visibility = View.GONE
                            updatePlayPauseButtonIcon(true)
                            
                            if (pendingSeekPosition > 0L) {
                                mediaPlayer?.time = pendingSeekPosition
                                Toast.makeText(this@PlayerActivity, "Reanudando desde ${formatTime(pendingSeekPosition)}", Toast.LENGTH_SHORT).show()
                                pendingSeekPosition = 0L // reset
                            }
                            
                            startTimelineUpdates()
                        }
                        MediaPlayer.Event.Paused -> {
                            updatePlayPauseButtonIcon(false)
                        }
                        MediaPlayer.Event.Stopped -> {
                            binding.playerProgress.visibility = View.GONE
                        }
                        MediaPlayer.Event.EndReached -> {
                            binding.playerProgress.visibility = View.GONE
                            finish()
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            binding.playerProgress.visibility = View.GONE
                            Toast.makeText(this@PlayerActivity, "Error de reproducción con VLC", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            // Create and set Media
            val media = Media(libVlc, Uri.parse(streamUrl)).apply {
                // Enable hardware decoding for flawless 4K stream performance
                setHWDecoderEnabled(true, false)
                // Streams extracted in real time often require these headers or they refuse to load
                if (!streamReferer.isNullOrEmpty()) addOption(":http-referrer=$streamReferer")
                if (!streamUserAgent.isNullOrEmpty()) addOption(":http-user-agent=$streamUserAgent")
            }
            mediaPlayer?.media = media
            media.release()
            
            mediaPlayer?.play()
            
        } catch (e: Exception) {
            e.printStackTrace()
            binding.playerProgress.visibility = View.GONE
            Toast.makeText(
                this,
                "Error al iniciar el reproductor VLC: ${e.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private var timelineJob: kotlinx.coroutines.Job? = null
    private fun startTimelineUpdates() {
        timelineJob?.cancel()
        timelineJob = lifecycleScope.launch {
            while (mediaPlayer != null) {
                val player = mediaPlayer ?: break
                if (player.isPlaying) {
                    val currentMs = player.time
                    val totalMs = player.length
                    
                    if (totalMs > 0) {
                        binding.vlcSeekBar.progress = ((currentMs * 100) / totalMs).toInt()
                        binding.txtCurrentTime.text = formatTime(currentMs)
                        binding.txtTotalTime.text = formatTime(totalMs)
                        
                        // Save playback progress to resume later if user exits!
                        getSharedPreferences("iptv_pref", android.content.Context.MODE_PRIVATE)
                            .edit()
                            .putLong("pos_$channelName", currentMs)
                            .apply()
                    } else {
                        // Live IPTV Streams
                        binding.vlcSeekBar.progress = 100
                        binding.txtCurrentTime.text = "LIVE"
                        binding.txtTotalTime.text = "LIVE"
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSecs = millis / 1000
        val seconds = totalSecs % 60
        val minutes = (totalSecs / 60) % 60
        val hours = totalSecs / 3600
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private var hideControlsJob: kotlinx.coroutines.Job? = null
    private fun startAutoHideControlsTimer() {
        hideControlsJob?.cancel()
        hideControlsJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(5000)
            binding.layoutPlayerControls.visibility = View.GONE
            hideSystemUI()
        }
    }

    private fun togglePlayPauseInternal() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            updatePlayPauseButtonIcon(false)
        } else {
            player.play()
            updatePlayPauseButtonIcon(true)
        }
    }

    private fun updatePlayPauseButtonIcon(isPlaying: Boolean) {
        if (isPlaying) {
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun showSourceSelectorDialog() {
        val sources = allSources ?: return
        if (sources.isEmpty()) return

        val friendlyNames = sources.mapIndexed { index, url ->
            val isCurrent = (url == channelUrl)
            val indicator = if (isCurrent) " (Actual)" else ""
            "Opción ${index + 1}: ${getDomainName(url)} [${getFileExtension(url)}]$indicator"
        }.toTypedArray()

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Cambiar de Servidor / Calidad")
            .setItems(friendlyNames) { _, which ->
                val selectedUrl = sources[which]
                if (selectedUrl != channelUrl) {
                    switchSource(selectedUrl)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun switchSource(newUrl: String) {
        channelUrl = newUrl
        // Reset extraction state so the new server gets its own fresh real-time resolution
        streamReferer = null
        streamUserAgent = null
        pageResolveAttempted = false
        channelName = "Video Web: " + getDomainName(newUrl)
        binding.txtPlayingName.text = channelName
        
        releasePlayer()
        initializePlayer()
        
        Toast.makeText(this, "Cambiando de servidor...", Toast.LENGTH_SHORT).show()
    }

    private fun getFileExtension(url: String): String {
        return try {
            val cleanUrl = url.lowercase().split("?")[0]
            val lastDot = cleanUrl.lastIndexOf('.')
            if (lastDot != -1) {
                cleanUrl.substring(lastDot + 1).uppercase()
            } else {
                "STREAM"
            }
        } catch (e: Exception) {
            "VIDEO"
        }
    }

    private fun getDomainName(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return "Servidor"
            host.replace("www.", "")
        } catch (e: Exception) {
            "Servidor"
        }
    }

    private fun showUnifiedCastDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_cast_selector, null)
        
        val layoutScanning = dialogView.findViewById<LinearLayout>(R.id.layoutCastScanning)
        val rvDevices = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvCastDevices)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCancelCast)

        discoveredDevicesList.clear()
        
        rvDevices.layoutManager = LinearLayoutManager(this)
        castDeviceAdapter = CastDeviceAdapter(emptyList()) { selectedDevice ->
            connectAndCastToDevice(selectedDevice)
        }
        rvDevices.adapter = castDeviceAdapter

        val dialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            try {
                mediaRouter.removeCallback(mediaRouterCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            val selector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build()
            mediaRouter.addCallback(selector, mediaRouterCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        layoutScanning.visibility = View.VISIBLE
        lifecycleScope.launch {
            val localDevices = UniversalCaster.discoverDevices(this@PlayerActivity)
            
            for (dev in localDevices) {
                if (discoveredDevicesList.none { it.ip == dev.ip }) {
                    discoveredDevicesList.add(dev)
                }
            }

            discoveredDevicesList.add(CastDevice("Transmitir con Smart View del Sistema 📺", "system_cast", 0, "SystemCast"))

            layoutScanning.visibility = View.GONE
            castDeviceAdapter.updateList(discoveredDevicesList)
            
            if (discoveredDevicesList.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "No se detectaron televisores en tu Wi-Fi.", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun connectAndCastToDevice(device: CastDevice) {
        val streamUrl = channelUrl ?: return
        
        if (device.type == "SystemCast") {
            launchSystemCastChooser()
            return
        }

        mediaPlayer?.pause()
        Toast.makeText(this, "Conectando con: ${device.name}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            var isSuccess = false

            when (device.type) {
                "Google Cast" -> {
                    val route = device.routeInfo
                    if (route != null) {
                        try {
                            mediaRouter.selectRoute(route)
                            isSuccess = true
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                "Roku" -> {
                    isSuccess = UniversalCaster.castToRoku(device.ip, streamUrl, channelName)
                }
                "DLNA" -> {
                    val controlUrl = device.controlUrl ?: "http://${device.ip}:1400/AVTransport/control"
                    isSuccess = UniversalCaster.castToDlna(controlUrl, streamUrl, channelName)
                }
            }

            if (isSuccess) {
                activeCastDevice = device
                showRemoteControlUi(device.name)
                Toast.makeText(this@PlayerActivity, "¡Transmitiendo con éxito en: ${device.name}! 📺", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@PlayerActivity, "No se pudo conectar a ${device.name}. Intenta de nuevo.", Toast.LENGTH_SHORT).show()
                mediaPlayer?.play()
            }
        }
    }

    private fun loadMediaOnTv(session: CastSession) {
        val streamUrl = channelUrl ?: return
        val remoteMediaClient = session.remoteMediaClient ?: return

        try {
            val movieMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, channelName)
            }

            val mediaInfo = MediaInfo.Builder(streamUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(getMimeType(streamUrl))
                .setMetadata(movieMetadata)
                .build()

            val mediaLoadRequestData = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .build()

            remoteMediaClient.load(mediaLoadRequestData)
            
            Toast.makeText(this, "Transmitiendo en tu TV: $channelName 📺", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al enviar transmisión a la TV", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMimeType(url: String): String {
        val cleanUrl = url.lowercase().split("?")[0]
        return when {
            cleanUrl.endsWith(".m3u8") || cleanUrl.contains("m3u8") || cleanUrl.contains("/hls/") -> "application/x-mpegURL"
            cleanUrl.endsWith(".mpd") -> "application/dash+xml"
            cleanUrl.endsWith(".ts") -> "video/mp2t"
            else -> "video/mp4"
        }
    }

    private fun releasePlayer() {
        timelineJob?.cancel()
        timelineJob = null
        hideControlsJob?.cancel()
        hideControlsJob = null
        
        mediaPlayer?.let {
            val currentMs = it.time
            if (currentMs > 0L) {
                getSharedPreferences("iptv_pref", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putLong("pos_$channelName", currentMs)
                    .apply()
            }
            it.stop()
            it.detachViews()
            it.release()
            mediaPlayer = null
        }
        libVlc?.let {
            it.release()
            libVlc = null
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT > 23) {
            initializePlayer()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        try {
            castContext?.sessionManager?.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        if (Build.VERSION.SDK_INT <= 23) {
            initializePlayer()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            castContext?.sessionManager?.removeSessionManagerListener(sessionManagerListener, CastSession::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        if (Build.VERSION.SDK_INT <= 23) {
            releasePlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT > 23) {
            releasePlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        castPollingJob?.cancel()
        castPollingJob = null
        releasePlayer()
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    private fun showRemoteControlUi(deviceName: String) {
        mediaPlayer?.pause()
        binding.vlcVideoLayout.visibility = View.GONE
        binding.playerProgress.visibility = View.GONE
        binding.layoutCastController.visibility = View.VISIBLE
        
        binding.txtConnectedTv.text = "Transmitiendo en $deviceName 📺"
        binding.txtCastPlayingTitle.text = "Reproduciendo: $channelName"
        isCastPlaying = true
        binding.btnCastPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        
        startCastPolling()
    }

    private fun toggleCastPlayPause() {
        val device = activeCastDevice ?: return
        isCastPlaying = !isCastPlaying
        
        if (isCastPlaying) {
            binding.btnCastPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            Toast.makeText(this, "Reanudando en tu TV...", Toast.LENGTH_SHORT).show()
            
            lifecycleScope.launch {
                when (device.type) {
                    "Google Cast" -> {
                        castSession?.remoteMediaClient?.play()
                    }
                    "Roku" -> {
                        UniversalCaster.sendRokuKeypress(device.ip, "Play")
                    }
                    "DLNA" -> {
                        val controlUrl = device.controlUrl ?: "http://${device.ip}:1400/AVTransport/control"
                        UniversalCaster.sendDlnaPlayResumeCommand(controlUrl)
                    }
                }
            }
        } else {
            binding.btnCastPlayPause.setImageResource(android.R.drawable.ic_media_play)
            Toast.makeText(this, "Pausando en tu TV...", Toast.LENGTH_SHORT).show()
            
            lifecycleScope.launch {
                when (device.type) {
                    "Google Cast" -> {
                        castSession?.remoteMediaClient?.pause()
                    }
                    "Roku" -> {
                        UniversalCaster.sendRokuKeypress(device.ip, "Pause")
                    }
                    "DLNA" -> {
                        val controlUrl = device.controlUrl ?: "http://${device.ip}:1400/AVTransport/control"
                        UniversalCaster.sendDlnaPauseCommand(controlUrl)
                    }
                }
            }
        }
    }

    private fun stopCastingAndRestoreLocalPlay() {
        val device = activeCastDevice ?: return
        Toast.makeText(this, "Deteniendo transmisión en TV...", Toast.LENGTH_SHORT).show()
        
        castPollingJob?.cancel()
        castPollingJob = null
        
        lifecycleScope.launch {
            when (device.type) {
                "Google Cast" -> {
                    try {
                        castContext?.sessionManager?.endCurrentSession(true)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                "Roku" -> {
                    UniversalCaster.sendRokuKeypress(device.ip, "Back")
                }
                "DLNA" -> {
                    val controlUrl = device.controlUrl ?: "http://${device.ip}:1400/AVTransport/control"
                    UniversalCaster.sendDlnaStopCommand(controlUrl)
                }
            }
            
            runOnUiThread {
                activeCastDevice = null
                binding.layoutCastController.visibility = View.GONE
                binding.vlcVideoLayout.visibility = View.VISIBLE
                mediaPlayer?.play()
                Toast.makeText(this@PlayerActivity, "Reproduciendo localmente.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCastPolling() {
        castPollingJob?.cancel()
        castPollingJob = lifecycleScope.launch {
            while (activeCastDevice != null) {
                val device = activeCastDevice ?: break
                
                withContext(Dispatchers.IO) {
                    try {
                        when (device.type) {
                            "Google Cast" -> {
                                val status = castSession?.remoteMediaClient?.mediaStatus
                                if (status != null) {
                                    val isPlaying = status.playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING
                                    withContext(Dispatchers.Main) {
                                        updateRemotePlayPauseButton(isPlaying)
                                    }
                                }
                            }
                            "Roku" -> {
                                val url = URL("http://${device.ip}:8060/query/media-player")
                                val connection = url.openConnection() as HttpURLConnection
                                connection.connectTimeout = 1000
                                connection.readTimeout = 1000
                                if (connection.responseCode in 200..299) {
                                    val xml = connection.inputStream.bufferedReader().use { it.readText() }
                                    val statePattern = Regex("""state\s*=\s*"([^"]*)""")
                                    val match = statePattern.find(xml)
                                    val state = match?.groupValues?.get(1) ?: "unknown"
                                    withContext(Dispatchers.Main) {
                                        updateRemotePlayPauseButton(state == "play" || state == "playing")
                                    }
                                }
                            }
                            "DLNA" -> {
                                val controlUrl = device.controlUrl ?: "http://${device.ip}:1400/AVTransport/control"
                                val isPlaying = UniversalCaster.queryDlnaTransportState(controlUrl)
                                withContext(Dispatchers.Main) {
                                    updateRemotePlayPauseButton(isPlaying)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    private fun updateRemotePlayPauseButton(isPlaying: Boolean) {
        isCastPlaying = isPlaying
        if (isPlaying) {
            binding.btnCastPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            binding.btnCastPlayPause.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun launchSystemCastChooser() {
        val streamUrl = channelUrl ?: return
        try {
            val castIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(streamUrl), "video/*")
                putExtra("title", channelName)
                putExtra("android.intent.extra.Title", channelName)
            }
            startActivity(Intent.createChooser(castIntent, "Selecciona tu Smart TV o Dispositivo de Transmisión 📺"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val CAST_PERMISSION_REQUEST_CODE = 1001

    private fun checkCastPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            showUnifiedCastDialog()
        } else {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), CAST_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAST_PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                showUnifiedCastDialog()
            } else {
                Toast.makeText(this, "Para detectar televisores en tu Wi-Fi, debes permitir el permiso de dispositivos cercanos.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Concede el permiso de superposición para activar la ventana flotante", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return
            }
        }

        // Enter native Picture-in-Picture mode!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = android.app.PictureInPictureParams.Builder().build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                e.printStackTrace()
                @Suppress("DEPRECATION")
                enterPictureInPictureMode()
            }
        } else {
            @Suppress("DEPRECATION")
            enterPictureInPictureMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // Hide all controls so only the video layout is visible in PIP
            binding.layoutPlayerControls.visibility = View.GONE
        } else {
            // Restore visibility of controls
            binding.layoutPlayerControls.visibility = View.VISIBLE
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(this)
            } else {
                true
            }
            if (hasOverlay) {
                enterPipMode()
            }
        }
    }

    private fun animateDoubleTapIndicator(isLeft: Boolean) {
        val indicator = if (isLeft) binding.imgDoubleTapLeft else binding.imgDoubleTapRight
        indicator.visibility = View.VISIBLE
        indicator.alpha = 0f
        indicator.scaleX = 0.8f
        indicator.scaleY = 0.8f
        
        indicator.animate()
            .alpha(1f)
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(300)
            .withEndAction {
                indicator.animate()
                    .alpha(0f)
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .setStartDelay(400) // Hold on screen for 400ms before fading out!
                    .setDuration(350)
                    .withEndAction {
                        indicator.visibility = View.GONE
                        indicator.animate().startDelay = 0 // reset start delay for next animation!
                    }
                    .start()
            }
            .start()
    }

    private fun returnToSmallScreen() {
        val currentMs = mediaPlayer?.time ?: 0L
        val data = Intent().apply {
            putExtra("endPosition", currentMs)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        returnToSmallScreen()
        super.onBackPressed()
    }
}

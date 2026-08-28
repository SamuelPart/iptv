package com.samuelpart.iptvplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.samuelpart.iptvplayer.databinding.ActivityCineTvShowDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CineTvShowDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCineTvShowDetailBinding
    private lateinit var media: CineMedia
    private lateinit var episodeAdapter: CineEpisodeAdapter
    private var seasons: List<Int> = emptyList()

    private var libVlc: org.videolan.libvlc.LibVLC? = null
    private var mediaPlayer: org.videolan.libvlc.MediaPlayer? = null
    private var isPlayingInline = false
    private var inlineTimelineJob: kotlinx.coroutines.Job? = null
    private var currentInlineTitle: String = ""
    private var pendingSeekPosition: Long = 0L

    private val fullscreenLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val endPosition = result.data?.getLongExtra("endPosition", 0L) ?: 0L
            if (endPosition > 0L) {
                startInlinePlayback(endPosition)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCineTvShowDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.post { UiMotion.reveal(binding.layoutTvDetailCard) }

        @Suppress("DEPRECATION")
        val extraMedia = intent.getSerializableExtra("media") as? CineMedia
        if (extraMedia == null) {
            finish()
            return
        }
        media = extraMedia

        if (media.episodes.isEmpty() && !media.url.isNullOrEmpty() && CineRepository.isRemotePlaylist(media.url)) {
            setupViews()
            binding.txtTvOverview.text = "Desglosando capítulos de la serie desde el servidor..."
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    CineRepository.fetchTmdMetadata(media)
                    if (media.tmdbId != null) {
                        CineRepository.fetchTmdTrailer(media)
                        CineRepository.fetchTmdCredits(media) // Fetch cast list!
                    }
                    val remoteEpisodes = CineRepository.fetchEpisodesFromPlaylist(media.url)
                    media.episodes = remoteEpisodes
                }
                setupViews()
            }
        } else {
            setupViews()
            // Headless background scraper to find series episodes or mirrors
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    if (media.overview.isNullOrEmpty() || media.cast.isEmpty()) {
                        CineRepository.fetchTmdMetadata(media)
                        if (media.tmdbId != null) {
                            CineRepository.fetchTmdTrailer(media)
                            CineRepository.fetchTmdCredits(media) // Fetch cast list!
                        }
                    }
                    
                    // Silent Web Scrape Search in second plane
                    val scraped = CineScraper.scrapeVideoUrls(media.title)
                    if (scraped.isNotEmpty() && media.episodes.isEmpty()) {
                        val remoteEpisodes = CineRepository.fetchEpisodesFromPlaylist(scraped[0])
                        if (remoteEpisodes.isNotEmpty()) {
                            media.episodes = remoteEpisodes
                        }
                    }
                }
                setupViews()
            }
        }
    }

    private fun setupViews() {
        binding.txtTvTitle.text = media.title
        binding.txtTvOverview.text = if (media.overview.isNullOrEmpty()) {
            "Sin sinopsis disponible."
        } else {
            media.overview
        }

        // Meta Text
        val rating = if (media.rating != null && media.rating!! > 0.0) {
            String.format("★ %.1f", media.rating)
        } else {
            "★ N/A"
        }
        binding.txtTvMeta.text = "$rating  |  Serie de TV"

        // Load high-quality clean scenic background image (without text) if available
        val backdropImageToLoad = when {
            !media.backdropUrl.isNullOrEmpty() -> media.backdropUrl
            !media.posterUrl.isNullOrEmpty() -> media.posterUrl
            else -> media.rawLogo
        }

        Glide.with(this)
            .asBitmap()
            .load(backdropImageToLoad)
            .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                override fun onResourceReady(
                    resource: android.graphics.Bitmap,
                    transition: com.bumptech.glide.request.transition.Transition<in android.graphics.Bitmap>?
                ) {
                    // Extract dominant color and apply the gorgeous vertical gradient with curved corners!
                    applyDynamicGradient(resource)
                    binding.imgTvBackdrop.setImageBitmap(resource)
                }

                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
            })

        // iOS floating poster + real TMDB rating pill
        Glide.with(this)
            .load(if (!media.posterUrl.isNullOrEmpty()) media.posterUrl else media.rawLogo)
            .placeholder(R.drawable.bg_placeholder)
            .into(binding.imgTvPoster)

        val ratingValue = media.rating ?: 0.0
        binding.txtTvRatingBadge.visibility = if (ratingValue > 0.0) View.VISIBLE else View.GONE
        if (ratingValue > 0.0) binding.txtTvRatingBadge.text = String.format("★ %.1f", ratingValue)

        binding.btnTvBack.setOnClickListener {
            finish()
        }

        // Setup Cast List dynamically, matching the beautiful Spiderman design!
        if (media.cast.isNotEmpty()) {
            binding.layoutCastSection.visibility = View.VISIBLE
            binding.rvCast.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            val castAdapter = CineCastAdapter(media.cast)
            binding.rvCast.adapter = castAdapter
        } else {
            binding.layoutCastSection.visibility = View.GONE
        }

        // Setup Episodes RecyclerView
        binding.rvEpisodes.layoutManager = LinearLayoutManager(this)
        episodeAdapter = CineEpisodeAdapter(emptyList()) { episode ->
            playEpisode(episode)
        }
        binding.rvEpisodes.adapter = episodeAdapter

        // Extract and populate Seasons Spinner
        seasons = media.episodes.map { it.season }.distinct().sorted()
        if (seasons.isNotEmpty()) {
            binding.layoutEpisodesHeader.visibility = View.VISIBLE
            val seasonLabels = seasons.map { "Temporada $it" }
            val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, seasonLabels).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            binding.spinnerSeason.adapter = spinnerAdapter
            binding.spinnerSeason.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedSeason = seasons[position]
                    val filteredEpisodes = media.episodes.filter { it.season == selectedSeason }
                    episodeAdapter.updateList(filteredEpisodes)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } else {
            binding.layoutEpisodesHeader.visibility = View.GONE
        }

        // Config Play button for TV Show
        binding.btnTvPlay.setOnClickListener {
            if (isPlayingInline) {
                stopInlinePlayback()
            } else {
                startInlinePlayback()
            }
        }

        // Show/Hide Trailer button dynamically inside custom modal dialog WebView!
        if (!media.trailerUrl.isNullOrEmpty()) {
            binding.btnTvTrailer.visibility = View.VISIBLE
            binding.btnTvTrailer.setOnClickListener {
                showTrailerModal(media.trailerUrl!!)
            }
        } else {
            binding.btnTvTrailer.visibility = View.GONE
        }

        // Configure Share button click listener
        binding.btnTvShare.setOnClickListener {
            shareMovieDetails()
        }
    }

    private fun applyDynamicGradient(bitmap: android.graphics.Bitmap) {
        val dominantColor = getDominantColor(bitmap)
        
        // Darken the dominant color to ensure white text readability
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(dominantColor, hsv)
        hsv[2] *= 0.35f 
        val darkenedColor = android.graphics.Color.HSVToColor(hsv)
        
        // Create semi-transparent glass colors for the frosted glassmorphism effect
        // Top of the card: 70% opacity (~0xB3) of the darkened dominant series theme color
        val topGlassColor = android.graphics.Color.argb(
            0xB3,
            android.graphics.Color.red(darkenedColor),
            android.graphics.Color.green(darkenedColor),
            android.graphics.Color.blue(darkenedColor)
        )
        // Bottom of the card: 92% opacity (~0xEB) of deep dark gray to make scrolling text fully readable
        val bottomGlassColor = android.graphics.Color.argb(0xEB, 0x12, 0x12, 0x12)
        
        // Create a perfect top-corners rounded GradientDrawable!
        val gradientDrawable = android.graphics.drawable.GradientDrawable()
        gradientDrawable.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        val radius = 36 * resources.displayMetrics.density
        gradientDrawable.cornerRadii = floatArrayOf(
            radius, radius, // top-left
            radius, radius, // top-right
            0f, 0f,         // bottom-right
            0f, 0f          // bottom-left
        )
        gradientDrawable.colors = intArrayOf(topGlassColor, bottomGlassColor)
        
        // Add a premium subtle 1.2dp white border to simulate light refraction on glass edges!
        gradientDrawable.setStroke(
            (1.2f * resources.displayMetrics.density).toInt(),
            android.graphics.Color.parseColor("#28FFFFFF") // 16% opacity white
        )
        
        binding.layoutTvDetailCard.background = gradientDrawable
    }

    private fun getDominantColor(bitmap: android.graphics.Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0
        
        val xStep = maxOf(1, w / 15)
        val yStep = maxOf(1, h / 15)
        
        for (x in 0 until w step xStep) {
            for (y in 0 until h step yStep) {
                val pixel = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                
                val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
                if (luminance in 35.0..220.0) {
                    rSum += r
                    gSum += g
                    bSum += b
                    count++
                }
            }
        }
        
        if (count == 0) {
            for (x in 0 until w step xStep) {
                for (y in 0 until h step yStep) {
                    val pixel = bitmap.getPixel(x, y)
                    rSum += android.graphics.Color.red(pixel)
                    gSum += android.graphics.Color.green(pixel)
                    bSum += android.graphics.Color.blue(pixel)
                    count++
                }
            }
        }
        
        val avgR = (rSum / count).toInt()
        val avgG = (gSum / count).toInt()
        val avgB = (bSum / count).toInt()
        
        return android.graphics.Color.rgb(avgR, avgG, avgB)
    }

    private fun playEpisode(episode: Episode) {
        if (isPlayingInline) {
            stopInlinePlayback()
        }
        startInlinePlayback(0L, episode.url, "${media.title} - S${episode.season}E${episode.episodeNumber}: ${episode.title}")
    }

    private fun startInlinePlayback(startMs: Long = 0L, customUrl: String? = null, customTitle: String? = null) {
        val streamUrl = customUrl ?: if (media.episodes.isNotEmpty()) media.episodes[0].url else media.url
        if (streamUrl.isNullOrEmpty()) return

        val displayTitle = customTitle ?: media.title
        currentInlineTitle = displayTitle

        // 1. Hide Backdrop Image and show Inline Player Layout
        binding.imgTvBackdrop.visibility = View.INVISIBLE
        binding.viewBackdropFade.visibility = View.GONE
        binding.layoutInlinePlayer.visibility = View.VISIBLE
        binding.inlineProgress.visibility = View.VISIBLE
        binding.layoutBackdropHeaderContainer.translationZ = 50f

        // 2. Update main play button to DETENER (stop button)
        binding.btnTvPlay.text = "■  DETENER"
        binding.btnTvPlay.setBackgroundResource(R.drawable.bg_button_secondary) // Change style to stop
        isPlayingInline = true

        // Animate detail card downward by 100dp to fully expose the 380dp video player
        val translationY = 100f * resources.displayMetrics.density
        binding.layoutTvDetailCard.animate()
            .translationY(translationY)
            .setDuration(500)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        // Hide system status bar (clocks, battery, etc.)
        hideStatusBarInline()

        // Check if the URL is a streaming page or embedded player rather than a direct
        // video stream. Domains come from ScraperConfig (hot-updated from GitHub), so
        // dead portal domains are fixed remotely. The page is visited RIGHT NOW to
        // extract the fresh temporary video URL — nothing is stored.
        if (CineScraper.shouldResolvePage(streamUrl)) {
            Toast.makeText(this@CineTvShowDetailActivity, "Extrayendo video en tiempo real...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val resolved = CineScraper.resolveBestVideoUrl(this@CineTvShowDetailActivity, streamUrl)
                if (isFinishing || isDestroyed) return@launch
                if (resolved != null) {
                    playStreamDirectly(resolved.url, startMs, displayTitle, resolved.referer, resolved.userAgent)
                } else {
                    Toast.makeText(this@CineTvShowDetailActivity, "Error: No se pudo extraer el video en tiempo real", Toast.LENGTH_SHORT).show()
                    stopInlinePlayback()
                }
            }
        } else {
            playStreamDirectly(streamUrl, startMs, displayTitle)
        }
    }

    private var activeStreamUrl: String = ""
    private var activeReferer: String? = null
    private var activeUserAgent: String? = null

    private fun playStreamDirectly(streamUrl: String, startMs: Long, displayTitle: String, referer: String? = null, userAgent: String? = null) {
        activeStreamUrl = streamUrl
        activeReferer = referer
        activeUserAgent = userAgent
        val savedPos = getSharedPreferences("iptv_pref", android.content.Context.MODE_PRIVATE)
            .getLong("pos_$displayTitle", 0L)
        pendingSeekPosition = if (startMs > 0L) startMs else savedPos

        try {
            val options = ArrayList<String>().apply {
                add("-vvv")
                add("--http-reconnect")
                add("--network-caching=800")
                add("--file-caching=800")
                add("--clock-jitter=0")
                add("--rtsp-tcp")
            }
            libVlc = org.videolan.libvlc.LibVLC(this, options)
            mediaPlayer = org.videolan.libvlc.MediaPlayer(libVlc).apply {
                attachViews(binding.inlineVlcVideoLayout, null, true, false)
                setEventListener { event ->
                    when (event.type) {
                        org.videolan.libvlc.MediaPlayer.Event.Buffering -> {
                            val buffering = event.getBuffering()
                            if (buffering < 100f) {
                                binding.inlineProgress.visibility = View.VISIBLE
                            } else {
                                binding.inlineProgress.visibility = View.GONE
                            }
                        }
                        org.videolan.libvlc.MediaPlayer.Event.Playing -> {
                            binding.inlineProgress.visibility = View.GONE
                            binding.btnInlinePlayPause.setImageResource(R.drawable.ic_ios_pause)
                            
                            if (pendingSeekPosition > 0L) {
                                mediaPlayer?.time = pendingSeekPosition
                                Toast.makeText(this@CineTvShowDetailActivity, "Reanudando desde ${formatTime(pendingSeekPosition)}", Toast.LENGTH_SHORT).show()
                                pendingSeekPosition = 0L // reset
                            }
                            
                            startInlineTimelineUpdates()
                        }
                        org.videolan.libvlc.MediaPlayer.Event.Paused -> {
                            binding.btnInlinePlayPause.setImageResource(R.drawable.ic_ios_play)
                        }
                        org.videolan.libvlc.MediaPlayer.Event.Stopped -> {
                            binding.inlineProgress.visibility = View.GONE
                        }
                        org.videolan.libvlc.MediaPlayer.Event.EndReached -> {
                            stopInlinePlayback()
                        }
                    }
                }
            }

            val m = org.videolan.libvlc.Media(libVlc, Uri.parse(streamUrl)).apply {
                setHWDecoderEnabled(true, false)
                // Streams extracted in real time often require these headers or they refuse to load
                if (!referer.isNullOrEmpty()) addOption(":http-referrer=$referer")
                if (!userAgent.isNullOrEmpty()) addOption(":http-user-agent=$userAgent")
            }
            mediaPlayer?.media = m
            m.release()
            mediaPlayer?.play()

        } catch (e: Exception) {
            e.printStackTrace()
            stopInlinePlayback()
            Toast.makeText(this, "Error al reproducir el video", Toast.LENGTH_SHORT).show()
        }

        // Setup Inline Controls
        binding.btnInlineClose.setOnClickListener {
            stopInlinePlayback()
        }

        // Apply theme-based color tinting to btnInlineClose
        val sharedPref = getSharedPreferences("iptv_pref", android.content.Context.MODE_PRIVATE)
        val selectedTheme = sharedPref.getString("theme_pref", "system") ?: "system"
        val isDark = if (selectedTheme == "system") {
            val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        } else {
            selectedTheme == "dark"
        }
        val iconColor = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        binding.btnInlineClose.setColorFilter(iconColor)

        binding.btnInlinePlayPause.setOnClickListener {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            }
        }

        binding.btnInlineFullscreen.setOnClickListener {
            maximizeToFullscreen(streamUrl, displayTitle)
        }

        binding.btnInlineServers.setOnClickListener {
            showInlineSourceSelectorDialog()
        }

        // Initialize Double-Tap Gesture Detector for 10s skip in small screen
        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                val width = binding.layoutInlineControls.width
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
                // Toggle inline controls visibility on single tap
                val isVisible = binding.layoutInlineBottomBar.visibility == View.VISIBLE
                if (isVisible) {
                    binding.layoutInlineBottomBar.visibility = View.GONE
                } else {
                    binding.layoutInlineBottomBar.visibility = View.VISIBLE
                }
                return true
            }
        })

        binding.layoutInlineControls.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        binding.inlineSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.let { player ->
                        val total = player.length
                        if (total > 0) {
                            player.time = (progress * total) / 100
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun stopInlinePlayback() {
        inlineTimelineJob?.cancel()
        inlineTimelineJob = null

        mediaPlayer?.let {
            val current = it.time
            if (current > 0L && currentInlineTitle.isNotEmpty()) {
                getSharedPreferences("iptv_pref", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putLong("pos_$currentInlineTitle", current)
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

        binding.imgTvBackdrop.visibility = View.VISIBLE
        binding.viewBackdropFade.visibility = View.VISIBLE
        binding.layoutInlinePlayer.visibility = View.GONE
        binding.layoutBackdropHeaderContainer.translationZ = 0f

        binding.btnTvPlay.text = "▶  REPRODUCIR"
        binding.btnTvPlay.setBackgroundResource(R.drawable.bg_button_primary)
        isPlayingInline = false

        // Animate detail card back up to its original overlapping position
        binding.layoutTvDetailCard.animate()
            .translationY(0f)
            .setDuration(500)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        // Restore system status bar (clocks, battery, etc.)
        showStatusBarInline()
    }

    private fun startInlineTimelineUpdates() {
        inlineTimelineJob?.cancel()
        inlineTimelineJob = lifecycleScope.launch {
            while (mediaPlayer != null) {
                val player = mediaPlayer ?: break
                if (player.isPlaying) {
                    val current = player.time
                    val total = player.length
                    if (total > 0) {
                        binding.inlineSeekBar.progress = ((current * 100) / total).toInt()
                        binding.txtInlineCurrentTime.text = formatTime(current)
                        binding.txtInlineTotalTime.text = formatTime(total)
                        
                        // Save playback progress to resume later if user exits!
                        if (currentInlineTitle.isNotEmpty()) {
                            getSharedPreferences("iptv_pref", android.content.Context.MODE_PRIVATE)
                                .edit()
                                .putLong("pos_$currentInlineTitle", current)
                                .apply()
                        }
                    } else {
                        binding.inlineSeekBar.progress = 100
                        binding.txtInlineCurrentTime.text = "LIVE"
                        binding.txtInlineTotalTime.text = "LIVE"
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun maximizeToFullscreen(streamUrl: String, title: String) {
        val currentPosition = mediaPlayer?.time ?: 0L
        
        stopInlinePlayback()

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("channelName", title)
            putExtra("channelUrl", streamUrl)
            putExtra("startPosition", currentPosition)
            putExtra("streamReferer", activeReferer)
            putExtra("streamUserAgent", activeUserAgent)
        }
        fullscreenLauncher.launch(intent)
    }

    private fun showInlineSourceSelectorDialog() {
        val sources = media.episodes.map { it.url }.toMutableList()
        if (sources.isEmpty()) return

        val friendlyNames = media.episodes.map { "S${it.season}E${it.episodeNumber}: ${it.title}" }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Cambiar de Capítulo / Servidor")
            .setItems(friendlyNames) { _, which ->
                val selectedUrl = sources[which]
                val selectedTitle = friendlyNames[which]
                
                stopInlinePlayback()
                startInlinePlayback(0L, selectedUrl, "${media.title} - $selectedTitle")
                
                Toast.makeText(this, "Cargando capítulo seleccionado...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
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

    private fun hideStatusBarInline() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    private fun showStatusBarInline() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.show(android.view.WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
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

    private fun showTrailerModal(url: String) {
        val videoId = extractYoutubeId(url) ?: return
        val embedUrl = "https://www.youtube.com/embed/$videoId?autoplay=1"

        val dialogView = layoutInflater.inflate(R.layout.dialog_trailer_player, null)
        val webView = dialogView.findViewById<android.webkit.WebView>(R.id.webViewTrailer)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnTrailerClose)
        val txtTitle = dialogView.findViewById<android.widget.TextView>(R.id.txtTrailerTitle)

        txtTitle.text = "Tráiler: ${media.title}"

        webView.settings.javaScriptEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = android.webkit.WebViewClient()
        webView.loadUrl(embedUrl)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            webView.loadUrl("about:blank")
        }

        dialog.show()
    }

    private fun extractYoutubeId(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            if (url.contains("youtu.be")) {
                uri.lastPathSegment
            } else {
                uri.getQueryParameter("v")
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun shareMovieDetails() {
        // Take screenshot of the backdrop header container (captures backdrop image or active player!)
        val bitmap = takeViewScreenshot(binding.layoutBackdropHeaderContainer) ?: return
        
        val cachePath = java.io.File(cacheDir, "shared_images")
        cachePath.mkdirs()
        val imageFile = java.io.File(cachePath, "movie_share.png")
        try {
            val stream = java.io.FileOutputStream(imageFile)
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            stream.close()
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        val imageUri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "com.samuelpart.iptvplayer.fileprovider",
            imageFile
        )

        val shareText = """
            🎬 ¡Mira esto en IPTV Player PRO!
            
            🍿 Título: ${media.title}
            
            📝 Sinopsis: ${media.overview ?: "Sin sinopsis disponible."}
            
            📲 ¡Descarga la aplicación IPTV Player PRO para ver esta película, series y canales de televisión en vivo totalmente gratis! 📺🌟
        """.trimIndent()

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            putExtra(Intent.EXTRA_TEXT, shareText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Compartir con amigos 🍿"))
    }

    private fun takeViewScreenshot(view: View): android.graphics.Bitmap? {
        return try {
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            view.draw(canvas)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onStop() {
        super.onStop()
        if (isPlayingInline) {
            stopInlinePlayback()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isPlayingInline) {
            stopInlinePlayback()
        }
    }
}

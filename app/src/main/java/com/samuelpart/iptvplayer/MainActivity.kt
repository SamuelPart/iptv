package com.samuelpart.iptvplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import kotlin.math.roundToInt
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.FrameLayout
import android.view.Gravity
import android.graphics.Typeface
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.samuelpart.iptvplayer.databinding.ActivityMainBinding
import androidx.recyclerview.widget.PagerSnapHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.ArrayList

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var coverModeCine = true
    private var coverSnapPos = -1
    private val coverItems = ArrayList<HomeCoverItem>()
    private lateinit var coverAdapter: HomeCoverAdapter
    private val coverSnap = PagerSnapHelper()
    private var cineMood: String? = null
    private lateinit var cineRecoAdapter: CineRecoAdapter
    private var deckMode = true
    private var deckBusy = false
    private var deckDownX = 0f
    private var deckFront: CineMedia? = null
    private var cineDeckWired = false
    private val deckQueue = java.util.ArrayDeque<CineMedia>()
    private val deckShown = HashSet<String>()
    
    private lateinit var channelsAdapter: ChannelAdapter
    private lateinit var searchAdapter: ChannelAdapter
    private lateinit var countryAdapter: CategoryAdapter
    private lateinit var searchCountryAdapter: CategoryAdapter
    
    private var allChannels: List<Channel> = emptyList()
    private var categories: List<String> = emptyList()
    private var countries: List<String> = emptyList()
    private var languages: List<String> = emptyList()
    
    // Filtering states
    private var selectedCategory: String = "Todos"
    private var selectedCountry: String = "Todos"
    private var selectedLanguage: String = "Todos"
    private var selectedAlphabet: String = "Sin Ordenar" // "Sin Ordenar", "A-Z", "Z-A"
    private var selectedSearchCountry: String = "Todos"
    private var isGridView = true

    // Cine & Series states
    private lateinit var cineAdapter: CineMediaAdapter
    private lateinit var favoriteAdapter: FavoriteAdapter
    private lateinit var continueWatchingAdapter: ContinueWatchingAdapter

    // QR scan: importar lista M3U escaneando codigo
    private val cameraPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            qrScanLauncher.launch(qrScanOptions())
        } else {
            Toast.makeText(this, "Permiso de cámara requerido para escanear QR", Toast.LENGTH_LONG).show()
        }
    }

    private val qrScanLauncher = registerForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        val contents = result.contents
        if (!contents.isNullOrEmpty()) {
            binding.edtUrl.setText(contents)
            loadIptvList(contents)
        }
    }

    private fun qrScanOptions(): com.journeyapps.barcodescanner.ScanOptions =
        com.journeyapps.barcodescanner.ScanOptions().apply {
            setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
            setPrompt("Apunta la camara al QR de tu lista")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
    private var allCineMedia: List<CineMedia> = emptyList()
    private var selectedCineType: String = "all" // "all", "movie", "series"

    // Google AdMob Rewarded Interstitial Ad reference
    private var mRewardedInterstitialAd: com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd? = null
    // Google AdMob Rewarded Ad reference
    private var mRewardedAd: com.google.android.gms.ads.rewarded.RewardedAd? = null

    // Public test lists (legal & free streams)
    private val urlSpain = "https://iptv-org.github.io/iptv/countries/es.m3u"
    private val urlGlobal = "https://iptv-org.github.io/iptv/index.m3u"
    private val urlNews = "https://iptv-org.github.io/iptv/categories/news.m3u"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyAppTheme()

        // Enable premium Immersive Full Screen mode (hiding the top status bar: battery, notifications, wifi, signal)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { controller ->
                    controller.hide(android.view.WindowInsets.Type.statusBars())
                    controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setupBottomNavigation()
        setupRecyclerViews()
        incrementOpenCounter()
        binding.txtHomeGreeting.text = homeGreeting()
        setupHomeCoverflow()
        setupSearchHistories()
        setupListeners()
        updateEmptyStates() // Initial state shows instructions everywhere
        
        // Initialize Google AdMob SDK and load ads
        com.google.android.gms.ads.MobileAds.initialize(this) {}
        val adRequest = com.google.android.gms.ads.AdRequest.Builder().build()
        binding.adView.loadAd(adRequest)
        loadRewardedInterstitialAd()
        loadRewardedAd()
        loadNativeAd()
        
        // Auto-restore last successfully loaded playlist on app startup!
        restoreSavedPlaylist()

        // Check if we need to show the interactive walkthrough tutorial for new users
        checkFirstTimeTutorial()

        // Load the TMDb integrated Cine & Series Catalog!
        loadCineCatalog()

        // PlayStation-style ambient glow drifting behind the UI
        UiMotion.startGlowDrift(binding.imgGlowBlue, dx = 46f, dy = 64f, baseAlpha = 0.85f, duration = 7000)
        UiMotion.startGlowDrift(binding.imgGlowViolet, dx = -56f, dy = -48f, baseAlpha = 0.7f, duration = 9000)
    }

    private fun setupBottomNavigation() {
        // iOS look: system blue when selected, iOS gray otherwise
        val navTint = androidx.core.content.ContextCompat.getColorStateList(this, R.color.nav_item_tint)
        binding.bottomNavigation.itemIconTintList = navTint
        binding.bottomNavigation.itemTextColor = navTint
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    showTab(View.VISIBLE, View.GONE, View.GONE, View.GONE, View.GONE)
                    true
                }
                R.id.navigation_channels -> {
                    showTab(View.GONE, View.VISIBLE, View.GONE, View.GONE, View.GONE)
                    true
                }
                R.id.navigation_search -> {
                    showTab(View.GONE, View.GONE, View.GONE, View.GONE, View.VISIBLE)
                    true
                }
                R.id.navigation_cine -> {
                    showTab(View.GONE, View.GONE, View.VISIBLE, View.GONE, View.GONE)
                    true
                }
                R.id.navigation_settings -> {
                    showTab(View.GONE, View.GONE, View.GONE, View.VISIBLE, View.GONE)
                    true
                }
                else -> false
            }
        }
    }

    private fun showTab(homeVis: Int, channelsVis: Int, cineVis: Int, browserVis: Int, searchVis: Int) {
        showTabAnimated(binding.containerHome, homeVis)
        showTabAnimated(binding.containerChannels, channelsVis)
        showTabAnimated(binding.containerCine, cineVis)
        showTabAnimated(binding.containerBrowser, browserVis)
        showTabAnimated(binding.containerSearch, searchVis)

        // Randomly choose between displaying Banner Ad or Native Advanced Ad for the current tab (50/50 chance).
        // If Native Ad hasn't loaded yet, default to Banner Ad so we never miss impressions!
        val showNative = (Math.random() < 0.5) && (nativeAd != null)
        
        if (showNative) {
            binding.cardGlobalNativeAd.visibility = View.VISIBLE
            binding.adView.visibility = View.GONE
        } else {
            binding.cardGlobalNativeAd.visibility = View.GONE
            binding.adView.visibility = View.VISIBLE
        }
    }


    /** PS5-style tab entry: the panel rises and springs into place. */
    private fun showTabAnimated(view: View, vis: Int) {
        view.visibility = vis
        if (vis == View.VISIBLE) {
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = 28f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                .start()
        }
    }

    private fun setupRecyclerViews() {
        // 1. Setup Channels — Sintonizador (1 col, fila con foco central) por defecto
        binding.rvChannelsGrid.layoutManager = GridLayoutManager(this, 1)
        channelsAdapter = ChannelAdapter(
            emptyList(),
            onChannelClick = { channel -> openPlayer(channel) },
            isFavorite = { FavoritesManager.isFavorite(this, it.url) },
            onFavoriteToggle = { channel ->
                val added = FavoritesManager.toggleChannel(this, channel)
                Toast.makeText(this, if (added) "Guardado en Favoritos ⭐" else "Quitado de Favoritos", Toast.LENGTH_SHORT).show()
                refreshFavorites()
            }
        )
        binding.rvChannelsGrid.adapter = channelsAdapter
        channelsAdapter.attachTuner(binding.rvChannelsGrid)
        channelsAdapter.tunerMode = true
        binding.btnToggleLayout.setImageResource(R.drawable.ic_ios_list)

        // 2. Setup Search Grid in Buscador tab (3 columns)
        binding.rvSearchGrid.layoutManager = GridLayoutManager(this, 3)
        searchAdapter = ChannelAdapter(
            emptyList(),
            onChannelClick = { channel -> openPlayer(channel) },
            isFavorite = { FavoritesManager.isFavorite(this, it.url) },
            onFavoriteToggle = { channel ->
                val added = FavoritesManager.toggleChannel(this, channel)
                Toast.makeText(this, if (added) "Guardado en Favoritos ⭐" else "Quitado de Favoritos", Toast.LENGTH_SHORT).show()
                refreshFavorites()
            }
        )
        binding.rvSearchGrid.adapter = searchAdapter

        // 3. Setup Cine Grid in Cine tab (2 columns for high-end poster aspect ratio)
        binding.rvCineGrid.layoutManager = GridLayoutManager(this, 2)
        cineAdapter = CineMediaAdapter(
            emptyList(),
            onMediaClick = { media -> openCineDetail(media) },
            isFavorite = { FavoritesManager.isFavorite(this, it.url) },
            onFavoriteToggle = { media ->
                val added = FavoritesManager.toggleMedia(this, media)
                Toast.makeText(this, if (added) "Guardado en Favoritos ⭐" else "Quitado de Favoritos", Toast.LENGTH_SHORT).show()
                refreshFavorites()
            }
        )
        binding.rvCineGrid.adapter = cineAdapter

        // Rail "Porque viste X" (Cine v2)
        cineRecoAdapter = CineRecoAdapter { media -> openCineDetail(media) }
        binding.rvCineReco.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCineReco.adapter = cineRecoAdapter

        setupCineDeck()

        // 4. Favorites horizontal strip in Home
        binding.rvFavorites.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        favoriteAdapter = FavoriteAdapter(
            emptyList(),
            onClick = { item ->
                if (item.isChannel) {
                    item.channel?.let { openPlayer(it) }
                } else {
                    item.media?.let { openCineDetail(it) }
                }
            },
            onRemove = { item ->
                val name = if (item.isChannel) item.channel?.name ?: "canal" else item.media?.title ?: "contenido"
                AlertDialog.Builder(this)
                    .setTitle("Quitar de Favoritos")
                    .setMessage("¿Quitar \"$name\" de tus Favoritos?")
                    .setPositiveButton("Quitar") { _, _ ->
                        FavoritesManager.remove(this, item.url)
                        refreshFavorites()
                        Toast.makeText(this, "Quitado de Favoritos", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        )
        binding.rvFavorites.adapter = favoriteAdapter
        refreshFavorites()

        // 5. Continue Watching horizontal strip in Home
        binding.rvContinueWatching.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        continueWatchingAdapter = ContinueWatchingAdapter(
            emptyList(),
            onClick = { entry -> resumeContinueWatching(entry) },
            onRemove = { entry -> confirmRemoveContinueWatching(entry) }
        )
        binding.rvContinueWatching.adapter = continueWatchingAdapter
        refreshContinueWatching()
    }

    /** Repaints the Home "Continue Watching" strip from the local store. */
    private fun refreshContinueWatching() {
        val items = ContinueWatchingManager.getAll(this)
        if (items.isEmpty()) {
            binding.sectionContinueWatching.visibility = View.GONE
        } else {
            binding.sectionContinueWatching.visibility = View.VISIBLE
            if (::continueWatchingAdapter.isInitialized) continueWatchingAdapter.updateList(items)
        }
    }

    private fun resumeContinueWatching(entry: ContinueWatchingManager.ResumeEntry) {
        if (entry.isChannel) {
            openPlayer(Channel(name = entry.title, url = entry.url, logoUrl = entry.channelLogo))
        } else {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("channelName", entry.title)
                putExtra("channelUrl", entry.url)
                putExtra("startPosition", entry.positionMs)
                entry.media?.let { putExtra("cineMedia", it) }
            })
        }
    }

    private fun confirmRemoveContinueWatching(entry: ContinueWatchingManager.ResumeEntry) {
        AlertDialog.Builder(this)
            .setTitle("Quitar de Continuar viendo")
            .setMessage("¿Quitar \"${entry.title}\" de la lista?")
            .setPositiveButton("Quitar") { _, _ ->
                ContinueWatchingManager.remove(this, entry.url)
                refreshContinueWatching()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshContinueWatching()
        refreshFavorites()
        applyAccentColor()
        refreshStats()
    }

    /** Repaints the Home favorites strip from the local store. */
    /** Re-tints nav + cine segment + settings preview with the chosen accent. */
    private fun applyAccentColor() {
        val navTint = AccentManager.navTintList(this)
        binding.bottomNavigation.itemIconTintList = navTint
        binding.bottomNavigation.itemTextColor = navTint
        binding.txtSettingsAccentDesc.text = AccentManager.getLabel(this)
        AccentManager.applyPreviewTint(binding.viewAccentPreview, this)
        updateCineFilterButtons()
    }

    private fun incrementOpenCounter() {
        val prefs = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        prefs.edit().putInt("app_open_count", prefs.getInt("app_open_count", 0) + 1).apply()
    }

    /** Local weekly stats rendered in the Home stats card. */
    private fun refreshStats() {
        val opens = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE).getInt("app_open_count", 0)
        val weekMs = 7L * 24 * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        val week = ContinueWatchingManager.getAll(this).filter { now - it.savedAt < weekMs }
        val channels = week.count { it.isChannel }
        val cine = week.filter { !it.isChannel }
        val watchMin = cine.sumOf { it.positionMs / 60000 }
        val favs = FavoritesManager.count(this)
        binding.txtStatsBody.text =
            "Esta semana: 📺 $channels canales · 🎬 ${cine.size} películas/series" +
            "\n⏱ Tiempo en cine: $watchMin min    ⭐ Favoritos: $favs    📲 Aperturas: $opens"
    }


    private fun refreshFavorites() {
        val favs = FavoritesManager.getAll(this)
        if (favs.isEmpty()) {
            binding.sectionFavorites.visibility = View.GONE
        } else {
            binding.sectionFavorites.visibility = View.VISIBLE
            binding.txtFavoritesCount.text = "${favs.size} guardados"
            if (::favoriteAdapter.isInitialized) favoriteAdapter.updateList(favs)
        }
    }

    private fun homeGreeting(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            h < 6 -> "Buenas noches"
            h < 12 -> "Buenos dias"
            h < 19 -> "Buenas tardes"
            else -> "Buenas noches"
        }
    }

    // ================= HOME V4 - COVERFLOW =================

    private fun setupHomeCoverflow() {
        coverAdapter = HomeCoverAdapter { onCoverTap(it) }
        binding.rvCoverflow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCoverflow.adapter = coverAdapter
        coverSnap.attachToRecyclerView(binding.rvCoverflow)
        binding.rvCoverflow.addOnScrollListener(coverScrollListener)
        binding.llChipChannels.setOnClickListener { setCoverMode(false); it.springPress() }
        binding.llChipCine.setOnClickListener { setCoverMode(true); it.springPress() }
        setCoverMode(true)
    }

    private val coverScrollListener = object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
            applyCoverTransforms(recyclerView)
        }

        override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
            if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) syncCoverInfo(recyclerView)
            applyCoverTransforms(recyclerView)
        }
    }

    private fun setCoverMode(cine: Boolean) {
        coverModeCine = cine
        binding.txtChipCine.setTextColor(if (cine) 0xFFFFFFFF.toInt() else 0xFF7A7A7C.toInt())
        binding.txtChipChannels.setTextColor(if (!cine) 0xFFFFFFFF.toInt() else 0xFF7A7A7C.toInt())
        binding.viewChipCineU.alpha = if (cine) 1f else 0f
        binding.viewChipChannelsU.alpha = if (!cine) 1f else 0f
        refreshCoverData()
    }

    private fun refreshCoverData() {
        if (!::coverAdapter.isInitialized) return
        coverItems.clear()
        if (coverModeCine) {
            allCineMedia.filter { !it.posterUrl.isNullOrBlank() }.take(30).forEach {
                coverItems.add(HomeCoverItem(it.posterUrl, it.title, coverCineMeta(it), null, null, it))
            }
            if (coverItems.isEmpty()) {
                coverItems.add(HomeCoverItem(null, "Cine y series premium", "Explora el catalogo completo en la pestana Cine", null, null, null))
            }
        } else {
            allChannels.take(20).forEach {
                coverItems.add(HomeCoverItem(it.logoUrl, it.name, "Transmision en vivo · toca para ver", "EN VIVO", it, null))
            }
            if (coverItems.isEmpty()) {
                coverItems.add(HomeCoverItem(null, "Conecta tu lista", "Ve a Ajustes, pega tu lista M3U o escanea un QR", null, null, null))
            }
        }
        coverAdapter.submitAll(coverItems)
        binding.rvCoverflow.post {
            applyCoverTransforms(binding.rvCoverflow)
            syncCoverInfo(binding.rvCoverflow)
        }
    }

    private fun coverCineMeta(c: CineMedia): String {
        val r = c.rating?.let { "★ %.1f".format(it) } ?: "Nuevo esta semana"
        val srv = "${c.urls.size} servidores"
        val eps = if (c.episodes.size > 1) " · ${c.episodes.size} episodios" else ""
        return "$r · $srv$eps"
    }

    private fun onCoverTap(pos: Int) {
        val item = coverItems.getOrNull(pos) ?: return
        if (pos != coverSnapPos && coverItems.size > 1) {
            binding.rvCoverflow.smoothScrollToPosition(pos)
            return
        }
        item.channel?.let { openPlayer(it); return }
        item.media?.let { openCineDetail(it); return }
        binding.bottomNavigation.selectedItemId =
            if (coverModeCine) R.id.navigation_cine else R.id.navigation_settings
    }

    private fun applyCoverTransforms(rv: androidx.recyclerview.widget.RecyclerView) {
        val cx = rv.width / 2f
        val radius = rv.width * 0.62f
        for (i in 0 until rv.childCount) {
            val v = rv.getChildAt(i)
            val vcx = (v.left + v.right) / 2f
            val t = (1f - kotlin.math.abs(vcx - cx) / radius).coerceIn(0f, 1f)
            val e = 1f - (1f - t) * (1f - t)
            val sc = 0.80f + 0.20f * e
            v.scaleX = sc
            v.scaleY = sc
            v.alpha = 0.45f + 0.55f * e
            v.translationZ = 12f * e
        }
    }

    private fun syncCoverInfo(rv: androidx.recyclerview.widget.RecyclerView) {
        val lv = coverSnap.findSnapView(rv.layoutManager) ?: return
        val pos = rv.getChildAdapterPosition(lv)
        if (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
        coverSnapPos = pos
        val item = coverItems.getOrNull(pos) ?: return
        binding.txtCoverTitle.text = item.title
        binding.txtCoverMeta.text = item.meta
    }

    private fun setupListeners() {
        // Load button click
        binding.btnScanQr.setOnClickListener {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
        binding.btnScanQr.springPress()

        binding.btnLoad.setOnClickListener {
            val url = binding.edtUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                loadIptvList(url)
            } else {
                Toast.makeText(this, "Por favor, ingresa una URL válida", Toast.LENGTH_SHORT).show()
            }
        }

        // Clear button clears text and deletes the saved URL from cache
        binding.btnClear.setOnClickListener {
            binding.edtUrl.setText("")
            val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
            sharedPref.edit().remove("last_url").apply()
            allChannels = emptyList()
            categories = emptyList()
            countries = emptyList()
            languages = emptyList()
            updateEmptyStates()
            Toast.makeText(this, "Lista eliminada del historial", Toast.LENGTH_SHORT).show()
        }

        // Quick load Spain
        binding.btnQuickSpain.setOnClickListener {
            binding.edtUrl.setText(urlSpain)
            loadIptvList(urlSpain)
        }

        // Quick load Global
        binding.btnQuickGlobal.setOnClickListener {
            binding.edtUrl.setText(urlGlobal)
            loadIptvList(urlGlobal)
        }

        // Quick load News
        binding.btnQuickNews.setOnClickListener {
            binding.edtUrl.setText(urlNews)
            loadIptvList(urlNews)
        }

        // Search Tab Input Listener
        binding.edtSearchTab.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterSearchTabUnified()
                if (s.isNullOrEmpty()) {
                    val current = getSearchHistory(CHANNELS_HISTORY_KEY)
                    if (current.isNotEmpty()) {
                        channelsHistoryAdapter.updateList(current)
                        binding.layoutChannelsSearchHistory.visibility = View.VISIBLE
                    }
                } else {
                    binding.layoutChannelsSearchHistory.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.edtSearchTab.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.edtSearchTab.text.isEmpty()) {
                val current = getSearchHistory(CHANNELS_HISTORY_KEY)
                if (current.isNotEmpty()) {
                    channelsHistoryAdapter.updateList(current)
                    binding.layoutChannelsSearchHistory.visibility = View.VISIBLE
                }
            } else {
                binding.layoutChannelsSearchHistory.visibility = View.GONE
            }
        }

        binding.edtSearchTab.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = binding.edtSearchTab.text.toString().trim()
                if (query.isNotEmpty()) {
                    addSearchQuery(CHANNELS_HISTORY_KEY, query)
                }
                binding.layoutChannelsSearchHistory.visibility = View.GONE
                hideKeyboard()
                true
            } else {
                false
            }
        }

        // Settings rows: iOS spring touch
        binding.btnSettingsParental.springPress()
        binding.btnSettingsTheme.springPress()
        binding.btnSettingsClearCache.springPress()

        // Settings: Parental Control click listener
        binding.btnSettingsParental.setOnClickListener {
            showParentalSettingsDialog()
        }

        // Settings: App Theme (Claro / Oscuro / Sistema) Selector click listener
        binding.btnSettingsAccent.setOnClickListener {
            showIosOptionPopup(
                "Color de acento",
                "Elige el color principal de la app",
                AccentManager.OPTIONS.map { R.drawable.ic_ios_globe to it.label }
            ) { which ->
                val opt = AccentManager.OPTIONS[which]
                AccentManager.set(this, opt.key)
                applyAccentColor()
                Toast.makeText(this, "🎨 Acento: ${opt.label}", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnSettingsAccent.springPress()

        binding.btnSettingsTheme.setOnClickListener {
            val options = arrayOf("Por defecto del sistema ⚙️", "Modo Oscuro 🌙", "Modo Claro ☀️")
            val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
            val currentTheme = sharedPref.getString("theme_pref", "system") ?: "system"
            val checkedItem = when (currentTheme) {
                "system" -> 0
                "dark" -> 1
                "light" -> 2
                else -> 0
            }

            AlertDialog.Builder(this)
                .setTitle("Seleccionar Tema de la App")
                .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                    val selectedTheme = when (which) {
                        0 -> "system"
                        1 -> "dark"
                        2 -> "light"
                        else -> "system"
                    }
                    sharedPref.edit().putString("theme_pref", selectedTheme).apply()
                    
                    val isDark = if (selectedTheme == "system") {
                        val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                        currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    } else {
                        selectedTheme == "dark"
                    }
                    
                    updateAppThemeColors(isDark)
                    dialog.dismiss()
                    Toast.makeText(this, "Tema aplicado al instante ⚡", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        // Settings: Clear Playlist Cache click listener
        binding.btnSettingsClearCache.setOnClickListener {
            binding.edtUrl.setText("")
            val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
            sharedPref.edit().remove("last_url").apply()
            allChannels = emptyList()
            categories = emptyList()
            countries = emptyList()
            languages = emptyList()
            updateEmptyStates()
            Toast.makeText(this, "Lista eliminada del historial", Toast.LENGTH_SHORT).show()
        }

        // Filter button in Channels tab (Opens the requested multi-option menu!)
        binding.btnFilterChannels.setOnClickListener {
            showFilterDialog()
        }

        // Layout Toggle (Grid/List) in Channels tab (Opens dialog options!)
        binding.btnToggleLayout.setOnClickListener {
            showLayoutToggleDialog()
        }

        // Filter button in Search tab
        binding.btnSearchFilter.setOnClickListener {
            showSearchFilterDialog()
        }

        // Toggle Channels Search History Click Listener
        binding.btnSearchHistoryTab.setOnClickListener {
            val isVisible = binding.layoutChannelsSearchHistory.visibility == View.VISIBLE
            if (isVisible) {
                binding.layoutChannelsSearchHistory.visibility = View.GONE
            } else {
                val current = getSearchHistory(CHANNELS_HISTORY_KEY)
                if (current.isNotEmpty()) {
                    channelsHistoryAdapter.updateList(current)
                    binding.layoutChannelsSearchHistory.visibility = View.VISIBLE
                } else {
                    Toast.makeText(this, "El historial de búsqueda está vacío", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Open Settings Dialog click listener
        binding.btnOpenSettings.setOnClickListener {
            showParentalSettingsDialog()
        }

        // Toggle Cine Search History Click Listener
        binding.btnCineSearchHistory.setOnClickListener {
            val isVisible = binding.layoutCineSearchHistory.visibility == View.VISIBLE
            if (isVisible) {
                binding.layoutCineSearchHistory.visibility = View.GONE
            } else {
                val current = getSearchHistory(CINE_HISTORY_KEY)
                if (current.isNotEmpty()) {
                    cineHistoryAdapter.updateList(current)
                    binding.layoutCineSearchHistory.visibility = View.VISIBLE
                } else {
                    Toast.makeText(this, "El historial de búsqueda está vacío", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Real-time search in Cine tab
        binding.edtCineSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrBlank()) setDeckMode(false)
                applyCineFilters()
                if (s.isNullOrEmpty()) {
                    val current = getSearchHistory(CINE_HISTORY_KEY)
                    if (current.isNotEmpty()) {
                        cineHistoryAdapter.updateList(current)
                        binding.layoutCineSearchHistory.visibility = View.VISIBLE
                    }
                } else {
                    binding.layoutCineSearchHistory.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.edtCineSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.edtCineSearch.text.isEmpty()) {
                val current = getSearchHistory(CINE_HISTORY_KEY)
                if (current.isNotEmpty()) {
                    cineHistoryAdapter.updateList(current)
                    binding.layoutCineSearchHistory.visibility = View.VISIBLE
                }
            } else {
                binding.layoutCineSearchHistory.visibility = View.GONE
            }
        }

        binding.edtCineSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = binding.edtCineSearch.text.toString().trim()
                if (query.isNotEmpty()) {
                    addSearchQuery(CINE_HISTORY_KEY, query)
                }
                binding.layoutCineSearchHistory.visibility = View.GONE
                hideKeyboard()
                true
            } else {
                false
            }
        }

        // Cine Filter buttons
        binding.btnCineFilterAll.setOnClickListener {
            selectedCineType = "all"
            updateCineFilterButtons()
            applyCineFilters()
        }
        binding.btnCineFilterMovies.setOnClickListener {
            selectedCineType = "movie"
            updateCineFilterButtons()
            applyCineFilters()
        }
        binding.btnCineFilterSeries.setOnClickListener {
            selectedCineType = "series"
            updateCineFilterButtons()
            applyCineFilters()
        }
        binding.btnCineFilterNews.setOnClickListener {
            selectedCineType = "new"
            updateCineFilterButtons()
            applyCineFilters()
        }

        // Lupa: muestra/oculta la pildora de busqueda del cine
        binding.btnCineSearchToggle.setOnClickListener {
            val card = binding.cardCineSearch
            if (card.visibility == View.VISIBLE) {
                card.visibility = View.GONE
            } else {
                card.visibility = View.VISIBLE
                binding.edtCineSearch.requestFocus()
            }
        }
        binding.btnCineSearchToggle.springPress()

        // Mood chips (mismo tap para desactivar)
        binding.btnMoodEstrenos.setOnClickListener {
            cineMood = if (cineMood == "estrenos") null else "estrenos"
            updateCineMoodButtons()
            applyCineFilters()
        }
        binding.btnMoodTop.setOnClickListener {
            cineMood = if (cineMood == "top") null else "top"
            updateCineMoodButtons()
            applyCineFilters()
        }

        // ====== Chips del boceto "Para ti" (orden EXACTO) ======
        binding.chipMoodTodos.setOnClickListener {
            selectedCineType = "all"; cineMood = null
            updateCineFilterButtons(); updateParaTiChips(); applyCineFilters()
        }
        binding.chipMoodAccion.setOnClickListener {
            selectedCineType = "all"; cineMood = "accion"
            updateCineFilterButtons(); updateParaTiChips(); applyCineFilters()
        }
        binding.chipMoodFeel.setOnClickListener {
            selectedCineType = "all"; cineMood = "feelgood"
            updateCineFilterButtons(); updateParaTiChips(); applyCineFilters()
        }
        binding.chipMoodSerie.setOnClickListener {
            selectedCineType = "series"; cineMood = null
            updateCineFilterButtons(); updateParaTiChips(); applyCineFilters()
        }
        binding.chipMoodCorta.setOnClickListener {
            selectedCineType = "movie"; cineMood = null
            updateCineFilterButtons(); updateParaTiChips(); applyCineFilters()
        }
        binding.chipMoodTop2.setOnClickListener {
            cineMood = if (cineMood == "top") null else "top"
            updateParaTiChips(); applyCineFilters()
        }
        binding.chipMoodEstrenos2.setOnClickListener {
            cineMood = if (cineMood == "estrenos") null else "estrenos"
            updateParaTiChips(); applyCineFilters()
        }
        updateParaTiChips()
    }

    /** Modern iPhone-style view picker with animated popup + spring cards. */
    private fun showLayoutToggleDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_view_options, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.let { w ->
            val attrs = w.attributes
            attrs.windowAnimations = R.style.iOSPopupAnimation
            w.attributes = attrs
        }

        val optionGrid = dialogView.findViewById<View>(R.id.optionViewGrid)
        val optionList = dialogView.findViewById<View>(R.id.optionViewList)
        optionGrid.springPress()
        optionList.springPress()

        optionGrid.setOnClickListener {
            isGridView = true
            val layoutManager = binding.rvChannelsGrid.layoutManager as GridLayoutManager
            layoutManager.spanCount = 3
            binding.btnToggleLayout.setImageResource(R.drawable.ic_ios_grid)
            channelsAdapter.tunerMode = false
            layoutManager.requestLayout()
            Toast.makeText(this, "Vista Cuadrícula activa", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        optionList.setOnClickListener {
            isGridView = false
            val layoutManager = binding.rvChannelsGrid.layoutManager as GridLayoutManager
            layoutManager.spanCount = 1
            binding.btnToggleLayout.setImageResource(R.drawable.ic_ios_list)
            channelsAdapter.tunerMode = true
            layoutManager.requestLayout()
            Toast.makeText(this, "Vista Lista activa", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.btnViewOptionsClose).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showParentalSettingsDialog() {
        val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        val isParentalActive = sharedPref.getBoolean("parental_active", false)
        val savedPin = sharedPref.getString("parental_pin", null)

        val statusText = if (isParentalActive) "ACTIVO 🔒" else "DESACTIVADO 🔓"
        val pinStatus = if (savedPin.isNullOrEmpty()) "Sin configurar" else "Configurado"

        val options = arrayOf(
            "Control Parental: $statusText (Toca para cambiar)",
            "Configurar/Cambiar PIN (Estado: $pinStatus) 🔑",
            "Limpiar caché de lista IPTV 🧹",
            "Ocultar categorías completas 📁"
        )

        AlertDialog.Builder(this)
            .setTitle("⚙️ Ajustes de la App")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        toggleParentalControlState()
                    }
                    1 -> {
                        configureOrChangePin()
                    }
                    2 -> {
                        binding.btnClear.performClick()
                    }
                    3 -> {
                        showHideCategoriesDialog()
                    }
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun toggleParentalControlState() {
        val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        val isParentalActive = sharedPref.getBoolean("parental_active", false)
        val savedPin = sharedPref.getString("parental_pin", null)

        if (savedPin.isNullOrEmpty()) {
            Toast.makeText(this, "Por favor, primero configura un PIN de seguridad.", Toast.LENGTH_LONG).show()
            configureOrChangePin()
            return
        }

        if (isParentalActive) {
            // Turning it OFF requires PIN Verification!
            val pinInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                maxLines = 1
                hint = "Escribe tu PIN de 4 dígitos"
                filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            }

            AlertDialog.Builder(this)
                .setTitle("🔒 Desactivar Control Parental")
                .setMessage("Por favor, ingresa tu PIN de seguridad de 4 dígitos para desactivar el filtro de adultos:")
                .setView(pinInput)
                .setPositiveButton("Verificar") { _, _ ->
                    val enteredPin = pinInput.text.toString().trim()
                    if (enteredPin == savedPin) {
                        sharedPref.edit().putBoolean("parental_active", false).apply()
                        applyFiltersAndSorting()
                        filterSearchTabUnified()
                        Toast.makeText(this, "Control Parental Desactivado con éxito.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "PIN Incorrecto. El control parental sigue activo.", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .setCancelable(false)
                .show()
        } else {
            // Turning it ON is immediate
            sharedPref.edit().putBoolean("parental_active", true).apply()
            applyFiltersAndSorting()
            filterSearchTabUnified()
            Toast.makeText(this, "Control Parental Activado. Canales de adultos bloqueados.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun configureOrChangePin() {
        val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        val savedPin = sharedPref.getString("parental_pin", null)

        if (savedPin.isNullOrEmpty()) {
            // Setup PIN for the first time
            val pinInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                maxLines = 1
                hint = "Nuevo PIN de 4 dígitos"
                filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            }

            AlertDialog.Builder(this)
                .setTitle("🔑 Configurar PIN de Seguridad")
                .setMessage("Ingresa un PIN único de 4 dígitos para activar/desactivar el control parental:")
                .setView(pinInput)
                .setPositiveButton("Guardar") { _, _ ->
                    val enteredPin = pinInput.text.toString().trim()
                    if (enteredPin.length == 4) {
                        sharedPref.edit().putString("parental_pin", enteredPin).apply()
                        Toast.makeText(this, "¡PIN de seguridad configurado con éxito! 🔒", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Error: El PIN debe tener exactamente 4 dígitos.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            // Change existing PIN (strictly requires old PIN to be verified first!)
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)
            }
            
            val currentPinInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                maxLines = 1
                hint = "PIN Actual"
                filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            }

            val newPinInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                maxLines = 1
                hint = "Nuevo PIN"
                filters = arrayOf(android.text.InputFilter.LengthFilter(4))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 20
                }
            }

            container.addView(currentPinInput)
            container.addView(newPinInput)

            AlertDialog.Builder(this)
                .setTitle("🔑 Cambiar PIN de Seguridad")
                .setMessage("Para cambiar el PIN, debes ingresar tu clave actual:")
                .setView(container)
                .setPositiveButton("Actualizar") { _, _ ->
                    val currentPinEntered = currentPinInput.text.toString().trim()
                    val newPinEntered = newPinInput.text.toString().trim()
                    
                    if (currentPinEntered == savedPin) {
                        if (newPinEntered.length == 4) {
                            sharedPref.edit().putString("parental_pin", newPinEntered).apply()
                            Toast.makeText(this, "¡PIN actualizado con éxito! 🔒", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Error: El nuevo PIN debe tener exactamente 4 dígitos.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this, "Error: El PIN actual es incorrecto.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    /** Modern iPhone-style animated option popup: glass rows with icons + chevrons. */
    private fun showIosOptionPopup(title: String, subtitle: String, options: List<Pair<Int, String>>, onPick: (Int) -> Unit) {
        val density = resources.displayMetrics.density
        fun Int.dp(): Int = (this * density).roundToInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dialog_ios)
            setPadding(22.dp(), 22.dp(), 22.dp(), 16.dp())
            minimumWidth = 300.dp()
            clipToOutline = true
        }

        root.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#98989F"))
            textSize = 13f
            setPadding(0, 6.dp(), 0, 16.dp())
        })

        var dialog: AlertDialog? = null
        options.forEachIndexed { index, (iconRes, label) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_tile_glass)
                setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 10.dp() }
                springPress()
            }
            val chip = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.bg_circle_glass)
                layoutParams = LinearLayout.LayoutParams(40.dp(), 40.dp())
            }
            chip.addView(ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                layoutParams = FrameLayout.LayoutParams(22.dp(), 22.dp(), Gravity.CENTER)
            })
            row.addView(chip)
            row.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 13.dp()
                }
            })
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_ios_arrow_right)
                setColorFilter(Color.parseColor("#98989F"))
                layoutParams = LinearLayout.LayoutParams(18.dp(), 18.dp())
            })
            row.setOnClickListener {
                dialog?.dismiss()
                onPick(index)
            }
            root.addView(row)
        }

        dialog = AlertDialog.Builder(this)
            .setView(root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.let { w ->
            val attrs = w.attributes
            attrs.windowAnimations = R.style.iOSPopupAnimation
            w.attributes = attrs
        }
    }

    private fun showFilterDialog() {
        showIosOptionPopup(
            "Filtrar y ordenar",
            "Toca una opcion para aplicarla",
            listOf(
                R.drawable.ic_ios_globe to "Filtrar por pais",
                R.drawable.ic_ios_message to "Filtrar por idioma",
                R.drawable.ic_ios_tv to "Filtrar por categoria",
                R.drawable.ic_ios_sort to "Ordenar alfabeticamente",
                R.drawable.ic_ios_refresh to "Restablecer filtros"
            )
        ) { which ->
            when (which) {
                0 -> showCountryFilterSelector()
                1 -> showLanguageFilterSelector()
                2 -> showCategoryFilterSelector()
                3 -> showAlphabetSortSelector()
                4 -> resetAllFilters()
            }
        }
    }

    private fun showCountryFilterSelector() {
        if (countries.isEmpty()) {
            Toast.makeText(this, "No hay países cargados.", Toast.LENGTH_SHORT).show()
            return
        }

        val listWithoutTodos = countries.filter { it != "Todos" }
        
        var dialog: AlertDialog? = null
        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = CountryDialogAdapter(listWithoutTodos) { selected ->
                selectedCountry = selected
                dialog?.dismiss()
                applyFiltersAndSorting()
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Seleccionar País 🗺️")
            .setView(recyclerView)
            .setNeutralButton("Mostrar Todos 🔄") { _, _ ->
                selectedCountry = "Todos"
                applyFiltersAndSorting()
            }
            .setNegativeButton("Cerrar", null)
            .create()

        dialog.show()
    }

    private fun showLanguageFilterSelector() {
        if (languages.isEmpty()) {
            Toast.makeText(this, "No hay idiomas detectados.", Toast.LENGTH_SHORT).show()
            return
        }

        val items = languages.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Seleccionar Idioma")
            .setItems(items) { _, which ->
                selectedLanguage = items[which]
                applyFiltersAndSorting()
            }
            .show()
    }

    private fun showCategoryFilterSelector() {
        if (categories.isEmpty()) {
            Toast.makeText(this, "No hay categorías cargadas.", Toast.LENGTH_SHORT).show()
            return
        }

        val items = categories.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Seleccionar Categoría")
            .setItems(items) { _, which ->
                selectedCategory = items[which]
                applyFiltersAndSorting()
            }
            .show()
    }

    private fun showAlphabetSortSelector() {
        val options = arrayOf("Sin Ordenar 🔄", "A-Z (Ascendente) 🔼", "Z-A (Descendente) 🔽")
        AlertDialog.Builder(this)
            .setTitle("Ordenar por Alfabeto")
            .setItems(options) { _, which ->
                selectedAlphabet = when (which) {
                    1 -> "A-Z"
                    2 -> "Z-A"
                    else -> "Sin Ordenar"
                }
                applyFiltersAndSorting()
            }
            .show()
    }

    private fun resetAllFilters() {
        selectedCategory = "Todos"
        selectedCountry = "Todos"
        selectedLanguage = "Todos"
        selectedAlphabet = "Sin Ordenar"
        applyFiltersAndSorting()
        Toast.makeText(this, "Filtros restablecidos", Toast.LENGTH_SHORT).show()
    }

    private fun applyFiltersAndSorting() {
        refreshCoverData()
        val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        val isParentalActive = sharedPref.getBoolean("parental_active", false)

        // 1. Filter dynamically by category AND country AND language concurrently!
        var filtered = allChannels.filter {
            (selectedCategory == "Todos" || it.group == selectedCategory) &&
            (selectedCountry == "Todos" || it.country == selectedCountry) &&
            (selectedLanguage == "Todos" || it.language == selectedLanguage)
        }

        // 2. Filter out adult channels if parental control is active!
        if (isParentalActive) {
            val hiddenCategories = sharedPref.getStringSet("parental_hidden_categories", emptySet()) ?: emptySet()
            filtered = filtered.filter { !isAdultChannel(it) && (it.group == null || it.group !in hiddenCategories) }
        }

        // 3. Sort dynamically alphabetically
        filtered = when (selectedAlphabet) {
            "A-Z" -> filtered.sortedBy { it.name }
            "Z-A" -> filtered.sortedByDescending { it.name }
            else -> filtered
        }

        // 4. Update adapter list
        channelsAdapter.updateList(filtered)

        // 5. Formulate clean filter status text in header
        val countText = "Canales: ${filtered.size}"
        val categoryText = if (selectedCategory == "Todos") "" else " | Cat: $selectedCategory"
        val countryText = if (selectedCountry == "Todos") "" else " | País: $selectedCountry"
        val languageText = if (selectedLanguage == "Todos") "" else " | Idioma: $selectedLanguage"
        val sortText = if (selectedAlphabet == "Sin Ordenar") "" else " | Orden: $selectedAlphabet"
        val parentalText = if (isParentalActive) " | 🔒 Parental Activo" else ""
        
        binding.txtSelectedFilterInfo.text = "$countText$categoryText$countryText$languageText$sortText$parentalText"
    }

    /** Parental control: select whole channel categories to hide when active. */
    private fun showHideCategoriesDialog() {
        val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        if (categories.isEmpty()) {
            Toast.makeText(this, "Primero carga tu lista de canales", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = sharedPref.getStringSet("parental_hidden_categories", emptySet())?.toMutableSet() ?: mutableSetOf()
        val names = categories.toTypedArray()
        val checked = BooleanArray(names.size) { selected.contains(names[it]) }
        AlertDialog.Builder(this)
            .setTitle("📁 Categorías ocultas (Parental)")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked) selected.add(names[which]) else selected.remove(names[which])
            }
            .setPositiveButton("Guardar") { _, _ ->
                sharedPref.edit().putStringSet("parental_hidden_categories", selected).apply()
                applyFiltersAndSorting()
                Toast.makeText(this, "Categorías ocultas actualizadas 🔒", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showSearchFilterDialog() {
        showIosOptionPopup(
            "Opciones de busqueda",
            "Filtra los resultados por pais",
            listOf(
                R.drawable.ic_ios_globe to "Filtrar por pais",
                R.drawable.ic_ios_refresh to "Restablecer filtro de pais"
            )
        ) { which ->
            when (which) {
                0 -> showSearchCountryFilterSelector()
                1 -> {
                    selectedSearchCountry = "Todos"
                    filterSearchTabUnified()
                    Toast.makeText(this, "Filtro de búsqueda restablecido", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSearchCountryFilterSelector() {
        if (countries.isEmpty()) {
            Toast.makeText(this, "No hay países cargados.", Toast.LENGTH_SHORT).show()
            return
        }

        val listWithoutTodos = countries.filter { it != "Todos" }
        
        var dialog: AlertDialog? = null
        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = CountryDialogAdapter(listWithoutTodos) { selected ->
                selectedSearchCountry = selected
                dialog?.dismiss()
                filterSearchTabUnified()
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Filtrar Búsqueda por País 🗺️")
            .setView(recyclerView)
            .setNeutralButton("Mostrar Todos 🔄") { _, _ ->
                selectedSearchCountry = "Todos"
                filterSearchTabUnified()
            }
            .setNegativeButton("Cerrar", null)
            .create()

        dialog.show()
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
            val host = uri.host ?: return ""
            host.replace("www.", "")
        } catch (e: Exception) {
            ""
        }
    }

    private fun restoreSavedPlaylist() {
        val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        val lastUrl = sharedPref.getString("last_url", null)
        if (!lastUrl.isNullOrEmpty()) {
            binding.edtUrl.setText(lastUrl)
            
            // Premium Instant Loading Cache Optimization!
            val cacheFile = java.io.File(filesDir, "cached_playlist.m3u")
            if (cacheFile.exists()) {
                lifecycleScope.launch {
                    binding.globalProgressBar.visibility = View.VISIBLE
                    val cachedChannels = withContext(Dispatchers.IO) {
                        try {
                            cacheFile.inputStream().use { M3UParser.parse(it) }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    binding.globalProgressBar.visibility = View.GONE
                    if (!cachedChannels.isNullOrEmpty()) {
                        allChannels = cachedChannels
                        
                        // Parse categories, countries and languages instantly
                        val parsedCategories = mutableListOf("Todos")
                        val extractedGroups = allChannels.mapNotNull { it.group }
                            .filter { it.isNotEmpty() }
                            .distinct()
                            .sorted()
                        parsedCategories.addAll(extractedGroups)
                        categories = parsedCategories

                        val parsedCountries = mutableListOf("Todos")
                        val extractedCountries = allChannels.mapNotNull { it.country }
                            .filter { it.isNotEmpty() }
                            .distinct()
                            .sorted()
                        parsedCountries.addAll(extractedCountries)
                        countries = parsedCountries

                        val parsedLanguages = mutableListOf("Todos")
                        val extractedLanguages = allChannels.mapNotNull { it.language }
                            .filter { it.isNotEmpty() }
                            .distinct()
                            .sorted()
                        parsedLanguages.addAll(extractedLanguages)
                        languages = parsedLanguages

                        selectedCategory = "Todos"
                        selectedCountry = "Todos"
                        selectedLanguage = "Todos"
                        selectedSearchCountry = "Todos"
                        selectedAlphabet = "Sin Ordenar"
                        
                        channelsAdapter.updateList(allChannels)
                        searchAdapter.updateList(allChannels)
                        
                        binding.txtSelectedFilterInfo.text = "Canales: ${allChannels.size} (Cargados al Instante ⚡)"
                        binding.edtSearchTab.setText("")
                        binding.txtSearchCount.text = "Ingresa el nombre de un canal para buscar entre los ${allChannels.size} cargados"
                        
                        updateEmptyStates()
                    } else {
                        loadIptvList(lastUrl, isAutoRestore = true)
                    }
                }
            } else {
                loadIptvList(lastUrl, isAutoRestore = true)
            }
        }
    }

    private fun checkFirstTimeTutorial() {
        val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        val isFirstTime = sharedPref.getBoolean("is_first_time", true)
        if (isFirstTime) {
            // Slight delay to allow UI to be completely drawn
            binding.root.postDelayed({
                showTutorialStep(1)
            }, 1000)
        }
    }

    private fun showTutorialStep(step: Int) {
        val builder = AlertDialog.Builder(this)
        
        when (step) {
            1 -> {
                builder.setTitle("🎉 ¡Bienvenido a IPTV Player PRO!")
                    .setMessage("Te damos la bienvenida a la mejor aplicación para disfrutar de televisión, películas y series gratis.\n\nHemos preparado un recorrido rápido de 5 pasos para explicarte cómo sacarle el máximo provecho. ¿Comenzamos?")
                    .setPositiveButton("Iniciar Recorrido") { _, _ ->
                        showTutorialStep(2)
                    }
                    .setNegativeButton("Omitir") { _, _ ->
                        completeTutorial()
                    }
                    .setCancelable(false)
                    .show()
            }
            2 -> {
                // Switch to HOME tab programmatically
                binding.bottomNavigation.selectedItemId = R.id.navigation_home
                builder.setTitle("🏠 Paso 1: Pestaña Inicio")
                    .setMessage("Aquí es donde administras tus listas.\n\nPuedes ingresar cualquier enlace IPTV (M3U) de tu preferencia o tocar cualquiera de nuestros tres botones rápidos (España 🇪🇸, Global 🌐, Noticias 📰) para cargar cientos de canales gratis al instante.")
                    .setPositiveButton("Siguiente") { _, _ ->
                        showTutorialStep(3)
                    }
                    .setCancelable(false)
                    .show()
            }
            3 -> {
                // Switch to CHANNELS tab programmatically
                binding.bottomNavigation.selectedItemId = R.id.navigation_channels
                builder.setTitle("📺 Paso 2: Pestaña Canales")
                    .setMessage("Una vez cargues tu lista, aquí aparecerán tus canales.\n\nHemos colocado un hermoso panel de opciones arriba. Toca el botón de Filtro para ordenar por País, Categoría o Alfabéticamente. Toca el botón de Cuadrícula para cambiar entre diseño Grid o Lista al instante.")
                    .setPositiveButton("Siguiente") { _, _ ->
                        showTutorialStep(4)
                    }
                    .setCancelable(false)
                    .show()
            }
            4 -> {
                // Switch to SEARCH tab programmatically
                binding.bottomNavigation.selectedItemId = R.id.navigation_search
                builder.setTitle("🔍 Paso 3: Buscador de Canales")
                    .setMessage("Encuentra rápidamente cualquiera de tus canales IPTV cargados escribiendo su nombre aquí.\n\n¡Además, puedes desplegar o cerrar tu historial de búsquedas recientes con un solo toque en el icono de reloj!")
                    .setPositiveButton("Siguiente") { _, _ ->
                        showTutorialStep(5)
                    }
                    .setCancelable(false)
                    .show()
            }
            5 -> {
                // Switch to CINE tab programmatically
                binding.bottomNavigation.selectedItemId = R.id.navigation_cine
                builder.setTitle("🎬 Paso 4: Cine y Series PRO")
                    .setMessage("Disfruta de más de 9,000 películas y series organizadas en una hermosa interfaz.\n\nAl seleccionar cualquier título, la app desglosa la sinopsis, valoraciones y trailers de TMDb en segundo plano al instante.")
                    .setPositiveButton("Siguiente") { _, _ ->
                        showTutorialStep(6)
                    }
                    .setCancelable(false)
                    .show()
            }
            6 -> {
                // Switch to SETTINGS tab programmatically
                binding.bottomNavigation.selectedItemId = R.id.navigation_settings
                builder.setTitle("⚙️ Paso 5: Ajustes y Configuración")
                    .setMessage("Accede de forma rápida al Control Parental (bloqueo por PIN de 4 dígitos) para ocultar categorías de adultos, borra la caché de lista para limpiar la app, o revisa la versión de soporte de la app.")
                    .setPositiveButton("Comenzar a Disfrutar") { _, _ ->
                        completeTutorial()
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun completeTutorial() {
        val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        sharedPref.edit().putBoolean("is_first_time", false).apply()
        
        // Return back to Home tab so they can begin loading their lists
        binding.bottomNavigation.selectedItemId = R.id.navigation_home
        Toast.makeText(this, "¡Recorrido completado! Que disfrutes de la aplicación. 🎉", Toast.LENGTH_LONG).show()
    }

    private fun loadIptvList(urlString: String, isAutoRestore: Boolean = false) {
        hideKeyboard()

        // Show global progress, lock load button
        binding.globalProgressBar.visibility = View.VISIBLE
        binding.btnLoad.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                fetchAndParseM3u(urlString)
            }

            binding.globalProgressBar.visibility = View.GONE
            binding.btnLoad.isEnabled = true

            if (result != null) {
                allChannels = result
                
                if (allChannels.isNotEmpty()) {
                    // Save URL to persistent storage since it loaded successfully!
                    val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
                    sharedPref.edit().putString("last_url", urlString).apply()

                    // Add to URL load history!
                    addSearchQuery(PLAYLIST_HISTORY_KEY, urlString)
                    updateHomeHistoryVisibility()

                    // 1. Parse and extract unique categories from list
                    val parsedCategories = mutableListOf("Todos")
                    val extractedGroups = allChannels.mapNotNull { it.group }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .sorted()
                    parsedCategories.addAll(extractedGroups)
                    categories = parsedCategories

                    // 2. Parse and extract unique countries from list!
                    val parsedCountries = mutableListOf("Todos")
                    val extractedCountries = allChannels.mapNotNull { it.country }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .sorted()
                    parsedCountries.addAll(extractedCountries)
                    countries = parsedCountries

                    // 3. Parse and extract unique languages from list!
                    val parsedLanguages = mutableListOf("Todos")
                    val extractedLanguages = allChannels.mapNotNull { it.language }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .sorted()
                    parsedLanguages.addAll(extractedLanguages)
                    languages = parsedLanguages

                    // 4. Reset variables
                    selectedCategory = "Todos"
                    selectedCountry = "Todos"
                    selectedLanguage = "Todos"
                    selectedSearchCountry = "Todos"
                    selectedAlphabet = "Sin Ordenar"
                    
                    // 5. Update the grids
                    channelsAdapter.updateList(allChannels)
                    searchAdapter.updateList(allChannels)
                    
                    // Update header text in categories/countries tab
                    binding.txtSelectedFilterInfo.text = "Canales: ${allChannels.size} (Filtros listos 🔄)"

                    // Clear search input on tab 3
                    binding.edtSearchTab.setText("")
                    binding.txtSearchCount.text = "Ingresa el nombre de un canal para buscar entre los ${allChannels.size} cargados"

                    // 6. Update empty state visibility structures
                    updateEmptyStates()

                    if (!isAutoRestore) {
                        Toast.makeText(this@MainActivity, "¡Lista cargada con éxito!", Toast.LENGTH_SHORT).show()
                    }

                    // 7. AUTO-SWITCH tab to "CANALES" so the user immediately sees the grid
                    binding.bottomNavigation.selectedItemId = R.id.navigation_channels
                } else {
                    allChannels = emptyList()
                    categories = emptyList()
                    countries = emptyList()
                    languages = emptyList()
                    updateEmptyStates()
                    Toast.makeText(this@MainActivity, "La lista M3U está vacía", Toast.LENGTH_LONG).show()
                }
            } else {
                updateEmptyStates()
                if (!isAutoRestore) {
                    Toast.makeText(this@MainActivity, "Error al descargar o procesar la lista. Revisa el enlace.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun fetchAndParseM3u(urlString: String): List<Channel>? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.requestMethod = "GET"
            connection.doInput = true
            
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                // High-performance streaming byte copy to save cache locally
                val bytes = connection.inputStream.readBytes()
                try {
                    val cacheFile = java.io.File(filesDir, "cached_playlist.m3u")
                    cacheFile.writeBytes(bytes)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                val byteArrayInputStream = java.io.ByteArrayInputStream(bytes)
                val parsed = M3UParser.parse(byteArrayInputStream)
                parsed
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun filterChannelsByCategoryAndCountry() {
        val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        val isParentalActive = sharedPref.getBoolean("parental_active", false)

        // 1. Filter dynamically by category AND country AND language concurrently!
        var filtered = allChannels.filter {
            (selectedCategory == "Todos" || it.group == selectedCategory) &&
            (selectedCountry == "Todos" || it.country == selectedCountry) &&
            (selectedLanguage == "Todos" || it.language == selectedLanguage)
        }

        // 2. Filter out adult channels if parental control is active!
        if (isParentalActive) {
            filtered = filtered.filter { !isAdultChannel(it) }
        }

        // 3. Sort dynamically alphabetically
        filtered = when (selectedAlphabet) {
            "A-Z" -> filtered.sortedBy { it.name }
            "Z-A" -> filtered.sortedByDescending { it.name }
            else -> filtered
        }

        // 4. Update adapter list
        channelsAdapter.updateList(filtered)

        // 5. Formulate clean filter status text in header
        val countText = "Canales: ${filtered.size}"
        val categoryText = if (selectedCategory == "Todos") "" else " | Cat: $selectedCategory"
        val countryText = if (selectedCountry == "Todos") "" else " | País: $selectedCountry"
        val languageText = if (selectedLanguage == "Todos") "" else " | Idioma: $selectedLanguage"
        val sortText = if (selectedAlphabet == "Sin Ordenar") "" else " | Orden: $selectedAlphabet"
        val parentalText = if (isParentalActive) " | 🔒 Parental Activo" else ""
        
        binding.txtSelectedFilterInfo.text = "$countText$categoryText$countryText$languageText$sortText$parentalText"
    }

    private fun onCategorySelected(category: String) {
        selectedCategory = category
        filterChannelsByCategoryAndCountry()
    }

    private fun onCountrySelected(country: String) {
        selectedCountry = country
        filterChannelsByCategoryAndCountry()
    }

    private fun filterSearchTabUnified() {
        val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        val isParentalActive = sharedPref.getBoolean("parental_active", false)

        val query = binding.edtSearchTab.text.toString().trim()
        var filtered = allChannels.filter {
            val matchesQuery = query.isEmpty() || it.name.contains(query, ignoreCase = true) || (it.group != null && it.group.contains(query, ignoreCase = true))
            val matchesCountry = selectedSearchCountry == "Todos" || it.country == selectedSearchCountry
            matchesQuery && matchesCountry
        }

        if (isParentalActive) {
            filtered = filtered.filter { !isAdultChannel(it) }
        }

        searchAdapter.updateList(filtered)
        if (query.isEmpty() && selectedSearchCountry == "Todos") {
            binding.txtSearchCount.text = "Mostrando todos: ${allChannels.size} canales"
        } else {
            binding.txtSearchCount.text = "Encontrados: ${filtered.size} de ${allChannels.size} (Filtro: $selectedSearchCountry)"
        }
        
        // Trigger the Spiderman overlay if they search for Spiderman
        checkAndShowSpidermanEasterEgg(query)
    }

    private fun onSearchCountrySelected(country: String) {
        selectedSearchCountry = country
        filterSearchTabUnified()
    }

    private fun updateEmptyStates() {
        val hasList = allChannels.isNotEmpty()

        if (hasList) {
            // TAB 2 (CANALES): Hide empty message, show channels list & categories
            binding.layoutChannelsEmpty.visibility = View.GONE
            binding.layoutChannelsContent.visibility = View.VISIBLE

            // TAB 3 (BUSCADOR): Hide empty message, show search input & grid
            binding.layoutSearchEmpty.visibility = View.GONE
            binding.layoutSearchContent.visibility = View.VISIBLE
        } else {
            // TAB 2 (CANALES): Show empty instructions message
            binding.layoutChannelsEmpty.visibility = View.VISIBLE
            binding.layoutChannelsContent.visibility = View.GONE

            // TAB 3 (BUSCADOR): Show empty instructions message
            binding.layoutSearchEmpty.visibility = View.VISIBLE
            binding.layoutSearchContent.visibility = View.GONE
        }
    }

    private fun loadRewardedInterstitialAd() {
        val adRequest = com.google.android.gms.ads.AdRequest.Builder().build()
        com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd.load(
            this,
            "ca-app-pub-8124327134735952/1699160240", // Your production Rewarded Interstitial ID!
            adRequest,
            object : com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd) {
                    mRewardedInterstitialAd = ad
                }

                override fun onAdFailedToLoad(loadAdError: com.google.android.gms.ads.LoadAdError) {
                    mRewardedInterstitialAd = null
                }
            }
        )
    }

    private fun loadRewardedAd() {
        val adRequest = com.google.android.gms.ads.AdRequest.Builder().build()
        com.google.android.gms.ads.rewarded.RewardedAd.load(
            this,
            "ca-app-pub-8124327134735952/7417379028", // Your production Rewarded Ad ID!
            adRequest,
            object : com.google.android.gms.ads.rewarded.RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: com.google.android.gms.ads.rewarded.RewardedAd) {
                    mRewardedAd = ad
                }

                override fun onAdFailedToLoad(loadAdError: com.google.android.gms.ads.LoadAdError) {
                    mRewardedAd = null
                }
            }
        )
    }

    private fun openPlayer(channel: Channel) {
        val rewardedInterstitial = mRewardedInterstitialAd
        val rewarded = mRewardedAd
        
        if (rewardedInterstitial != null) {
            rewardedInterstitial.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    mRewardedInterstitialAd = null
                    loadRewardedInterstitialAd() // Load next ad
                    launchPlayerActivity(channel)
                }

                override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                    mRewardedInterstitialAd = null
                    launchPlayerActivity(channel)
                }
            }
            rewardedInterstitial.show(this) { rewardItem -> }
        } else if (rewarded != null) {
            rewarded.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    mRewardedAd = null
                    loadRewardedAd() // Load next ad
                    launchPlayerActivity(channel)
                }

                override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                    mRewardedAd = null
                    launchPlayerActivity(channel)
                }
            }
            rewarded.show(this) { rewardItem -> }
        } else {
            launchPlayerActivity(channel)
        }
    }

    private fun launchPlayerActivity(channel: Channel) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("channelName", channel.name)
            putExtra("channelUrl", channel.url)
            putExtra("isLiveTv", true) // Mark as Live TV channel!
            putExtra("channelLogo", channel.logoUrl)
        }
        startActivity(intent)
    }

    private fun openPlayerWithSources(selectedUrl: String, allSources: ArrayList<String>) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("channelName", "Video Web: " + getDomainName(selectedUrl))
            putExtra("channelUrl", selectedUrl)
            putStringArrayListExtra("allSources", allSources)
            putExtra("isLiveTv", true) // External web player option acts as live or regular stream depending on origin
        }
        startActivity(intent)
    }

    private fun isAdultChannel(channel: Channel): Boolean {
        val keywords = listOf("xxx", "18+", "adult", "porn", "erotic", "erotica", "sensual", "forbiden", "prohibido", "hot", "sex", "redlight", "playboy")
        val name = channel.name.lowercase()
        val group = channel.group?.lowercase() ?: ""
        return keywords.any { name.contains(it) || group.contains(it) }
    }

    private fun loadCineCatalog() {
        lifecycleScope.launch {
            binding.layoutCineLoading.visibility = View.VISIBLE
            binding.rvCineGrid.visibility = View.GONE
            
            val catalog = CineRepository.getCineCatalog(this@MainActivity)
            allCineMedia = catalog
            refreshCoverData()
            
            binding.layoutCineLoading.visibility = View.GONE
            binding.rvCineGrid.visibility = View.VISIBLE
            
            cineAdapter.updateList(catalog)
            binding.txtCineCount.text = "Total: ${catalog.size}"
            buildCineReco()
            if (deckMode) refreshDeck() else setDeckMode(deckMode)
        }
    }

    private fun applyCineFilters() {
        val query = binding.edtCineSearch.text.toString().trim().lowercase()
        val filtered = allCineMedia.filter {
            val matchesType = when (selectedCineType) {
                "movie" -> it.type == "movie"
                "series" -> it.type == "series"
                "new" -> !it.releaseDate.isNullOrEmpty()
                else -> true
            }
            val matchesQuery = query.isEmpty() || it.title.lowercase().contains(query)
            val matchesMood = when (cineMood) {
                "estrenos" -> (it.releaseDate?.take(4)?.toIntOrNull() ?: 0) >= 2024
                "top" -> (it.rating ?: 0.0) >= 7.5
                "accion" -> listOf("accion", "acción", "action", "aventura", "guerra", "combate", "pelea", "espionaje", "ninja", "superhéroe", "superheroe").any { k ->
                    (it.title + " " + (it.overview ?: "") + " " + it.group).lowercase().contains(k)
                }
                "feelgood" -> listOf("comedia", "romance", "familia", "animaci", "navidad", "musica", "adolescente", "feel").any { k ->
                    (it.title + " " + (it.overview ?: "") + " " + it.group).lowercase().contains(k)
                }
                else -> true
            }
            matchesType && matchesQuery && matchesMood
        }
        val finalCineList = if (selectedCineType == "new") {
            filtered.sortedByDescending { it.releaseDate ?: "" }.take(60)
        } else if (selectedCineType == "all" && cineMood == null && query.isEmpty()) {
            // orden casa: lo mejor segun TMDB primero, sin tocar el resto
            filtered.sortedByDescending { it.rating ?: -1.0 }
        } else filtered
        cineAdapter.updateList(finalCineList)
        binding.txtCineCount.text = if (selectedCineType == "new") "Novedades: ${finalCineList.size}" else "Total: ${finalCineList.size}"
        
        // Trigger the Spiderman overlay if they search for Spiderman
        checkAndShowSpidermanEasterEgg(query)
    }

    private fun updateCineFilterButtons() {
        val orangeColor = AccentManager.color(this)
        val grayColor = android.graphics.Color.parseColor("#14141E")
        
        binding.btnCineFilterAll.setBackgroundColor(if (selectedCineType == "all") orangeColor else grayColor)
        binding.btnCineFilterAll.setTextColor(if (selectedCineType == "all") android.graphics.Color.WHITE else android.graphics.Color.parseColor("#98989F"))
        
        binding.btnCineFilterMovies.setBackgroundColor(if (selectedCineType == "movie") orangeColor else grayColor)
        binding.btnCineFilterMovies.setTextColor(if (selectedCineType == "movie") android.graphics.Color.WHITE else android.graphics.Color.parseColor("#98989F"))
        
        binding.btnCineFilterSeries.setBackgroundColor(if (selectedCineType == "series") orangeColor else grayColor)
        binding.btnCineFilterSeries.setTextColor(if (selectedCineType == "series") android.graphics.Color.WHITE else android.graphics.Color.parseColor("#98989F"))
        binding.btnCineFilterNews.setBackgroundColor(if (selectedCineType == "new") orangeColor else grayColor)
        binding.btnCineFilterNews.setTextColor(if (selectedCineType == "new") android.graphics.Color.WHITE else android.graphics.Color.parseColor("#98989F"))
    }

    private fun updateCineMoodButtons() {
        val activeTint = android.graphics.Color.parseColor("#3E2E1F")
        val activeText = android.graphics.Color.parseColor("#C9A96E")
        val grayTint = android.graphics.Color.parseColor("#33404048")
        val grayText = android.graphics.Color.parseColor("#98989F")
        binding.btnMoodEstrenos.setBackgroundColor(if (cineMood == "estrenos") activeTint else grayTint)
        binding.btnMoodEstrenos.setTextColor(if (cineMood == "estrenos") activeText else grayText)
        binding.btnMoodTop.setBackgroundColor(if (cineMood == "top") activeTint else grayTint)
        binding.btnMoodTop.setTextColor(if (cineMood == "top") activeText else grayText)
    }

    private fun updateParaTiChips() {
        val activeTint = android.graphics.Color.parseColor("#3E2E1F")
        val activeText = android.graphics.Color.parseColor("#C9A96E")
        val grayTint = android.graphics.Color.parseColor("#33404048")
        val grayText = android.graphics.Color.parseColor("#98989F")
        fun paint(b: android.widget.Button, active: Boolean) {
            b.setBackgroundColor(if (active) activeTint else grayTint)
            b.setTextColor(if (active) activeText else grayText)
        }
        paint(binding.chipMoodTodos, selectedCineType == "all" && cineMood == null)
        paint(binding.chipMoodAccion, cineMood == "accion")
        paint(binding.chipMoodFeel, cineMood == "feelgood")
        paint(binding.chipMoodSerie, selectedCineType == "series")
        paint(binding.chipMoodCorta, selectedCineType == "movie")
        paint(binding.chipMoodTop2, cineMood == "top")
        paint(binding.chipMoodEstrenos2, cineMood == "estrenos")
    }

    /** Rail "PORQUE VISTE X": semilla = lo ultimo visto de cine; sin historial -> top valoradas. */
    private fun buildCineReco() {
        val seed = ContinueWatchingManager.getAll(this)
            .filter { !it.isChannel && it.media != null }
            .maxByOrNull { it.savedAt }
        val reco: List<CineMedia>
        if (seed?.media != null) {
            reco = allCineMedia
                .filter { it.group == seed.media.group && it.title != seed.title }
                .filter { !it.posterUrl.isNullOrBlank() }
                .sortedByDescending { it.rating ?: 0.0 }
                .take(12)
            binding.txtCineRecoLabel.text = "PORQUE VISTE ${seed.title.uppercase()}"
        } else {
            reco = allCineMedia
                .filter { !it.posterUrl.isNullOrBlank() && (it.rating ?: 0.0) > 0.0 }
                .sortedByDescending { it.rating ?: 0.0 }
                .take(12)
            binding.txtCineRecoLabel.text = "LAS MEJOR VALORADAS"
        }
        if (::cineRecoAdapter.isInitialized) cineRecoAdapter.submitAll(reco)
        val vis = if (reco.isEmpty()) View.GONE else View.VISIBLE
        binding.txtCineRecoLabel.visibility = vis
        binding.rvCineReco.visibility = vis
    }

    // ================= CINE V3 · DESCUBRE (deck + learning) =================

    private fun setupCineDeck() {
        if (cineDeckWired) return
        cineDeckWired = true
        binding.deckCardFront.root.setOnTouchListener { v, ev -> onDeckTouch(v, ev) }
        binding.btnDeckSkip.setOnClickListener { it.springPress(); flyOutAndAdvance(-1) }
        binding.btnDeckPlay.setOnClickListener {
            it.springPress()
            deckFront?.let { m -> openCineDetail(m) }
        }
        binding.btnDeckFav.setOnClickListener {
            it.springPress()
            val m = deckFront ?: return@setOnClickListener
            val added = FavoritesManager.toggleMedia(this, m)
            if (added) TasteProfile.recordFavorite(this, m)
            Toast.makeText(this, if (added) "Guardado en Favoritos ⭐ tu mazo se afina" else "Quitado de Favoritos", Toast.LENGTH_SHORT).show()
            refreshFavorites()
            if (added) flyOutAndAdvance(1)
        }
        binding.txtCineCatalogToggle.setOnClickListener { setDeckMode(!deckMode) }
        renderDeck()
    }

    private fun setDeckMode(on: Boolean) {
        deckMode = on
        binding.layoutCineDeck.visibility = if (on) View.VISIBLE else View.GONE
        binding.cineDeckActions.visibility = if (on && deckFront != null) View.VISIBLE else View.GONE
        binding.txtCineCatalogToggle.visibility = View.VISIBLE
        binding.txtCineCatalogToggle.text = if (on) "▦  Ver catálogo completo  ›" else "✨  Volver a Descubre  ›"
        val cat = if (on) View.GONE else View.VISIBLE
        binding.rvCineGrid.visibility = cat
        binding.cineChipsScroll.visibility = cat
        binding.txtCineCount.visibility = cat
        binding.layoutCineLoading.visibility = if (on) View.GONE else binding.layoutCineLoading.visibility
        if (on) {
            binding.rvCineReco.visibility = View.GONE
            binding.txtCineRecoLabel.visibility = View.GONE
            refreshDeck()
        } else {
            binding.rvCineReco.visibility = if (binding.rvCineReco.adapter?.itemCount ?: 0 > 0) View.VISIBLE else View.GONE
            binding.txtCineRecoLabel.visibility = if (binding.rvCineReco.adapter?.itemCount ?: 0 > 0) View.VISIBLE else View.GONE
        }
    }

    private fun refreshDeck() {
        if (allCineMedia.isEmpty()) { renderDeck(); return }
        val watched = ContinueWatchingManager.getAll(this)
            .filter { !it.isChannel }.map { it.title }.toHashSet()
        val candidates = allCineMedia.filter { !it.posterUrl.isNullOrBlank() && it.title !in watched && it.title !in deckShown }
        deckQueue.clear()
        if (candidates.isNotEmpty()) {
            val scored = candidates.map { it to TasteProfile.score(this, it) }
            val learned = scored.filter { it.second >= 2.0 }.sortedByDescending { it.second }
            val queue = if (learned.isNotEmpty()) learned.take(40).map { it.first }
                        else candidates.sortedByDescending { it.rating ?: 0.0 }.take(40)
            deckQueue.addAll(queue)
        }
        renderDeck()
    }

    private fun renderDeck() {
        val front = deckQueue.firstOrNull()
        deckFront = front
        fillDeckCard(binding.deckCardFront.root, front)
        fillDeckCard(binding.deckCardBack.root, deckQueue.elementAtOrNull(1))
        binding.cineDeckActions.visibility = if (front != null && deckMode) View.VISIBLE else View.GONE
        if (front != null) deckShown.add(front.title)
    }

    private fun fillDeckCard(card: View, media: CineMedia?) {
        if (media == null) { card.visibility = View.GONE; return }
        card.visibility = View.VISIBLE
        Glide.with(card.context).load(media.posterUrl)
            .transition(DrawableTransitionOptions.withCrossFade(250))
            .centerCrop()
            .into(card.findViewById(R.id.imgDeckPoster))
        card.findViewById<android.widget.TextView>(R.id.txtDeckTitle).text = media.title
        val yr = media.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
        val typeTxt = if (media.type == "movie") "Película" else "Serie"
        card.findViewById<android.widget.TextView>(R.id.txtDeckMeta).text = listOfNotNull(
            yr, typeTxt,
            if (media.urls.size > 0) "${media.urls.size} servidores" else null
        ).joinToString(" · ")
        val rb = card.findViewById<android.widget.TextView>(R.id.txtDeckRating)
        val r = media.rating ?: 0.0
        rb.visibility = if (r > 0.0) View.VISIBLE else View.GONE
        if (r > 0.0) rb.text = "★ %.1f".format(r)
        val eb = card.findViewById<android.widget.TextView>(R.id.txtDeckEyebrow)
        val reason = TasteProfile.topReason(this, media)
        if (reason != null) {
            eb.visibility = View.VISIBLE
            eb.text = "PORQUE VES · ${reason.uppercase()}"
        } else eb.visibility = View.GONE
        card.animate().alpha(1f).setDuration(200).start()
    }

    private fun onDeckTouch(v: View, ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { deckDownX = ev.x; return true }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - deckDownX
                v.translationX = dx
                v.rotation = dx / 25f
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                val dx = ev.x - deckDownX
                if (kotlin.math.abs(dx) > 150f) {
                    flyOutAndAdvance(if (dx > 0) 1 else -1)
                } else {
                    v.animate().translationX(0f).rotation(0f)
                        .setDuration(260)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                        .start()
                }
                return true
            }
        }
        return false
    }

    private fun flyOutAndAdvance(dir: Int) {
        if (deckBusy || deckFront == null) return
        deckBusy = true
        val front = binding.deckCardFront.root
        front.animate()
            .translationX(dir * front.width * 1.4f)
            .rotation(dir * 20f)
            .alpha(0f)
            .setDuration(240)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                if (deckQueue.isNotEmpty()) deckQueue.removeFirst()
                if (deckQueue.size < 4) refreshDeck()
                // reset instantaneo
                front.rotation = 0f
                front.translationX = 0f
                front.alpha = 0f
                renderDeck()
                deckBusy = false
            }
            .start()
    }

        private fun openCineDetail(media: CineMedia) {
        TasteProfile.recordOpen(this, media)
        val intent = Intent(this, if (media.type == "movie") CineMovieDetailActivity::class.java else CineTvShowDetailActivity::class.java).apply {
            putExtra("media", media)
        }
        startActivity(intent)
    }

    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private var nativeAd: com.google.android.gms.ads.nativead.NativeAd? = null

    private fun loadNativeAd() {
        val adLoader = com.google.android.gms.ads.AdLoader.Builder(this, "ca-app-pub-8124327134735952/9832680995")
            .forNativeAd { ad : com.google.android.gms.ads.nativead.NativeAd ->
                nativeAd?.destroy()
                nativeAd = ad
                populateNativeAdView(ad)
            }
            .withAdListener(object : com.google.android.gms.ads.AdListener() {
                override fun onAdFailedToLoad(loadAdError: com.google.android.gms.ads.LoadAdError) {
                    binding.cardGlobalNativeAd.visibility = View.GONE
                }
            })
            .build()

        adLoader.loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
    }

    private fun populateNativeAdView(nativeAd: com.google.android.gms.ads.nativead.NativeAd) {
        val adView = binding.globalNativeAdView
        
        val headlineView = adView.findViewById<android.widget.TextView>(R.id.global_ad_headline)
        val bodyView = adView.findViewById<android.widget.TextView>(R.id.global_ad_body)
        val iconView = adView.findViewById<android.widget.ImageView>(R.id.global_ad_app_icon)
        val ctaButton = adView.findViewById<android.widget.Button>(R.id.global_ad_call_to_action)
        
        headlineView.text = nativeAd.headline
        adView.headlineView = headlineView
        
        if (nativeAd.body != null) {
            bodyView.visibility = View.VISIBLE
            bodyView.text = nativeAd.body
            adView.bodyView = bodyView
        } else {
            bodyView.visibility = View.GONE
        }
        
        if (nativeAd.icon != null) {
            iconView.visibility = View.VISIBLE
            iconView.setImageDrawable(nativeAd.icon?.drawable)
            adView.iconView = iconView
        } else {
            iconView.visibility = View.GONE
        }
        
        if (nativeAd.callToAction != null) {
            ctaButton.visibility = View.VISIBLE
            ctaButton.text = nativeAd.callToAction
            adView.callToActionView = ctaButton
        } else {
            ctaButton.visibility = View.GONE
        }
        
        adView.setNativeAd(nativeAd)
    }

    private fun getSearchHistory(key: String): MutableList<String> {
        val prefs = getSharedPreferences("IPTV_PREFS", Context.MODE_PRIVATE)
        val historyStr = prefs.getString(key, "") ?: ""
        if (historyStr.isEmpty()) return mutableListOf()
        return historyStr.split("|||").filter { it.isNotEmpty() }.toMutableList()
    }

    private fun saveSearchHistory(key: String, list: List<String>) {
        val prefs = getSharedPreferences("IPTV_PREFS", Context.MODE_PRIVATE)
        val historyStr = list.joinToString("|||")
        prefs.edit().putString(key, historyStr).apply()
    }

    private fun addSearchQuery(key: String, query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val current = getSearchHistory(key)
        current.remove(trimmed) // Remove duplicates
        current.add(0, trimmed) // Add to top
        if (current.size > 5) { // Keep last 5 recent searches
            current.removeAt(current.size - 1)
        }
        saveSearchHistory(key, current)
    }

    private lateinit var channelsHistoryAdapter: SearchHistoryAdapter
    private lateinit var cineHistoryAdapter: SearchHistoryAdapter
    private lateinit var playlistHistoryAdapter: SearchHistoryAdapter
    
    private val CHANNELS_HISTORY_KEY = "CHANNELS_SEARCH_HISTORY"
    private val CINE_HISTORY_KEY = "CINE_SEARCH_HISTORY"
    private val PLAYLIST_HISTORY_KEY = "PLAYLIST_URL_HISTORY"

    private fun setupSearchHistories() {
        // Channels search history
        binding.rvChannelsSearchHistory.layoutManager = LinearLayoutManager(this)
        channelsHistoryAdapter = SearchHistoryAdapter(getSearchHistory(CHANNELS_HISTORY_KEY),
            onItemClick = { query ->
                binding.edtSearchTab.setText(query)
                performChannelsSearch(query)
                binding.layoutChannelsSearchHistory.visibility = View.GONE
            },
            onDeleteClick = { query ->
                val current = getSearchHistory(CHANNELS_HISTORY_KEY)
                current.remove(query)
                saveSearchHistory(CHANNELS_HISTORY_KEY, current)
                channelsHistoryAdapter.updateList(current)
                if (current.isEmpty()) {
                    binding.layoutChannelsSearchHistory.visibility = View.GONE
                }
            }
        )
        binding.rvChannelsSearchHistory.adapter = channelsHistoryAdapter

        binding.btnChannelsClearAll.setOnClickListener {
            saveSearchHistory(CHANNELS_HISTORY_KEY, emptyList())
            channelsHistoryAdapter.updateList(emptyList())
            binding.layoutChannelsSearchHistory.visibility = View.GONE
        }

        // Cine search history
        binding.rvCineSearchHistory.layoutManager = LinearLayoutManager(this)
        cineHistoryAdapter = SearchHistoryAdapter(getSearchHistory(CINE_HISTORY_KEY),
            onItemClick = { query ->
                binding.edtCineSearch.setText(query)
                performCineSearch(query)
                binding.layoutCineSearchHistory.visibility = View.GONE
            },
            onDeleteClick = { query ->
                val current = getSearchHistory(CINE_HISTORY_KEY)
                current.remove(query)
                saveSearchHistory(CINE_HISTORY_KEY, current)
                cineHistoryAdapter.updateList(current)
                if (current.isEmpty()) {
                    binding.layoutCineSearchHistory.visibility = View.GONE
                }
            }
        )
        binding.rvCineSearchHistory.adapter = cineHistoryAdapter

        binding.btnCineClearAll.setOnClickListener {
            saveSearchHistory(CINE_HISTORY_KEY, emptyList())
            cineHistoryAdapter.updateList(emptyList())
            binding.layoutCineSearchHistory.visibility = View.GONE
        }

        // Playlist load history
        binding.rvHomePlaylistHistory.layoutManager = LinearLayoutManager(this)
        playlistHistoryAdapter = SearchHistoryAdapter(getSearchHistory(PLAYLIST_HISTORY_KEY),
            onItemClick = { query ->
                binding.edtUrl.setText(query)
                loadIptvList(query)
            },
            onDeleteClick = { query ->
                val current = getSearchHistory(PLAYLIST_HISTORY_KEY)
                current.remove(query)
                saveSearchHistory(PLAYLIST_HISTORY_KEY, current)
                playlistHistoryAdapter.updateList(current)
                updateHomeHistoryVisibility()
            }
        )
        binding.rvHomePlaylistHistory.adapter = playlistHistoryAdapter

        binding.btnHomeClearHistory.setOnClickListener {
            saveSearchHistory(PLAYLIST_HISTORY_KEY, emptyList())
            playlistHistoryAdapter.updateList(emptyList())
            updateHomeHistoryVisibility()
        }

        // Initial check of history visibility
        updateHomeHistoryVisibility()
    }

    private fun updateHomeHistoryVisibility() {
        val current = getSearchHistory(PLAYLIST_HISTORY_KEY)
        if (current.isNotEmpty()) {
            binding.cardHomeStatus.visibility = View.VISIBLE
            binding.rvHomePlaylistHistory.visibility = View.VISIBLE
            playlistHistoryAdapter.updateList(current)
        } else {
            binding.cardHomeStatus.visibility = View.GONE
        }
    }

    private fun performChannelsSearch(query: String) {
        binding.edtSearchTab.setText(query)
        filterSearchTabUnified()
        addSearchQuery(CHANNELS_HISTORY_KEY, query)
    }

    private fun performCineSearch(query: String) {
        binding.edtCineSearch.setText(query)
        applyCineFilters()
        addSearchQuery(CINE_HISTORY_KEY, query)
    }

    private fun applyAppTheme() {
        val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        val selectedTheme = sharedPref.getString("theme_pref", "system") ?: "system"
        
        val isDark = if (selectedTheme == "system") {
            val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        } else {
            selectedTheme == "dark"
        }
        
        binding.root.post {
            updateAppThemeColors(isDark)
        }
    }

    private fun updateAppThemeColors(isDark: Boolean) {
        val bgColor = if (isDark) android.graphics.Color.parseColor("#121212") else android.graphics.Color.parseColor("#F5F5F5")
        val cardColor = if (isDark) android.graphics.Color.parseColor("#1E1E1E") else android.graphics.Color.parseColor("#FFFFFF")
        val textColor = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        
        // 1. Update window background
        window.decorView.setBackgroundColor(bgColor)
        
        // 2. Update main tab containers backgrounds
        binding.containerHome.setBackgroundColor(bgColor)
        binding.containerChannels.setBackgroundColor(bgColor)
        binding.containerCine.setBackgroundColor(bgColor)
        binding.containerBrowser.setBackgroundColor(bgColor)
        binding.containerSearch.setBackgroundColor(bgColor)
        
        // 3. Update bottom navigation bar (both background, text tint, and dynamic icon tint)
        binding.bottomNavigation.setBackgroundColor(cardColor)
        binding.bottomNavigation.itemTextColor = android.content.res.ColorStateList.valueOf(textColor)
        
        // Dynamic bottom icon tint: White in dark mode, Black in light mode
        val iconColor = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        binding.bottomNavigation.itemIconTintList = android.content.res.ColorStateList.valueOf(iconColor)
        
        // 4. Update recursively all subviews (CardViews, TextViews, and EditTexts)
        updateSubviewsColorRecursive(binding.root, isDark)
    }

    private fun updateSubviewsColorRecursive(view: android.view.View, isDark: Boolean) {
        val textColor = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val cardColor = if (isDark) android.graphics.Color.parseColor("#1E1E1E") else android.graphics.Color.parseColor("#FFFFFF")
        
        if (view is androidx.cardview.widget.CardView) {
            view.setCardBackgroundColor(cardColor)
        } else if (view is android.widget.TextView) {
            // Do not override primary orange/red/yellow colored texts
            val isOrange = view.currentTextColor == android.graphics.Color.parseColor("#FF9800")
            val isRed = view.currentTextColor == android.graphics.Color.parseColor("#D32F2F")
            if (!isOrange && !isRed) {
                view.setTextColor(textColor)
            }
        } else if (view is android.widget.EditText) {
            view.setTextColor(textColor)
            view.setHintTextColor(if (isDark) android.graphics.Color.parseColor("#666666") else android.graphics.Color.parseColor("#999999"))
            view.setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#1E1E1E") else android.graphics.Color.parseColor("#E0E0E0"))
        }
        
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                updateSubviewsColorRecursive(view.getChildAt(i), isDark)
            }
        }
    }

    private var lastSpidermanSearchQuery = ""
    private var spidermanOverlayJob: kotlinx.coroutines.Job? = null

    private fun checkAndShowSpidermanEasterEgg(query: String) {
        val lower = query.lowercase().trim()
        if (lower.isEmpty()) return
        
        // Trigger if the query contains 'spider' (covers 'spiderman', 'spider-man', 'spider man')
        val containsSpiderman = lower.contains("spider")
        
        // Only trigger once per unique search query text to prevent double triggering on text change
        if (containsSpiderman && lower != lastSpidermanSearchQuery) {
            lastSpidermanSearchQuery = lower
            showSpidermanOverlay()
        } else if (!containsSpiderman) {
            lastSpidermanSearchQuery = ""
        }
    }

    private fun showSpidermanOverlay() {
        spidermanOverlayJob?.cancel() // Cancel any pending close timer
        binding.layoutSpidermanOverlay.alpha = 1f
        binding.layoutSpidermanOverlay.visibility = View.VISIBLE
        
        // Apply beautiful hardware-accelerated soft blur behind the overlay on Android 12+ (API 31+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val blurEffect = android.graphics.RenderEffect.createBlurEffect(
                15f, 15f,
                android.graphics.Shader.TileMode.CLAMP
            )
            binding.contentContainer.setRenderEffect(blurEffect)
        }
        
        // Load the GIF using Glide
        com.bumptech.glide.Glide.with(this)
            .asGif()
            .load(R.drawable.spiderman_multiverse)
            .placeholder(R.drawable.bg_placeholder)
            .into(binding.imgSpidermanOverlayGif)
            
        // Keep the overlay on screen for exactly 3.5 seconds, then hide it with a smooth fade-out!
        spidermanOverlayJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(3500)
            hideSpidermanOverlay()
        }
            
        // Also allow the user to tap to dismiss the overlay immediately!
        binding.layoutSpidermanOverlay.setOnClickListener {
            spidermanOverlayJob?.cancel()
            hideSpidermanOverlay()
        }
    }

    private fun hideSpidermanOverlay() {
        if (binding.layoutSpidermanOverlay.visibility == View.VISIBLE) {
            binding.layoutSpidermanOverlay.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction {
                    binding.layoutSpidermanOverlay.visibility = View.GONE
                    binding.layoutSpidermanOverlay.alpha = 1f // Reset alpha for next time
                    
                    // Clear the blur effect on Android 12+
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        binding.contentContainer.setRenderEffect(null)
                    }
                }
                .start()
        }
    }

    override fun onDestroy() {
        nativeAd?.destroy()
        super.onDestroy()
    }
}

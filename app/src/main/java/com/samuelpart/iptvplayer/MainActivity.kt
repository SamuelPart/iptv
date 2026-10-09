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
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
    private lateinit var cineAdapter: CineSearchResultAdapter

    private var allCineMedia: List<CineMedia> = emptyList()
    private var selectedCineType: String = "all" // "all", "movie", "series"

    // Public test lists (legal & free streams)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyAppTheme()

        // La barra de estado (hora, red, notificaciones) siempre visible,
        // con el color grafito de la app (definido en themes.xml).


        setupBottomNavigation()
        setupRecyclerViews()
        incrementOpenCounter()
        setupHomeBanner()
        setupHomeNewSection()
        setupHomeSections()
        setupSearchHistories()
        setupListeners()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77)
        }
        updateEmptyStates() // Initial state shows instructions everywhere
        
        // Initialize Google AdMob SDK and load ads
        com.google.android.gms.ads.MobileAds.initialize(this) {}
        val adRequest = com.google.android.gms.ads.AdRequest.Builder().build()
        binding.adView.loadAd(adRequest)
        RewardGate.load(this)
        setupNativeAds()
        
        // Auto-restore last successfully loaded playlist on app startup!
        restoreSavedPlaylist()


        // Load the TMDb integrated Cine & Series Catalog!
        loadCineCatalog()

        // ¿Llegamos desde un enlace compartido (lumen://ver?d=...)? Entra
        // directo al Cine; en cuanto el catalogo cargue, se abre la ficha.
        pendingDeepLink = DeepLink.fromIntent(intent)

        // Tip contextual de bienvenida (solo la primera vez que se abre la app)
        binding.root.postDelayed({
            if (!isFinishing) SmartTips.showOnce(this, "home",
                "💡 Bienvenido: carga tu lista M3U en Inicio o toca Cine para +9.000 películas")
        }, 3200)
    }

    private fun setupBottomNavigation() {
        // iOS look: system blue when selected, iOS gray otherwise
        val navTint = androidx.core.content.ContextCompat.getColorStateList(this, R.color.nav_item_tint)
        binding.bottomNavigation.itemIconTintList = navTint
        binding.bottomNavigation.itemTextColor = navTint
        updateLumenNav(R.id.navigation_home)
        listOf(binding.navBtnHome, binding.navBtnChannels, binding.navBtnSearch,
            binding.navBtnCine, binding.navBtnSettings).forEach { it.springPress() }
        binding.navBtnHome.setOnClickListener { binding.bottomNavigation.selectedItemId = R.id.navigation_home }
        binding.navBtnChannels.setOnClickListener { binding.bottomNavigation.selectedItemId = R.id.navigation_channels }
        binding.navBtnSearch.setOnClickListener { binding.bottomNavigation.selectedItemId = R.id.navigation_search }
        binding.navBtnCine.setOnClickListener { binding.bottomNavigation.selectedItemId = R.id.navigation_cine }
        binding.navBtnSettings.setOnClickListener { binding.bottomNavigation.selectedItemId = R.id.navigation_settings }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            updateLumenNav(item.itemId)
            when (item.itemId) {
                R.id.navigation_home -> {
                    showTab(View.VISIBLE, View.GONE, View.GONE, View.GONE, View.GONE)
                    true
                }
                R.id.navigation_channels -> {
                    showTab(View.GONE, View.VISIBLE, View.GONE, View.GONE, View.GONE)
                    SmartTips.showOnce(this, "channels",
                        "💡 Toca el filtro para ordenar los canales por país o categoría")
                    true
                }
                R.id.navigation_search -> {
                    showTab(View.GONE, View.GONE, View.GONE, View.GONE, View.VISIBLE)
                    SmartTips.showOnce(this, "search",
                        "💡 Busca canales y películas al instante, con historial incluido")
                    true
                }
                R.id.navigation_cine -> {
                    showTab(View.GONE, View.GONE, View.VISIBLE, View.GONE, View.GONE)
                    SmartTips.showOnce(this, "cine",
                        "💡 El BOT busca los mejores servidores por ti: toca una película y solo disfruta")
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

        // El anuncio nativo va ahora insertado dentro del contenido de Inicio
        // (se muestra solo cuando ya cargó), y el banner queda fijo abajo.
        binding.adView.visibility = View.VISIBLE
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
        val channelsGridLayout = GridLayoutManager(this, 1)
        binding.rvChannelsGrid.layoutManager = channelsGridLayout
        channelsAdapter = ChannelAdapter(
            emptyList(),
            onChannelClick = { channel -> openPlayer(channel) },
            isFavorite = { FavoritesManager.isFavorite(this, it.url) },
            onFavoriteToggle = { channel ->
                val added = FavoritesManager.toggleChannel(this, channel)
                Toast.makeText(this, if (added) "Guardado en Favoritos ⭐" else "Quitado de Favoritos", Toast.LENGTH_SHORT).show()
                    }
        )
        binding.rvChannelsGrid.adapter = channelsAdapter
        channelsAdapter.gridColumns = 1 // sintonizador: 1 columna → anuncio cada 4 filas (4 canales)
        channelsGridLayout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (channelsAdapter.isAdAt(position)) channelsGridLayout.spanCount else 1
        }
        channelsAdapter.attachTuner(binding.rvChannelsGrid)
        channelsAdapter.tunerMode = true
        binding.btnToggleLayout.setImageResource(R.drawable.ic_ios_list)

        // 2. Setup Search Grid in Buscador tab (3 columns)
        val searchGridLayout = GridLayoutManager(this, 3)
        binding.rvSearchGrid.layoutManager = searchGridLayout
        searchAdapter = ChannelAdapter(
            emptyList(),
            onChannelClick = { channel -> openPlayer(channel) },
            isFavorite = { FavoritesManager.isFavorite(this, it.url) },
            onFavoriteToggle = { channel ->
                val added = FavoritesManager.toggleChannel(this, channel)
                Toast.makeText(this, if (added) "Guardado en Favoritos ⭐" else "Quitado de Favoritos", Toast.LENGTH_SHORT).show()
                    }
        )
        binding.rvSearchGrid.adapter = searchAdapter
        searchAdapter.gridColumns = 3 // 3 columnas → anuncio cada 4 filas (12 canales)
        searchGridLayout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (searchAdapter.isAdAt(position)) searchGridLayout.spanCount else 1
        }

        // 3. Setup Cine Grid in Cine tab (2 columns for high-end poster aspect ratio)
        val cineGridLayout = GridLayoutManager(this, 2)
        binding.rvCineGrid.layoutManager = cineGridLayout
        cineAdapter = CineSearchResultAdapter(
            emptyList(),
            onMediaClick = { media -> openCineDetail(media) },
            isFavorite = { FavoritesManager.isFavorite(this, it.url) },
            onFavoriteToggle = { media ->
                val added = FavoritesManager.toggleMedia(this, media)
                Toast.makeText(this, if (added) "Guardado en Favoritos ⭐" else "Quitado de Favoritos", Toast.LENGTH_SHORT).show()
                    }
        )
        binding.rvCineGrid.adapter = cineAdapter
        cineAdapter.gridColumns = 2 // 2 columnas → anuncio cada 4 filas (8 títulos)
        binding.rvCineGrid.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.GridLayoutManager ?: return
                val total = recyclerView.adapter?.itemCount ?: return
                if (lm.findLastVisibleItemPosition() >= total - 4) cineLoadMore()
            }
        })
        cineGridLayout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (cineAdapter.isAdAt(position)) cineGridLayout.spanCount else 1
        }

        // Rail "Porque viste X" (Cine v2)
        cineRecoAdapter = CineRecoAdapter { media -> openCineDetail(media) }
        binding.rvCineReco.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCineCoverflow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCineCoverflow.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                transformCoverflow(binding.rvCineCoverflow)
            }

            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    val snap = coverflowSnap?.findSnapView(recyclerView.layoutManager)
                    val pos = if (snap != null) recyclerView.getChildAdapterPosition(snap) else -1
                    if (pos >= 0) {
                        currentCoverflowPos = pos
                        updatePlatformLogoFor(pos)
                    }
                }
            }
        })
        coverflowSnap = androidx.recyclerview.widget.LinearSnapHelper()
        coverflowSnap?.attachToRecyclerView(binding.rvCineCoverflow)
        binding.rvCineCoverflow.post { transformCoverflow(binding.rvCineCoverflow) }
        binding.rvCinePopular.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCineReco.adapter = cineRecoAdapter

        setupCineDeck()

        // PANTALLA PREMIER COMPLETA (los 5 bloques aprobados de una vez):
        // 1 top bar + 2 carrusel 3D + 3 Popular + 4 New Episode (+ 5 nav pro
        // ya activo globalmente). El mobiliario anterior permanece apagado.
        for (i in 0 until binding.containerCine.childCount) {
            val v = binding.containerCine.getChildAt(i)
            v.visibility = if (v.id == R.id.premierScroll) View.VISIBLE else View.GONE
        }

        binding.txtSeeAllPopular.setOnClickListener {
            startActivity(Intent(this, CinePopularAllActivity::class.java))
        }

        setupCineMenu()

    }

    override fun onResume() {
        super.onResume()
        startHomeSearchRotator()
        resumeHomeBanner()
        applyAccentColor()
        handlePendingSettings()
        syncCineCatalogIfNeeded()
    }

    /** Si el BOT detectó cambios en GitHub (películas/series nuevas), refresca
     *  el catálogo y repinta el Cine sin reinstalar. */
    private fun syncCineCatalogIfNeeded() {
        if (!CatalogBot.consumeChangedFlag(this)) return
        lifecycleScope.launch {
            val sync = withContext(Dispatchers.IO) { CineRepository.refreshCatalog(this@MainActivity) }
            allCineMedia = sync.catalog
            applyCineFilters()
            refreshHomeSections()
            setupCineFeatured()
            if (sync.addedTitles > 0) {
                Toast.makeText(
                    this@MainActivity,
                    "🎬 Catálogo actualizado: ${sync.addedTitles} ${if (sync.addedTitles == 1) "título nuevo" else "títulos nuevos"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Aplica lo que pidieron las pantallas de Ajustes (cargar lista, limpiar
     *  o re-aplicar tema/acento/parental) al volver a MainActivity. */
    private fun handlePendingSettings() {
        SettingsSync.consumePendingLoad(this)?.let { url ->
            loadIptvList(url)
        }
        if (SettingsSync.consumePendingClear(this)) {
            clearPlaylist()
        }
        if (SettingsSync.consume(this)) {
            applyAppTheme()
            applyAccentColor()
            applyFiltersAndSorting()
            filterSearchTabUnified()
        }
    }

    /** Repaints the Home favorites strip from the local store. */
    /** Re-tints nav + cine segment with the chosen accent. */
    private fun applyAccentColor() {
        val navTint = AccentManager.navTintList(this)
        binding.bottomNavigation.itemIconTintList = navTint
        binding.bottomNavigation.itemTextColor = navTint
        updateCineFilterButtons()
    }

    private fun incrementOpenCounter() {
        val prefs = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        prefs.edit().putInt("app_open_count", prefs.getInt("app_open_count", 0) + 1).apply()
    }

    // ================= HOME V4 - COVERFLOW =================

    // ================= BANNER DE INICIO + ULTIMAMENTE NUEVO + PAGINACION =================

    /** Repinta el banner y TODAS las secciones de Inicio tras cargar el catalogo. */
    private fun refreshHomeSections() {
        refreshHomeBanner()
        refreshHomeNewSection()
        buildHomeSectionsAsync()
    }

    /** BOT: crea la siguiente seccion aleatoria al final de Inicio (max 15),
     *  con loading propio y carga fuera del hilo de UI. */
    private fun maybeCreateRandomSection() {
        if (randomBusy || randomCreated >= 15) return
        if (!::homeSagasAdapter.isInitialized) return
        randomBusy = true
        val spec = randomSpecs[randomCursor % randomSpecs.size]
        randomCursor++
        val idx = randomCreated++

        val ctx = this
        fun dp2(v: Int): Int = (v * resources.displayMetrics.density).toInt()

        // contenedor de la seccion
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        binding.containerRandomSections.addView(box)

        val tv = android.widget.TextView(ctx).apply {
            text = spec.first
            setTextColor(0xFFC9A96E.toInt()); textSize = 13f
            letterSpacing = 0.12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp2(22), 0, 0)
        }
        val pb = android.widget.ProgressBar(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp2(30), dp2(30)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = dp2(10)
            }
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFC9A96E.toInt())
        }
        val rv = androidx.recyclerview.widget.RecyclerView(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp2(10) }
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(ctx, 3)
            isNestedScrollingEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
        }
        box.addView(tv); box.addView(pb); box.addView(rv)

        lifecycleScope.launch {
            val pool = withContext(Dispatchers.Default) {
                allCineMedia
                    .filter { TasteProfile.genreKeysOf(it).contains(spec.second) }
                    .sortedByDescending { it.rating ?: -1.0 }
            }
            if (isFinishing || isDestroyed) return@launch
            pb.visibility = android.view.View.GONE
            if (pool.isEmpty()) {
                // Sin contenido para este genero: la seccion NO se crea
                binding.containerRandomSections.removeView(box)
                binding.containerRandomSections.postDelayed({ randomBusy = false }, 200)
                return@launch
            }
            val adapter = CineSearchResultAdapter(pool.shuffled().take(9), onMediaClick = { openCineDetail(it) })
            adapter.gridColumns = 3
            rv.adapter = adapter
            // botones
            fun pill(label: String, icon: Int, action: () -> Unit): android.widget.LinearLayout =
                android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_apple_glass_pill)
                    setPadding(dp2(18), dp2(10), dp2(18), dp2(10))
                    isClickable = true; isFocusable = true
                    addView(android.widget.ImageView(ctx).apply {
                        setImageResource(icon)
                        setColorFilter(0xFFC9A96E.toInt())
                        layoutParams = android.widget.LinearLayout.LayoutParams(dp2(15), dp2(15))
                    })
                    addView(android.widget.TextView(ctx).apply {
                        text = label
                        setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = dp2(7)
                        }
                    })
                    setOnClickListener { action() }
                }
            val row = android.widget.LinearLayout(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp2(12)
                }
                gravity = android.view.Gravity.CENTER
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(pill("Ver más", R.drawable.ic_ios_arrow_right) {
                    openCineList(spec.first, "genre", spec.second)
                })
                addView(android.view.View(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp2(10), 1)
                })
                addView(pill("Cambiar", R.drawable.ic_ios_refresh) {
                    adapter.updateList(pool.shuffled().take(9))
                })
            }
            box.addView(row)
            // pequeño respiro entre secciones para no sobrecargar
            binding.containerRandomSections.postDelayed({ randomBusy = false }, 350)
        }
    }

    /** Cablea las secciones STREAMING - PLATAFORMAS, EL NUEVO STREAMING DE
     *  HOY, ESTRENOS EN CINE y SAGAS (cuadriculas 3x3 con botones Ver mas y
     *  Cambiar, fila de logos y anuncio en bloque). */
    private fun setupHomeSections() {
        homeStreamingAdapter = CineSearchResultAdapter(emptyList(), onMediaClick = { openCineDetail(it) })
        homeStreamingAdapter.gridColumns = 3
        homeStreamingAdapter.platformBadgeResolver = { m -> PlatformCatalog.logoOf(m) }
        binding.rvHomeStreaming.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)
        binding.rvHomeStreaming.adapter = homeStreamingAdapter
        binding.rvHomeStreaming.isNestedScrollingEnabled = false

        homeEstrenosAdapter = CineSearchResultAdapter(emptyList(), onMediaClick = { openCineDetail(it) })
        homeEstrenosAdapter.gridColumns = 3
        binding.rvHomeEstrenos.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)
        binding.rvHomeEstrenos.adapter = homeEstrenosAdapter
        binding.rvHomeEstrenos.isNestedScrollingEnabled = false

        platformAdapter = PlatformRowAdapter { p ->
            openCineList(p.name.uppercase(), "platform", p.key)
        }
        binding.rvHomePlatforms.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvHomePlatforms.adapter = platformAdapter

        homeSagasAdapter = HomeSagasAdapter { openCineDetail(it) }
        binding.rvHomeSagas.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvHomeSagas.adapter = homeSagasAdapter
        binding.rvHomeSagas.isNestedScrollingEnabled = false

        binding.btnStreamingVerMas.setOnClickListener {
            it.springPress()
            openCineList("EL NUEVO STREAMING DE HOY", "new_month", "", badges = true)
        }
        binding.btnStreamingCambiar.setOnClickListener {
            it.springPress()
            sectionsData?.let { d -> homeStreamingAdapter.updateList(d.streaming.shuffled().take(9)) }
        }
        binding.btnEstrenosVerMas.setOnClickListener {
            it.springPress()
            openCineList("ESTRENOS EN CINE", "recent", "")
        }
        binding.btnEstrenosCambiar.setOnClickListener {
            it.springPress()
            sectionsData?.let { d -> homeEstrenosAdapter.updateList(d.estrenos.shuffled().take(9)) }
        }
        binding.btnSagasVerMas.setOnClickListener {
            it.springPress()
            openCineList("SAGAS", "sagas", "")
        }
        binding.btnSagasCambiar.setOnClickListener {
            it.springPress()
            // Rota a la SIGUIENTE saga de las mejores (orden TMDB)
            sectionsData?.let { d ->
                if (d.sagaGroups.isNotEmpty()) {
                    d.sagaCursor = (d.sagaCursor + 1) % d.sagaGroups.size
                    homeSagasAdapter.submit(
                        d.sagaGroups[d.sagaCursor].second.take(14).map { SagaEntry.Poster(it) }
                    )
                }
            }
        }

        // Anuncio en bloque permanente bajo los botones de Ultimamente Nuevo
        NativeAds.attach(this, binding.adSlotHomeSections, NativeAds.VARIANT_MEDIA)

        // BOT de secciones aleatorias: al llegar cerca del final, crea la
        // siguiente (con su loading) para bajar sin limite
        binding.containerHome.setOnScrollChangeListener { v, _, _, _, _ ->
            @Suppress("USELESS_CAST")
            val sv = v as? android.widget.ScrollView ?: return@setOnScrollChangeListener
            val child = sv.getChildAt(0) ?: return@setOnScrollChangeListener
            if (sv.scrollY + sv.height >= child.height - 700) maybeCreateRandomSection()
        }

        // Logos oficiales de las plataformas (TMDB watch/providers)
        lifecycleScope.launch {
            PlatformCatalog.fetchLogos(CineRepository.TMDB_API_KEY)
            platformAdapter.notifyDataSetChanged()
        }
    }

    /** Construye las secciones de Inicio FUERA del hilo de UI y las pinta
     *  ESCALONADAS (una a una, con loading sin texto) para que el catalogo
     *  grande nunca congele la pantalla. */
    private fun buildHomeSectionsAsync() {
        if (!::homeStreamingAdapter.isInitialized) return
        binding.pbHomeLoader.visibility = View.VISIBLE
        lifecycleScope.launch {
            val data = withContext(Dispatchers.Default) {
                val streaming = allCineMedia
                    .filter { PlatformCatalog.keyOf(it) != null }
                    .sortedByDescending { it.releaseDate ?: "" }
                val estrenos = allCineMedia
                    .filter { ((it.releaseDate ?: "").take(4).toIntOrNull() ?: 0) >= 2024 }
                    .sortedByDescending { it.releaseDate ?: "" }
                val sagaGroups = Sagas.buildGroups(allCineMedia)
                // SOLO la saga TOP (mejor rating TMDB): carteles directos, sin titulo
                val sagasFlat = sagaGroups.firstOrNull()
                    ?.second?.take(14)?.map { SagaEntry.Poster(it) } ?: emptyList()
                HomeSectionsData(streaming, estrenos, sagasFlat, sagaGroups)
            }
            sectionsData = data
            // Carga escalonada: una seccion cada 120 ms
            homeStreamingAdapter.updateList(data.streaming.take(9))
            binding.rvHomeStreaming.postDelayed({
                homeEstrenosAdapter.updateList(data.estrenos.take(9))
                binding.rvHomeSagas.postDelayed({
                    homeSagasAdapter.submit(data.sagas)
                    binding.pbHomeLoader.visibility = View.GONE
                }, 120)
            }, 120)
        }
    }

    private val CINE_PAGE = 60
    private var cinePagingFull: List<CineMedia> = emptyList()
    private var cineShown = 0
    private var cineLoadingMore = false
    private var cineFilterJob: kotlinx.coroutines.Job? = null
    private var bannerRandom = false
    private var bannerRunnable: Runnable? = null
    private var bannerIdx = 0
    private var bannerSize = 0
    private var homeNewPool: List<CineMedia> = emptyList()
    private var homeRandomAdAttached = false
    private lateinit var homeNewAdapter: CineSearchResultAdapter
    private val bannerSlides = mutableListOf<CineMedia>()
    private lateinit var homeStreamingAdapter: CineSearchResultAdapter
    private lateinit var homeEstrenosAdapter: CineSearchResultAdapter
    private lateinit var platformAdapter: PlatformRowAdapter
    private lateinit var homeSagasAdapter: HomeSagasAdapter
    private var sectionsData: HomeSectionsData? = null
    private var randomCreated = 0
    private var randomCursor = 0
    private var randomBusy = false
    private val randomSpecs = listOf(
        Pair("ACCIÓN Y ADRENALINA", "acción"),
        Pair("COMEDIAS PARA REÍR", "comedia"),
        Pair("TERROR DE NOCHE", "terror"),
        Pair("ROMANCE", "romance"),
        Pair("CIENCIA FICCIÓN", "ciencia ficción"),
        Pair("THRILLERS", "thriller"),
        Pair("FANTASÍA ÉPICA", "fantasía"),
        Pair("PARA LA FAMILIA", "familia"),
        Pair("DRAMA REAL", "drama"),
        Pair("MÚSICA Y CONCIERTOS", "música"),
        Pair("ÉPOCA E HISTORIA", "histórica"),
        Pair("ANIMACIÓN", "animación")
    )

    private data class HomeSectionsData(
        val streaming: List<CineMedia>,
        val estrenos: List<CineMedia>,
        val sagas: List<SagaEntry>,
        val sagaGroups: List<Pair<String, List<CineMedia>>>,
        var sagaCursor: Int = 0
    )

    /** Paginacion del grid de Cine: ancha de a [CINE_PAGE] con loading sin
     *  texto, para que catalogos extensos no congelen la UI. */
    private fun cineLoadMore() {
        if (cineLoadingMore || cineShown >= cinePagingFull.size) return
        cineLoadingMore = true
        binding.pbCineLoader.visibility = View.VISIBLE
        binding.rvCineGrid.postDelayed({
            cineShown = minOf(cineShown + CINE_PAGE, cinePagingFull.size)
            cineAdapter.updateList(cinePagingFull.take(cineShown))
            binding.pbCineLoader.visibility = View.GONE
            cineLoadingMore = false
        }, 450)
    }

    /** Configura el banner (rotacion manual cada 4.2s con puntos) y los
     *  botones Ver mas / Cambiar de la seccion Ultimamente Nuevo. */
    private fun setupHomeBanner() {
        binding.flipHomeBanner.inAnimation =
            android.view.animation.AnimationUtils.loadAnimation(this, R.anim.rot_in)
        binding.flipHomeBanner.outAnimation =
            android.view.animation.AnimationUtils.loadAnimation(this, R.anim.rot_out)

        // DESLIZAR con el dedo cambia la diapositiva; un toque abre el titulo
        val detector = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean = true
                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                    val m = bannerSlides.getOrNull(bannerIdx)
                    if (m != null) openCineDetail(m) else shareAppPromo()
                    return true
                }
                override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent,
                                     vx: Float, vy: Float): Boolean {
                    if (kotlin.math.abs(vx) > kotlin.math.abs(vy) && kotlin.math.abs(vx) > 2200) {
                        if (vx < 0) showBannerAt(bannerIdx + 1) else showBannerAt(bannerIdx - 1)
                        return true
                    }
                    return false
                }
            })
        binding.flipHomeBanner.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev); true
        }
        binding.btnHomeVerMas.setOnClickListener {
            it.springPress()
            openCineList("ULTIMAMENTE NUEVO", "new_month", "")
        }
        binding.btnHomeCambiar.setOnClickListener {
            it.springPress()
            bannerRandom = true
            refreshHomeBanner()
            if (::homeNewAdapter.isInitialized) homeNewAdapter.updateList(homeNewPool.shuffled().take(9))
            if (!homeRandomAdAttached) {
                homeRandomAdAttached = true
                NativeAds.attach(this, binding.adSlotHomeRandom, NativeAds.VARIANT_MEDIA)
            }
        }
    }

    /** Seccion Ultimamente Nuevo: cuadricula 3x3 con lo ultimo del catalogo. */
    private fun setupHomeNewSection() {
        homeNewAdapter = CineSearchResultAdapter(emptyList(), onMediaClick = { openCineDetail(it) })
        homeNewAdapter.gridColumns = 3
        binding.rvHomeNew.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)
        binding.rvHomeNew.adapter = homeNewAdapter
        binding.rvHomeNew.isNestedScrollingEnabled = false
    }

    private fun refreshHomeNewSection() {
        if (!::homeNewAdapter.isInitialized) return
        val withPoster = allCineMedia.filter { !it.posterUrl.isNullOrBlank() }
        homeNewPool = if (withPoster.size >= 9) withPoster.sortedByDescending { it.releaseDate ?: "" } else allCineMedia
        if (!bannerRandom) homeNewAdapter.updateList(homeNewPool.take(9))
    }

    /** (Re)construye las 8 diapositivas del banner: 7 titulos del catalogo y
     *  la ultima es publicidad propia de la app. En modo aleatorio muestra
     *  contenido al azar (boton Cambiar). */
    private fun refreshHomeBanner() {
        val flipper = binding.flipHomeBanner
        stopHomeBanner()
        flipper.removeAllViews()
        val withPoster = allCineMedia.filter { !it.posterUrl.isNullOrBlank() }
        val pool = if (bannerRandom) withPoster.shuffled()
        else withPoster.sortedByDescending { it.releaseDate ?: "" }
        val slides = pool.take(7)

        val dotRow = binding.dotsHomeBanner
        dotRow.removeAllViews()
        bannerSize = slides.size + 1
        val dots = (0 until bannerSize).map {
            android.view.View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                    marginStart = dp(5); marginEnd = dp(5)
                }
                setBackgroundResource(R.drawable.bg_banner_dot)
                alpha = 0.35f
            }
        }
        dots.forEach { dotRow.addView(it) }

        bannerSlides.clear()
        bannerSlides.addAll(slides)
        slides.forEach { m ->
            val slide = android.widget.FrameLayout(this)
            // Fondo: el mismo poster llenando y ligeramente atenuado
            slide.addView(android.widget.ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                Glide.with(this@MainActivity).load(m.posterUrl).centerCrop()
                    .placeholder(R.drawable.bg_tile_glass).into(this)
                setColorFilter(0x66000000.toInt(), android.graphics.PorterDuff.Mode.SRC_OVER)
            })
            // EL CARTEL como tarjeta redondeada con sombra: se ve el poster completo
            val card = androidx.cardview.widget.CardView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(150), dp(216))
                    .apply { gravity = android.view.Gravity.CENTER }
                radius = dp(12).toFloat()
                cardElevation = dp(7).toFloat()
                preventCornerOverlap = false
                useCompatPadding = false
            }
            card.addView(android.widget.ImageView(this).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                Glide.with(this@MainActivity).load(m.posterUrl).centerCrop()
                    .placeholder(R.drawable.bg_tile_glass).into(this)
            })
            slide.addView(card)
            flipper.addView(slide)
        }

        // Ultima diapositiva: publicidad propia de la app
        val promo = android.widget.FrameLayout(this)
        promo.setBackgroundResource(R.drawable.bg_ios_gradient)
        val col = android.widget.LinearLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.CENTER
            }
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.icon_lumen_play)
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(46), dp(46))
            })
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "LUMEN IPTV"
                setTextColor(0xFFC9A96E.toInt()); textSize = 19f; letterSpacing = 0.18f
                (layoutParams as? android.widget.LinearLayout.LayoutParams)?.topMargin = dp(8)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
            })
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "Tu reproductor de listas M3U: películas, series y TV en vivo en un solo lugar"
                setTextColor(0xFFD6DAE4.toInt()); textSize = 12.5f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(26), dp(6), dp(26), 0)
            })
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "COMPARTIR"
                setTextColor(0xFF0F2144.toInt()); textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_chip_active)
                setPadding(dp(24), dp(8), dp(24), dp(8))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                }
            })
        }
        promo.addView(col)
        flipper.addView(promo)

        if (slides.isNotEmpty()) {
            bannerIdx = 0
            paintBannerDots()
            armBannerLoop()
        }
    }

    /** Salta a la diapositiva [target] (con envoltura) y reinicia el ciclo. */
    private fun showBannerAt(target: Int) {
        if (bannerSize == 0) return
        val newIdx = (target % bannerSize + bannerSize) % bannerSize
        if (newIdx == bannerIdx) return
        val forward = newIdx == (bannerIdx + 1) % bannerSize
        if (forward) binding.flipHomeBanner.showNext() else binding.flipHomeBanner.showPrevious()
        bannerIdx = newIdx
        paintBannerDots()
        armBannerLoop()
    }

    private fun paintBannerDots() {
        (0 until binding.dotsHomeBanner.childCount).forEach { i ->
            binding.dotsHomeBanner.getChildAt(i).alpha = if (i == bannerIdx) 1f else 0.35f
        }
    }

    private fun armBannerLoop() {
        stopHomeBanner()
        val r = object : Runnable {
            override fun run() {
                bannerIdx = (bannerIdx + 1) % bannerSize
                binding.flipHomeBanner.showNext()
                paintBannerDots()
                bannerRunnable = this
                binding.flipHomeBanner.postDelayed(this, 4200)
            }
        }
        bannerRunnable = r
        binding.flipHomeBanner.postDelayed(r, 4200)
    }

    private fun stopHomeBanner() {
        bannerRunnable?.let { binding.flipHomeBanner.removeCallbacks(it) }
        bannerRunnable = null
    }

    private fun resumeHomeBanner() {
        if (bannerRunnable == null && binding.flipHomeBanner.childCount > 1) armBannerLoop()
    }

    /** Publicidad propia de la app: comparte el enlace de la tienda. */
    private fun shareAppPromo() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    android.content.Intent.EXTRA_TEXT,
                    "Lumen IPTV: reproductor de listas M3U con películas, series y TV en vivo. " +
                        "https://play.google.com/store/apps/details?id=$packageName"
                )
            }
            startActivity(android.content.Intent.createChooser(intent, "Comparte la app"))
        } catch (_: Exception) {}
    }

    private fun setupListeners() {
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

        // Settings: cada opción abre su propia pantalla
        binding.btnSettingsIptv.springPress()
        binding.btnSettingsParental.springPress()
        binding.btnSettingsTheme.springPress()
        binding.btnSettingsAccent.springPress()
        binding.btnSettingsHistory.springPress()
        binding.btnSettingsClearCache.springPress()
        binding.btnSettingsStats.springPress()

        binding.btnSettingsIptv.setOnClickListener {
            startActivity(Intent(this, IptvListActivity::class.java))
        }
        binding.btnSettingsParental.setOnClickListener {
            startActivity(Intent(this, ParentalControlActivity::class.java))
        }
        binding.btnSettingsTheme.setOnClickListener {
            startActivity(Intent(this, ThemeActivity::class.java))
        }
        binding.btnSettingsAccent.setOnClickListener {
            startActivity(Intent(this, AccentColorActivity::class.java))
        }
        binding.btnSettingsHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.btnSettingsClearCache.setOnClickListener {
            startActivity(Intent(this, StorageActivity::class.java))
        }
        binding.btnSettingsStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
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

        // Barra de Inicio: abre el BUSCADOR DE CINE (peliculas/series; nada de
        // canales). El reloj abre el historial de lo visto.
        binding.homeSearchBar.setOnClickListener {
            startActivity(android.content.Intent(this, CineSearchActivity::class.java))
        }
        binding.btnWatchHistory.setOnClickListener {
            startActivity(android.content.Intent(this, WatchHistoryActivity::class.java))
        }

        // Secciones de Inicio: cada chip abre su pantalla CON LA INTERFAZ DEL
        // INICIO (buscador + cuadricula 3x3 con loading), solo de esa seccion
        fun openSection(title: String, kind: String, param: String, badges: Boolean = false) {
            startActivity(android.content.Intent(this, CineSectionActivity::class.java).apply {
                putExtra("title", title)
                putExtra("kind", kind)
                putExtra("param", param)
                putExtra("show_platform_badges", badges)
            })
        }
        binding.chipHomePeliculas.setOnClickListener {
            openSection("Películas", "type", "movie")
        }
        binding.chipHomeSeries.setOnClickListener {
            openSection("Series", "type", "series")
        }
        binding.chipHomeAnimacion.setOnClickListener {
            openSection("Animación", "genre", "animación")
        }
        binding.chipHomePlataformas.setOnClickListener {
            openSection("Plataformas", "platform_all", "", badges = true)
        }
        startHomeSearchRotator()


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
            channelsAdapter.gridColumns = 3
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
            channelsAdapter.gridColumns = 1
            binding.btnToggleLayout.setImageResource(R.drawable.ic_ios_list)
            channelsAdapter.tunerMode = true
            layoutManager.requestLayout()
            Toast.makeText(this, "Vista Lista activa", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.btnViewOptionsClose).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

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
        refreshHomeSections()
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


    /** Vacía la lista cargada (lo usa la pantalla de Almacenamiento / Limpiar). */
    private fun clearPlaylist() {
        val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        sharedPref.edit().remove("last_url").apply()
        allChannels = emptyList()
        categories = emptyList()
        countries = emptyList()
        languages = emptyList()
        updateEmptyStates()
        Toast.makeText(this, "Lista eliminada del historial", Toast.LENGTH_SHORT).show()
    }

    private fun loadIptvList(urlString: String, isAutoRestore: Boolean = false) {
        hideKeyboard()

        // Show global progress
        binding.globalProgressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                fetchAndParseM3u(urlString)
            }

            binding.globalProgressBar.visibility = View.GONE

            if (result != null) {
                allChannels = result
                
                if (allChannels.isNotEmpty()) {
                    // Save URL to persistent storage since it loaded successfully!
                    val sharedPref = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
                    sharedPref.edit().putString("last_url", urlString).apply()

                    // Add to URL load history!
                    addSearchQuery(PLAYLIST_HISTORY_KEY, urlString)

                    // 1. Parse and extract unique categories from list
                    val parsedCategories = mutableListOf("Todos")
                    val extractedGroups = allChannels.mapNotNull { it.group }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .sorted()
                    parsedCategories.addAll(extractedGroups)
                    categories = parsedCategories

                    // Guardar categorías para la pantalla de control parental.
                    sharedPref.edit().putStringSet("categories_list", categories.toSet()).apply()

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

    private fun openPlayer(channel: Channel) {
        // Anuncio recompensado obligatorio: solo se reproduce si se ve completo.
        RewardGate.requireAdThen(this) { launchPlayerActivity(channel) }
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

    private var pendingDeepLink: String? = null

    private fun loadCineCatalog() {
        lifecycleScope.launch {
            // FAST PASS: catalogo empaquetado sin red -> contenido visible YA.
            // El loading verde SOLO aparece si no hay nada que mostrar todavia.
            val quick = withContext(Dispatchers.IO) {
                CineRepository.getBundledQuickCatalog(this@MainActivity)
            }
            if (allCineMedia.isEmpty() && quick.isNotEmpty()) {
                allCineMedia = quick
                applyCineFilters()
                refreshHomeSections()
            }

            val catalog = CineRepository.getCineCatalog(this@MainActivity)
            allCineMedia = catalog
            // Pre-calienta el cache de generos (fuera del hilo de UI) ANTES de
            // armar secciones: asi ni el primer toque en PELICULAS/SERIES congela.
            withContext(Dispatchers.Default) {
                try { catalog.forEach { TasteProfile.genreKeysOf(it) } } catch (_: Exception) {}
            }
            refreshHomeSections()
            setupCineFeatured()
            CineNewNotifier.onCatalogLoaded(this@MainActivity, catalog)
            consumePendingDeepLink()
            
            binding.layoutCineLoading.visibility = View.GONE
            binding.rvCineGrid.visibility = View.VISIBLE
            
            // PAGINADO (60 + loading): jamas volcar 9.5k de golpe en el grid
            cinePagingFull = catalog.sortedByDescending { it.rating ?: -1.0 }
            cineShown = minOf(CINE_PAGE, cinePagingFull.size)
            cineLoadingMore = false
            cineAdapter.updateList(cinePagingFull.take(cineShown))
            binding.txtCineCount.text = "Total: ${cinePagingFull.size}"
            buildCineReco()
        }
    }

    private fun applyCineFilters() {
        val query = binding.edtCineSearch.text.toString().trim().lowercase()
        cineFilterJob?.cancel()
        cineFilterJob = lifecycleScope.launch {
        val finalCineList = withContext(Dispatchers.Default) {
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
            val ordered = if (selectedCineType == "new") {
                filtered.sortedByDescending { it.releaseDate ?: "" }
            } else if (selectedCineType == "all" && cineMood == null && query.isEmpty()) {
                // orden casa: lo mejor segun TMDB primero, sin tocar el resto
                filtered.sortedByDescending { it.rating ?: -1.0 }
            } else filtered
            ordered
        }
        cinePagingFull = finalCineList
        cineShown = minOf(CINE_PAGE, cinePagingFull.size)
        cineLoadingMore = false
        cineAdapter.updateList(cinePagingFull.take(cineShown))
        binding.txtCineCount.text = if (selectedCineType == "new") "Novedades: ${cinePagingFull.size}" else "Total: ${cinePagingFull.size}"

        // Trigger the Spiderman overlay if they search for Spiderman
        checkAndShowSpidermanEasterEgg(query)
        }
    }

    private fun updateCineFilterButtons() {
        val orangeColor = AccentManager.color(this)
        val grayColor = android.graphics.Color.parseColor("#0F2144")
        
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
        binding.btnMoodEstrenos.setBackgroundResource(if (cineMood == "estrenos") R.drawable.bg_chip_active else R.drawable.bg_chip_idle)
        binding.btnMoodEstrenos.setTextColor(if (cineMood == "estrenos") activeText else grayText)
        binding.btnMoodTop.setBackgroundResource(if (cineMood == "top") R.drawable.bg_chip_active else R.drawable.bg_chip_idle)
        binding.btnMoodTop.setTextColor(if (cineMood == "top") activeText else grayText)
    }

    private fun updateParaTiChips() {
        val activeText = android.graphics.Color.parseColor("#0F2144")
        val grayText = android.graphics.Color.parseColor("#98989F")
        fun paint(b: android.widget.TextView, active: Boolean) {
            b.setBackgroundResource(if (active) R.drawable.bg_chip_active else R.drawable.bg_chip_idle)
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
            reco = premierSource()
                .filter { it.group == seed.media.group && it.title != seed.title }
                .filter { !it.posterUrl.isNullOrBlank() }
                .sortedByDescending { it.rating ?: 0.0 }
                .take(12)
            binding.txtCineRecoLabel.text = "PORQUE VISTE ${seed.title.uppercase()}"
        } else {
            reco = premierSource()
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
                if (added) flyOutAndAdvance(1)
        }
        binding.txtCineCatalogToggle.setOnClickListener { setDeckMode(!deckMode) }
        binding.catSegAll.setOnClickListener { selectedCineType = "all"; updateCatalogSegments(); applyCineFilters() }
        binding.catSegMovies.setOnClickListener { selectedCineType = "movie"; updateCatalogSegments(); applyCineFilters() }
        binding.catSegSeries.setOnClickListener { selectedCineType = "series"; updateCatalogSegments(); applyCineFilters() }
        renderDeck()
    }

    private fun updateCatalogSegments() {
        val activeText = android.graphics.Color.parseColor("#0F2144")
        val grayText = android.graphics.Color.parseColor("#98989F")
        fun seg(v: android.widget.TextView, active: Boolean) {
            v.setBackgroundResource(if (active) R.drawable.bg_chip_active else android.R.color.transparent)
            v.setTextColor(if (active) activeText else grayText)
        }
        seg(binding.catSegAll, selectedCineType == "all")
        seg(binding.catSegMovies, selectedCineType == "movie")
        seg(binding.catSegSeries, selectedCineType == "series")
    }

    private fun transformCoverflow(rv: androidx.recyclerview.widget.RecyclerView) {
        val mid = rv.width / 2f
        if (mid <= 0) return
        for (i in 0 until rv.childCount) {
            val v = rv.getChildAt(i)
            val c = (v.left + v.right) / 2f
            val d = ((mid - c) / mid).coerceIn(-1f, 1f)
            val a = kotlin.math.abs(d)
            v.scaleX = 1f - 0.18f * a
            v.scaleY = 1f - 0.18f * a
            v.rotationY = if (d < 0) 14f * a else -14f * a
            v.alpha = 1f - 0.35f * a
            v.translationZ = (1f - a) * 10f
            v.findViewById<View>(R.id.coverInfo)?.alpha = (1f - 1.7f * a).coerceIn(0f, 1f)
        }
    }

    private fun runCineHeadline(container: android.widget.LinearLayout) {
        container.removeAllViews()
        val text = "GRANDES ESTRENOS"
        text.forEachIndexed { i, ch ->
            val tv = android.widget.TextView(this).apply {
                this.text = if (ch == ' ') "\u00A0" else ch.toString()
                textSize = 26f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.WHITE)
                letterSpacing = 0.05f
                alpha = 0f
                translationX = 18f
            }
            container.addView(tv)
            tv.post {
                tv.animate().alpha(1f).translationX(0f)
                    .setStartDelay(55L * i)
                    .setDuration(300)
                    .start()
            }
        }
    }

    private fun setupCineFeatured() {
        if (allCineMedia.isEmpty()) return
        val posters = premierSource()
        if (posters.isEmpty()) return
        binding.rvCineCoverflow.adapter = CineCoverAdapter(
            posters.sortedByDescending { it.rating ?: 0.0 }.take(10)
        ) { openCineDetail(it) }
        binding.rvCinePopular.adapter = CinePopularAdapter(
            posters.sortedBy { kotlin.math.abs(it.title.hashCode()) }.take(15)
        ) { openCineDetail(it) }
        runCineHeadline(binding.cineHeadlineLtrs)
        binding.rvCineCoverflow.setItemViewCacheSize(20)
        binding.rvCinePopular.setItemViewCacheSize(20)
        binding.rvCineReco.setItemViewCacheSize(12)
        binding.rvCineCoverflow.post {
            transformCoverflow(binding.rvCineCoverflow)
            updatePlatformLogoFor(0)
        }
        buildCineSections()
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
        binding.cineCatalogDock.visibility = if (on) View.GONE else View.VISIBLE
        binding.cineFeaturedBlock.visibility = if (on) View.VISIBLE else View.GONE
        binding.layoutCinePopular.visibility = if (on) View.VISIBLE else View.GONE
        binding.layoutCineLoading.visibility = if (on) View.GONE else binding.layoutCineLoading.visibility
        if (on) {
            binding.rvCineReco.visibility = View.GONE
            binding.txtCineRecoLabel.visibility = View.GONE
            refreshDeck()
        } else {
            updateCatalogSegments()
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

    private var deckTracker: android.view.VelocityTracker? = null
    private var coverflowSnap: androidx.recyclerview.widget.LinearSnapHelper? = null

    /** Abre la ficha del titulo compartido (deep link) una vez que el
     *  catalogo esta en memoria; luego limpia el intent para que el
     *  deep link no se re-dispare al rotar o volver del background. */
    private fun consumePendingDeepLink() {
        val t = pendingDeepLink ?: return
        pendingDeepLink = null
        try {
            intent.removeExtra("deeplink_title")
            intent.data = null
        } catch (_: Exception) {}
        if (t.isBlank()) return
        val media = allCineMedia.firstOrNull {
            it.title.equals(t, ignoreCase = true) || it.searchTitle.equals(t, ignoreCase = true)
        } ?: run {
            // Busqueda tolerante: contiene
            allCineMedia.firstOrNull {
                it.title.contains(t, ignoreCase = true) || it.searchTitle.contains(t, ignoreCase = true)
            }
        }
        try { binding.bottomNavigation.selectedItemId = R.id.navigation_cine } catch (_: Exception) {}
        if (media == null) {
            SmartTips.showOnce(this, "deeplink_miss_$t", "💡 \"$t\" no está en el catálogo: usa el buscador")
            return
        }
        try {
            val dest = if (media.type == "movie") CineMovieDetailActivity::class.java
                       else CineTvShowDetailActivity::class.java
            startActivity(android.content.Intent(this, dest).apply { putExtra("media", media) })
        } catch (_: Exception) {}
    }

    private fun updateLumenNav(activeId: Int) {
        fun style(btn: View, icon: ImageView, label: TextView, id: Int) {
            val on = id == activeId
            btn.setBackgroundResource(if (on) R.drawable.bg_apple_dock_active else android.R.color.transparent)
            icon.setColorFilter(android.graphics.Color.parseColor(if (on) "#FFFFFF" else "#8A8A93"))
            label.setTextColor(android.graphics.Color.parseColor(if (on) "#FFFFFF" else "#6E6E76"))
        }
        style(binding.navBtnHome, binding.imgNavHome, binding.txtNavHome, R.id.navigation_home)
        style(binding.navBtnChannels, binding.imgNavCh, binding.txtNavChannels, R.id.navigation_channels)
        style(binding.navBtnSearch, binding.imgNavSearch, binding.txtNavSearch, R.id.navigation_search)
        style(binding.navBtnCine, binding.imgNavCine, binding.txtNavCine, R.id.navigation_cine)
        style(binding.navBtnSettings, binding.imgNavSettings, binding.txtNavSettings, R.id.navigation_settings)
    }

    private fun platformIconOf(label: String): Int = when {
        label.contains("NETFLIX") -> R.drawable.ic_brand_netflix
        label.contains("DISNEY") -> R.drawable.ic_brand_disney
        label.contains("MAX") -> R.drawable.ic_brand_max
        label.contains("PRIME") -> R.drawable.ic_brand_prime
        label.contains("APPLE") -> R.drawable.ic_brand_apple
        label.contains("HULU") -> R.drawable.ic_brand_hulu
        label.contains("PARAMOUNT") -> R.drawable.ic_brand_paramount
        label.contains("CRUNCHYROLL") -> R.drawable.ic_brand_crunchyroll
        label.contains("TUBI") -> R.drawable.ic_brand_tubi
        label.contains("PEACOCK") -> R.drawable.ic_brand_peacock
        else -> R.drawable.icon_lumen_play
    }

    private var premierKind = "all"

    private fun premierSource(): List<CineMedia> =
        if (premierKind == "all") allCineMedia
        else allCineMedia.filter { it.type == premierKind }

    private fun updateKindChips() {
        fun setActive(v: android.widget.TextView, on: Boolean) {
            v.setBackgroundResource(if (on) R.drawable.bg_chip_active else R.drawable.bg_chip_idle)
            v.setTextColor(android.graphics.Color.parseColor(if (on) "#0F2144" else "#C9A96E"))
        }
        setActive(binding.chipKindMovies, premierKind == "movie")
        setActive(binding.chipKindSeries, premierKind == "series")
    }

    private fun switchPremierKind(kind: String) {
        premierKind = if (premierKind == kind) "all" else kind
        updateKindChips()
        // setupCineFeatured() ya llama a buildCineSections() internamente:
        // antes se llamaba DOS veces por toque y congelaba la pestaña Cine.
        setupCineFeatured()
        buildCineReco()
    }

    private val platformsInFlight = mutableSetOf<String>()

    private fun brandFromText(text: String): String? {
        val g = text.lowercase()
        return when {
            g.contains("netflix") -> "NETFLIX"
            g.contains("disney") -> "DISNEY+"
            g.contains("hbo") || (g.contains("max") && !g.contains("maxim") && !g.contains("cinemax")) -> "MAX"
            g.contains("prime") || g.contains("amazon") -> "PRIME VIDEO"
            g.contains("apple") -> "APPLE TV+"
            g.contains("hulu") -> "HULU"
            g.contains("paramount") -> "PARAMOUNT+"
            g.contains("crunchy") -> "CRUNCHYROLL"
            g.contains("tubi") -> "TUBI"
            g.contains("peacock") -> "PEACOCK"
            else -> null
        }
    }

    private fun platformLabelOf(m: CineMedia): String {
        brandFromText(m.group)?.let { return it }
        brandFromText(m.title)?.let { return it }
        m.platformName?.let { return it }
        // Pedir a TMDB el streaming real (una vez por titulo) y repintar al llegar
        if (m.tmdbId != null && platformsInFlight.add(m.url)) {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try { CineRepository.fetchWatchProviders(m) } catch (_: Exception) { }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    currentCoverflowPos?.let { updatePlatformLogoFor(it) }
                }
            }
        }
        return android.net.Uri.parse(m.url).host?.substringBefore('.')?.uppercase() ?: "LUMEN"
    }

    private var currentCoverflowPos: Int? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildCineSections() {
        val container = binding.layoutCineSections
        container.removeAllViews()
        // Calcula los generos UNA sola vez por titulo (antes se recomputaba
        // 5x por cada categoria -> era lo que frenaba la pestaña Cine).
        val annotated = premierSource().map { it to TasteProfile.genreKeysOf(it).toSet() }
        listOf(
            Triple("ACCIÓN", "accion", false),
            Triple("COMEDIA", "comedia", false),
            Triple("ROMANCE", "romance", false),
            Triple("TERROR", "terror", false),
            Triple("ANIMÉ", "anime", true)
        ).forEach { (title, key, isAnime) ->
            val items = if (isAnime) {
                annotated.filter { (m, _) ->
                    m.title.lowercase().contains("anime") || m.group.lowercase().contains("anime")
                }.map { it.first }
            } else {
                annotated.filter { (_, genres) -> genres.contains(key) }.map { it.first }
            }
            if (items.size < 3) return@forEach
            val sorted = items.sortedBy { Math.abs(it.title.hashCode()) }.take(12)

            val header = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(18) }
            }
            val titleV = android.widget.TextView(this).apply {
                text = title
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val seeV = android.widget.TextView(this).apply {
                text = "See all ›"
                setTextColor(android.graphics.Color.parseColor("#98989F"))
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setOnClickListener { openCineList(title, "genre", key) }
            }
            header.addView(titleV)
            header.addView(seeV)
            container.addView(header)

            val rv = androidx.recyclerview.widget.RecyclerView(this).apply {
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(212)
                ).apply { topMargin = dp(10) }
                clipToPadding = false
                clipChildren = false
                setPadding(dp(20), 0, dp(20), 0)
                adapter = CinePopularAdapter(sorted) { openCineDetail(it) }
            }
            container.addView(rv)
        }
    }

    private fun setupCineMenu() {
        binding.premierMenu.setOnClickListener { openCineMenu() }
        binding.imgIslandSearch.setOnClickListener {
            startActivity(Intent(this, CineSearchActivity::class.java))
        }
        binding.cineMenuScrim.setOnClickListener { closeCineMenu() }
        binding.btnMenuClose.setOnClickListener { closeCineMenu() }

        binding.menuItemFav.setOnClickListener { openCineList("FAVORITOS", "favorites") }
        binding.menuItemContinue.setOnClickListener { openCineList("SEGUIR VIENDO", "continue") }
        binding.menuItemWallpapers.setOnClickListener {
            startActivity(android.content.Intent(this, CineWallpapersActivity::class.java))
        }
        binding.menuItemRecent.setOnClickListener { openCineList("ESTRENOS", "recent") }
        binding.menuItemAlerts.setOnClickListener { openCineList("ALERTAS", "alerts") }
        binding.menuItemHistory.setOnClickListener { openCineList("HISTORIAL", "history") }
        binding.menuItemSettings.setOnClickListener {
            closeCineMenu()
            binding.bottomNavigation.selectedItemId = R.id.navigation_settings
        }

        // Grupos expandibles: generos y plataformas
        fun toggle(v: android.widget.LinearLayout) {
            v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.menuItemGenres.setOnClickListener { toggle(binding.menuGenresChips) }
        binding.menuItemPlatforms.setOnClickListener { toggle(binding.menuPlatformsChips) }

        binding.chipGenreAc.setOnClickListener { openCineList("ACCION", "genre", "accion") }
        binding.chipGenreCo.setOnClickListener { openCineList("COMEDIA", "genre", "comedia") }
        binding.chipGenreRo.setOnClickListener { openCineList("ROMANCE", "genre", "romance") }
        binding.chipGenreAn.setOnClickListener { openCineList("ANIME", "genre", "anime") }
        binding.chipPlatNetflix.setOnClickListener { openCineList("NETFLIX", "platform", "netflix") }
        binding.chipPlatDisney.setOnClickListener { openCineList("DISNEY+", "platform", "disney") }
        binding.chipPlatMax.setOnClickListener { openCineList("MAX", "platform", "hbo") }
        binding.chipPlatPrime.setOnClickListener { openCineList("PRIME VIDEO", "platform", "prime") }
        binding.chipGenreTe.setOnClickListener { openCineList("TERROR", "genre", "terror") }
        binding.chipGenreDr.setOnClickListener { openCineList("DRAMA", "genre", "drama") }
        binding.chipPlatApple.setOnClickListener { openCineList("APPLE TV+", "platform", "apple") }
        binding.chipPlatHulu.setOnClickListener { openCineList("HULU", "platform", "hulu") }

        binding.chipKindMovies.setOnClickListener { switchPremierKind("movie") }
        binding.chipKindSeries.setOnClickListener { switchPremierKind("series") }
        updateKindChips()
    }

    private fun openCineMenu() {
        binding.cineMenuOverlay.visibility = View.VISIBLE
        val w = -(binding.cineMenuPanel.width.takeIf { it > 0 } ?: (300 * resources.displayMetrics.density).toInt()).toFloat()
        binding.cineMenuPanel.translationX = w
        binding.cineMenuPanel.animate().translationX(0f).setDuration(240).start()
    }

    private fun closeCineMenu() {
        val w = -(binding.cineMenuPanel.width.takeIf { it > 0 } ?: (300 * resources.displayMetrics.density).toInt()).toFloat()
        binding.cineMenuPanel.animate().translationX(w).setDuration(200)
            .withEndAction { binding.cineMenuOverlay.visibility = View.GONE }
            .start()
    }

    private fun openCineList(title: String, kind: String, param: String = "", badges: Boolean = false) {
        closeCineMenu()
        startActivity(Intent(this, CinePopularAllActivity::class.java).apply {
            putExtra("title", title)
            putExtra("kind", kind)
            putExtra("param", param)
            putExtra("show_platform_badges", badges)
        })
    }

    private fun updatePlatformLogoFor(pos: Int) {
        val a = binding.rvCineCoverflow.adapter as? CineCoverAdapter ?: return
        val m = a.itemAt(pos) ?: return
        val label = platformLabelOf(m)
        if (!m.platformLogoUrl.isNullOrBlank()) {
            binding.imgPlatformLogo.setImageResource(android.R.color.transparent)
            Glide.with(binding.imgPlatformLogo)
                .load(m.platformLogoUrl)
                .transform(com.bumptech.glide.load.resource.bitmap.RoundedCorners(
                    (9 * resources.displayMetrics.density).toInt()
                ))
                .into(binding.imgPlatformLogo)
        } else {
            binding.imgPlatformLogo.setImageResource(platformIconOf(label))
        }
    }

    private fun onDeckTouch(v: View, ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                deckDownX = ev.x
                deckTracker = android.view.VelocityTracker.obtain()
                deckTracker?.addMovement(ev)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                deckTracker?.addMovement(ev)
                val dx = ev.x - deckDownX
                v.translationX = dx
                v.rotation = dx / 22f
                // la carta de atras sube gradualmente mientras arrastras (vivo)
                val pr = (kotlin.math.abs(dx) / 160f).coerceIn(0f, 1f)
                binding.deckCardBack.root.rotation = -4f + 4f * pr
                binding.deckCardBack.root.scaleX = 0.94f + 0.06f * pr
                binding.deckCardBack.root.scaleY = 0.94f + 0.06f * pr
                binding.deckCardBack.root.alpha = 0.6f + 0.4f * pr
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                val dx = ev.x - deckDownX
                deckTracker?.computeCurrentVelocity(1000)
                val vx = deckTracker?.xVelocity ?: 0f
                deckTracker?.recycle()
                deckTracker = null
                if (kotlin.math.abs(dx) > 150f || kotlin.math.abs(vx) > 1200f) {
                    flyOutAndAdvance(if ((dx + vx * 0.08f) > 0) 1 else -1)
                } else {
                    v.animate().translationX(0f).rotation(0f)
                        .setDuration(340)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                        .start()
                    binding.deckCardBack.root.animate().rotation(-4f).scaleX(0.94f).scaleY(0.94f).alpha(0.6f)
                        .setDuration(240).start()
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
            .setDuration(210)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
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

    private fun setupNativeAds() {
        // Inicio: tarjeta inline (ya está en el layout)
        // Canales y Buscador: bloque compacto
        NativeAds.attach(this, binding.adSlotChannels, NativeAds.VARIANT_COMPACT)
        NativeAds.attach(this, binding.adSlotSearch, NativeAds.VARIANT_COMPACT)
        NativeAds.attach(this, binding.adSlotSettings, NativeAds.VARIANT_COMPACT)
        // Cine: bloque con vídeo/imagen
        NativeAds.attach(this, binding.adSlotCine, NativeAds.VARIANT_MEDIA)
    }

    private var homeRotatorRunnable: Runnable? = null

    /** Placeholder rotativo del buscador de Inicio: muestra las busquedas
     *  pasadas una a una (3s, transicion vertical). Se reconstruye en cada
     *  onResume para reflejar las busquedas nuevas. Con una sola busqueda la
     *  muestra FIJA (sin transicion que repita el mismo texto). */
    private fun startHomeSearchRotator() {
        homeRotatorRunnable?.let { binding.homeSearchRotator.removeCallbacks(it) }
        homeRotatorRunnable = null
        val suggestions = LinkedHashSet<String>().apply {
            addAll(getSearchHistory(CINE_HISTORY_KEY))
            addAll(getSearchHistory(CHANNELS_HISTORY_KEY))
        }.toList().filter { it.isNotBlank() }.take(8)
        val switcher = binding.homeSearchRotator
        when {
            suggestions.isEmpty() ->
                switcher.setText("Busca películas, series y canales…")
            suggestions.size == 1 ->
                switcher.setText(suggestions[0])
            else -> {
                switcher.setText(suggestions[0])
                var idx = 0
                val r = object : Runnable {
                    override fun run() {
                        idx = (idx + 1) % suggestions.size
                        switcher.setText(suggestions[idx])
                        switcher.postDelayed(this, 3000)
                    }
                }
                homeRotatorRunnable = r
                switcher.postDelayed(r, 3000)
            }
        }
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
        if (current.size > 10) { // Keep last 10 recent searches
            current.removeAt(current.size - 1)
        }
        saveSearchHistory(key, current)
    }

    private lateinit var channelsHistoryAdapter: SearchHistoryAdapter
    private lateinit var cineHistoryAdapter: SearchHistoryAdapter
    
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
        val bgColor = if (isDark) android.graphics.Color.parseColor("#0A1834") else android.graphics.Color.parseColor("#F5F5F5")
        val cardColor = if (isDark) android.graphics.Color.parseColor("#14294F") else android.graphics.Color.parseColor("#FFFFFF")
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
        val cardColor = if (isDark) android.graphics.Color.parseColor("#14294F") else android.graphics.Color.parseColor("#FFFFFF")
        
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
            view.setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#14294F") else android.graphics.Color.parseColor("#E0E0E0"))
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

    override fun onPause() {
        super.onPause()
        homeRotatorRunnable?.let { binding.homeSearchRotator.removeCallbacks(it) }
        stopHomeBanner()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

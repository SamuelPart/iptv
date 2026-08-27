package com.samuelpart.iptvplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.samuelpart.iptvplayer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.ArrayList

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    
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
        setupHomeHubTiles()
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

    /** Home hub: big tiles jump straight to each tab. */
    private fun setupHomeHubTiles() {
        binding.tileChannels.setOnClickListener { binding.bottomNavigation.selectedItemId = R.id.navigation_channels }
        binding.tileCine.setOnClickListener { binding.bottomNavigation.selectedItemId = R.id.navigation_cine }
        binding.tileSearch.setOnClickListener { binding.bottomNavigation.selectedItemId = R.id.navigation_search }
        binding.tileSettings.setOnClickListener { binding.bottomNavigation.selectedItemId = R.id.navigation_settings }
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
        // 1. Setup Channels Grid in Canales tab (3 columns by default)
        binding.rvChannelsGrid.layoutManager = GridLayoutManager(this, 3)
        channelsAdapter = ChannelAdapter(emptyList()) { channel ->
            openPlayer(channel)
        }
        binding.rvChannelsGrid.adapter = channelsAdapter

        // 2. Setup Search Grid in Buscador tab (3 columns)
        binding.rvSearchGrid.layoutManager = GridLayoutManager(this, 3)
        searchAdapter = ChannelAdapter(emptyList()) { channel ->
            openPlayer(channel)
        }
        binding.rvSearchGrid.adapter = searchAdapter

        // 3. Setup Cine Grid in Cine tab (2 columns for high-end poster aspect ratio)
        binding.rvCineGrid.layoutManager = GridLayoutManager(this, 2)
        cineAdapter = CineMediaAdapter(emptyList()) { media ->
            openCineDetail(media)
        }
        binding.rvCineGrid.adapter = cineAdapter
    }

    private fun setupListeners() {
        // Load button click
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

        // Settings: Parental Control click listener
        binding.btnSettingsParental.setOnClickListener {
            showParentalSettingsDialog()
        }

        // Settings: App Theme (Claro / Oscuro / Sistema) Selector click listener
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

            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
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
    }

    private fun showLayoutToggleDialog() {
        val options = arrayOf(
            "Vista Cuadrícula 🎛️ (3 Columnas)",
            "Vista Lista ☰ (1 Columna)"
        )

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Opciones de Vista")
            .setItems(options) { _, which ->
                val layoutManager = binding.rvChannelsGrid.layoutManager as GridLayoutManager
                when (which) {
                    0 -> {
                        isGridView = true
                        layoutManager.spanCount = 3
                        binding.btnToggleLayout.setImageResource(android.R.drawable.ic_dialog_dialer)
                        Toast.makeText(this, "Vista Cuadrícula activa", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        isGridView = false
                        layoutManager.spanCount = 1
                        binding.btnToggleLayout.setImageResource(android.R.drawable.ic_menu_sort_by_size)
                        Toast.makeText(this, "Vista Lista activa", Toast.LENGTH_SHORT).show()
                    }
                }
                layoutManager.requestLayout()
            }
            .show()
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
            "Limpiar caché de lista IPTV 🧹"
        )

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
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

            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
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

            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
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

            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
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

    private fun showFilterDialog() {
        val options = arrayOf(
            "Filtrar por País 🗺️",
            "Filtrar por Idioma 🗣️",
            "Filtrar por Categoría 📺",
            "Ordenar por Alfabeto 🔤",
            "Restablecer Filtros 🔄"
        )

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Opciones de Filtrado")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCountryFilterSelector()
                    1 -> showLanguageFilterSelector()
                    2 -> showCategoryFilterSelector()
                    3 -> showAlphabetSortSelector()
                    4 -> resetAllFilters()
                }
            }
            .show()
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

        dialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
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
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
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
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Seleccionar Categoría")
            .setItems(items) { _, which ->
                selectedCategory = items[which]
                applyFiltersAndSorting()
            }
            .show()
    }

    private fun showAlphabetSortSelector() {
        val options = arrayOf("Sin Ordenar 🔄", "A-Z (Ascendente) 🔼", "Z-A (Descendente) 🔽")
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
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

    private fun showSearchFilterDialog() {
        val options = arrayOf(
            "Filtrar por País 🗺️",
            "Restablecer Filtro de País 🔄"
        )

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Opciones de Búsqueda")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSearchCountryFilterSelector()
                    1 -> {
                        selectedSearchCountry = "Todos"
                        filterSearchTabUnified()
                        Toast.makeText(this, "Filtro de búsqueda restablecido", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
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

        dialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
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
        val builder = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
        
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
            
            binding.layoutCineLoading.visibility = View.GONE
            binding.rvCineGrid.visibility = View.VISIBLE
            
            cineAdapter.updateList(catalog)
            binding.txtCineCount.text = "Total: ${catalog.size}"
        }
    }

    private fun applyCineFilters() {
        val query = binding.edtCineSearch.text.toString().trim().lowercase()
        val filtered = allCineMedia.filter {
            val matchesType = when (selectedCineType) {
                "movie" -> it.type == "movie"
                "series" -> it.type == "series"
                else -> true
            }
            val matchesQuery = query.isEmpty() || it.title.lowercase().contains(query)
            matchesType && matchesQuery
        }
        cineAdapter.updateList(filtered)
        binding.txtCineCount.text = "Total: ${filtered.size}"
        
        // Trigger the Spiderman overlay if they search for Spiderman
        checkAndShowSpidermanEasterEgg(query)
    }

    private fun updateCineFilterButtons() {
        val orangeColor = android.graphics.Color.parseColor("#0A84FF")
        val grayColor = android.graphics.Color.parseColor("#1E1E1E")
        
        binding.btnCineFilterAll.setBackgroundColor(if (selectedCineType == "all") orangeColor else grayColor)
        binding.btnCineFilterAll.setTextColor(if (selectedCineType == "all") android.graphics.Color.WHITE else android.graphics.Color.parseColor("#CCCCCC"))
        
        binding.btnCineFilterMovies.setBackgroundColor(if (selectedCineType == "movie") orangeColor else grayColor)
        binding.btnCineFilterMovies.setTextColor(if (selectedCineType == "movie") android.graphics.Color.WHITE else android.graphics.Color.parseColor("#CCCCCC"))
        
        binding.btnCineFilterSeries.setBackgroundColor(if (selectedCineType == "series") orangeColor else grayColor)
        binding.btnCineFilterSeries.setTextColor(if (selectedCineType == "series") android.graphics.Color.WHITE else android.graphics.Color.parseColor("#CCCCCC"))
    }

    private fun openCineDetail(media: CineMedia) {
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

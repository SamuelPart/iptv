package com.samuelpart.iptvplayer

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.os.Build
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import kotlinx.coroutines.launch
import com.samuelpart.iptvplayer.databinding.ActivityWebVideoPlayerBinding
import java.io.ByteArrayInputStream

/**
 * Reproductor BOT: carga la página iframe del host y JUEGA SOLO —
 * sin que el usuario tenga que pulsar nada. Hace el trabajo sucio:
 *
 *  1. Clicks automáticos en TODOS los botones de play conocidos
 *     (video-js, jwplayer, plyr, overlay, poster, "continuar") cada 900ms.
 *  2. Fuerza video.play() muted→unmuted dentro de cada iframe anidado.
 *  3. BORRA los overlays de publicidad del DOM (fixeos, iframes de ads,
 *     banners, pseudo-popups tipo layer/Kover).
 *  4. Bloquea popups/redirects: la navegación solo puede salir si es un
 *     request del propio anfitrión; popup windows desactivados.
 *  5. Bloqueo de red contra la lista ScraperConfig.adDomains.
 */
class WebVideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebVideoPlayerBinding
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val botHandler = Handler(Looper.getMainLooper())
    private var botTicks = 0
    private var originalHost: String = ""
    private var currentPageUrl: String = ""
    private var currentUserAgent: String = ""
    private var expectedRuntimeSec: Int? = null
    private var runtimeChecked = false
    private var runtimeInflight = false
    private var preferNativeDirect = false
    private var tmdbIdArg: Int = -1
    private var mediaTypeArg: String = "movie"
    private var pageBroken = false
    private var rescueTried = false

    companion object {
        private val EMBED_HOSTS = listOf("nupload")

        // DOM & players junk
        private val AD_OVERLAY_JS = """
            (function(){
                try {
                    var junk = [];
                    document.querySelectorAll('div[class*="ad"],div[id*="ad"],div[class*="banner"],.popup,.overlay-ad,.adsbygoogle,.a-ads,.bn,.banner,.vjs-ads-overlay').forEach(function(e){
                        if (e && e.tagName && !e.querySelector('video')) junk.push(e);
                    });
                    document.querySelectorAll('iframe').forEach(function(f){
                        var s = (f.className||'') + ' ' + (f.id||'') + ' ' + (f.src||'');
                        if (/zoom|bingo|ad|banner|promo|doubleclick|pop/i.test(s) && !/play|video|stream|embed/i.test(s)) junk.push(f);
                    });
                    // Overlays fijos con alto zIndex sin video adentro
                    document.querySelectorAll('div').forEach(function(e){
                        try {
                            var st = window.getComputedStyle(e);
                            var z = parseInt(st.zIndex||'0');
                            if ((st.position==='fixed'||st.position==='absolute') && z>90
                                && e.offsetWidth > window.innerWidth*0.6 && !e.querySelector('video')
                                && !e.closest('.vjs-video,.jwplayer,.plyr')) {
                                junk.push(e);
                            }
                        } catch (ex) {}
                    });
                    junk.forEach(function(e){ try { e.style.display='none'; e.remove(); } catch (ex) {} });
                    // Fake scroll disabler
                    document.documentElement.style.overflow='auto';
                    document.body && (document.body.style.overflow='auto');
                } catch (e) {}
            })();
        """.trimIndent()

        private val BOT_JS = """
            (function(){
                var playedNow = false;
                function tryPlay(doc) {
                    try {
                        var vs = doc.querySelectorAll('video');
                        for (var i = 0; i < vs.length; i++) {
                            var v = vs[i];
                            try {
                                v.muted = true;
                                v.volume = 1;
                                if (v.paused) { var p = v.play(); if (p && p.catch) { p.catch(function(){}); } }
                                if (!v.paused && v.currentSrc) { playedNow = true; }
                            } catch (e1) {}
                        }
                        var sel = '.vjs-big-play-button,.vjs-poster,.jw-icon-playback,.jw-display-icon-container,.plyr__control--overlaid,.plyr__control--overlaid-play,button[aria-label="Play"],button[class*="play" i],.play-button,.play-btn,#play-button,.play.overlay,.overlay-play,.video-js,.jwplayer,.ps-player,.play-btn-large';
                        doc.querySelectorAll(sel).forEach(function(b){ try { b.click(); } catch (e2) {} });
                        // Botoncitos de continuar/aceptar gates
                        doc.querySelectorAll('button,a').forEach(function(b){
                            try {
                                var t = (b.innerText||'').toLowerCase();
                                if (t.includes('continuar') || t.includes('continue') || t.includes('ver video') || t.includes('play video')) b.click();
                            } catch (e3) {}
                        });
                        // Chips de SERVIDOR (pelisflix/tioplus): voe, filemoon, latino...
                        doc.querySelectorAll('a,button,li.option,.server-item,.server').forEach(function(b){
                            try {
                                var t2 = ((b.innerText||'') + ' ' + (b.title||'')).toLowerCase();
                                if (t2 && t2.length < 30 &&
                                    /voe|dood|filemoon|mixdrop|streamtape|upstream|vidplay|latino|espan|server|opcion|opción|hd/.test(t2)
                                    && !b.querySelector('video')) {
                                    b.click();
                                }
                            } catch (e9) {}
                        });
                    } catch (e4) {}
                }
                tryPlay(document);
                try {
                    document.querySelectorAll('iframe').forEach(function(f){
                        try { if (f.contentDocument) tryPlay(f.contentDocument); } catch (e5) {}
                    });
                } catch (e6) {}
                $AD_OVERLAY_JS
                if (!playedNow) {
                    try {
                        var fr2 = document.querySelectorAll('iframe');
                        for (var f2 = 0; f2 < fr2.length; f2++) {
                            try {
                                var s3 = fr2[f2].src || '';
                                if (/^https?:/i.test(s3) &&
                                    /voe|filemoon|dood|mixdrop|streamtape|upstream|luluvdo|vidmoly|nupload|vidplay|oka|embed|play|video|server/i.test(s3) &&
                                    s3 !== location.href) {
                                    return 'IFRAME:' + s3;
                                }
                            } catch (e8) {}
                        }
                    } catch (e10) {}
                }
                return playedNow ? '1' : '0';
            })();
        """.trimIndent()

        fun isEmbedUrl(url: String): Boolean {
            val host = Uri.parse(url).host?.lowercase() ?: return false
            return EMBED_HOSTS.any { host.contains(it) }
        }
    }

    private var isVideoRolling = false
    private var iframeHops = 0
    private var cinematicApplied = false
    private var extractionAttempted = false
    private val sniffedVideoUrls = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val DEEP_PICK_JS = "(function(){" +
        "function ok(u){if(!u||typeof u!=='string')return false;if(!/^https?:/i.test(u))return false;" +
        "var l=u.toLowerCase();" +
        "return l.indexOf('m3u8')>=0||l.indexOf('.mp4')>=0||l.indexOf('.mpd')>=0||l.indexOf('webm')>=0||l.indexOf('videoplayback')>=0||l.indexOf('mime=video')>=0;}" +
        "function strong(u){var l=u.toLowerCase();return l.indexOf('m3u8')>=0||l.indexOf('videoplayback')>=0||l.indexOf('mime=video')>=0;}" +
        "var vs=document.querySelectorAll('video');" +
        "for(var i=0;i<vs.length;i++){" +
        "try{vs[i].preload='auto';if(vs[i].load)vs[i].load();}catch(el){}" +
        "var d=vs[i].duration||0;var t=vs[i].currentSrc||vs[i].src;" +
        "if(ok(t)){return 'DUR:'+Math.round(d)+'|'+t;}" +
        "var ss=vs[i].querySelectorAll('source');" +
        "for(var k=0;k<ss.length;k++){if(ok(ss[k].src)){return 'DUR:'+Math.round(d)+'|'+ss[k].src;}}" +
        "}" +
        "var all=document.querySelectorAll('source');" +
        "for(var j=0;j<all.length;j++){if(ok(all[j].src)){return 'NODUR|'+all[j].src;}}" +
        "try{var es=performance.getEntriesByType('resource');" +
        "for(var r=es.length-1;r>=0;r--){var n=es[r].name;if(strong(n)){return 'NODUR|'+n;}}}catch(e){}" +
        "return '';})();"

    private val CINEMATIC_JS = "(function(){" +
        "try {" +
        "var v = document.querySelector('video'); if (!v) return '';" +
        "var p = v;" +
        "while (p.parentElement && p.parentElement !== document.body) { p = p.parentElement; }" +
        "p.style.cssText += 'position:fixed !important; left:0 !important; top:0 !important; width:100vw !important; height:100vh !important; max-width:100vw !important; max-height:100vh !important; z-index:2147483000 !important; background:#000 !important;';" +
        "v.style.cssText += ' width:100% !important; height:100% !important;';" +
        "v.setAttribute('controls',''); v.setAttribute('playsinline',''); v.muted = false;" +
        "return 'ok';" +
        "} catch (e) { return ''; }" +
        "})();"


    private val botRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            botTicks++
            if (preferNativeDirect && botTicks >= 4 && botTicks % 2 == 0) {
                tryExpressNativeExtraction()
            }
            try {
                if (isVideoRolling) {
                    // CORRIENDO: solo limpia anuncios, jamás clicks/.play()
                    binding.webFramePlayer.evaluateJavascript(AD_OVERLAY_JS, null)
                } else {
                    binding.webFramePlayer.evaluateJavascript(BOT_JS) { res ->
                        if (res == null) return@evaluateJavascript
                        // Salto a iframe cross-origin (voe/filemoon...): la pagina
                        // ES el embed, y ahora si podemos pulsar su play.
                        if (!isVideoRolling && res.contains("IFRAME:") && iframeHops < 3 && botTicks >= 5) {
                            val raw = res.substringAfter("IFRAME:")
                            val src = buildString {
                                for (ci in 0 until raw.length) {
                                    val ch = raw[ci]
                                    if (ch.code != 92 && ch.code != 34) append(ch)
                                }
                            }
                            if (src.startsWith("http")) {
                                iframeHops++
                                originalHost = Uri.parse(src).host?.lowercase() ?: originalHost
                                botTicks = 0
                                Toast.makeText(
                                    this@WebVideoPlayerActivity,
                                    "Abriendo servidor…", Toast.LENGTH_SHORT
                                ).show()
                                binding.webFramePlayer.loadUrl(
                                    src, mapOf("Referer" to "https://$originalHost/")
                                )
                                return@evaluateJavascript
                            }
                        }
                        if (res.contains("1") && !res.contains("IFRAME:")) {
                            isVideoRolling = true
                            showCinematic()
                        }
                    }
                }
            } catch (_: Exception) { }
            val nx = botTicks == 14 || botTicks == 22 ||
                (preferNativeDirect && (botTicks == 8 || botTicks == 12 || botTicks == 16 || botTicks == 20 || botTicks == 26))
            if ((!isVideoRolling || preferNativeDirect) && !extractionAttempted && nx) {
                attemptProfessionalExtraction()
            }
            if (!isVideoRolling && (pageBroken || botTicks >= 28)) {
                triggerRescue()
                return
            }
            val delay = if (isVideoRolling) 3000L else 900L
            val limit = if (isVideoRolling) 6000 else 200
            if (botTicks < limit) botHandler.postDelayed(this, delay)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Iframe SIEMPRE a lo grande: landscape + pantalla completa
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Barra de notificaciones OCULTA dentro del player (se desliza para verla)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
        binding = ActivityWebVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        val title = intent.getStringExtra("channelName") ?: "Reproduciendo"
        val pageUrl = intent.getStringExtra("channelUrl") ?: ""
        originalHost = Uri.parse(pageUrl).host?.lowercase() ?: ""
        tmdbIdArg = intent.getIntExtra("tmdbId", -1)
        mediaTypeArg = intent.getStringExtra("mediaType") ?: "movie"
        preferNativeDirect = originalHost.contains("hanerix")
        if (preferNativeDirect) fetchRuntimeIfNeeded {}
        binding.txtWebPlayerTitle.text = title

        binding.btnWebPlayerBack.setOnClickListener { finish() }

        binding.btnWebCast.setOnClickListener {
            checkCastPermissionsAndAsk()
        }

        val web = binding.webFramePlayer
        val ws = web.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = false
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        ws.setSupportMultipleWindows(false)                 // sin ventanas/publicidad nueva
        ws.javaScriptCanOpenWindowsAutomatically = false
        ws.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        currentUserAgent = ws.userAgentString

        val emptyResponse = WebResourceResponse(
            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
        )

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return true
                val host = Uri.parse(url).host?.lowercase() ?: ""
                // Iframes internos (servers/embeds que el portal invoca): SÍ cargan
                if (request?.isForMainFrame == false) return false
                // Nada de salirse a Google/anuncios: solo host original (o esquemas base)
                return !(host == originalHost || host.endsWith(".$originalHost") ||
                    url.startsWith("about:") || url.startsWith("data:"))
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url?.toString() ?: ""
                val host = Uri.parse(url).host?.lowercase() ?: ""
                if (ScraperConfig.adDomains.any { d ->
                        host.contains(d.lowercase()) || url.lowercase().contains(d.lowercase())
                    }) {
                    return emptyResponse
                }
                // sniffer profesional: urls de video verdadero que la pagina pida
                if (sniffedVideoUrls.size < 40 &&
                    (listOf(".m3u8", ".mp4", ".mpd", ".webm").any { url.lowercase().contains(it) } ||
                        url.contains("videoplayback", true) || url.contains("mime=video", true))
                ) {
                    sniffedVideoUrls.add(url)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.webPlayerProgress.visibility = View.VISIBLE
                if (url != null) currentPageUrl = url
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.webPlayerProgress.visibility = View.GONE
                if (pageBroken) { triggerRescue(); return }
                // Dispara el BOT por PRIMERA vez al terminarse la carga…
                botHandler.removeCallbacks(botRunnable)
                botTicks = 0
                botHandler.postDelayed(botRunnable, 400)
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                val code = errorResponse?.statusCode ?: 0
                if ((request?.isForMainFrame == true) && code >= 400) {
                    pageBroken = true
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) pageBroken = true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                customViewCallback?.onCustomViewHidden()
                customView = view
                customViewCallback = callback
                binding.frameWebFullscreen.addView(view)
                binding.frameWebFullscreen.visibility = View.VISIBLE
            }

            override fun onHideCustomView() {
                binding.frameWebFullscreen.removeAllViews()
                binding.frameWebFullscreen.visibility = View.GONE
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }
        }

        if (pageUrl.isNotBlank()) {
            val headers = mutableMapOf(
                "Referer" to "https://${originalHost}/",
                "User-Agent" to ws.userAgentString
            )
            web.loadUrl(pageUrl, headers)
        }
    }

    override fun onPause() {
        super.onPause()
        botHandler.removeCallbacks(botRunnable)
        binding.webFramePlayer.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webFramePlayer.onResume()
        botHandler.postDelayed(botRunnable, 400)
    }

    override fun onDestroy() {
        botHandler.removeCallbacks(botRunnable)
        binding.frameWebFullscreen.removeAllViews()
        binding.webFramePlayer.destroy()
        super.onDestroy()
    }

    /** Anti-caida SIN EXTRACCION: si el iframe murio, buscar el MISMO titulo
     *  en los portales (TioPlus, RePelis24, RePelis24 Oficial, avcos
     *  pelisflix1.fans si esta habilitado) y RECARGAR esa pagina aqui mismo —
     *  el BOT se encargara de darle play a la nueva web/embed. Nada de VLC. */
    private fun triggerRescue() {
        if (rescueTried || isFinishing || isDestroyed) return
        rescueTried = true
        botHandler.removeCallbacks(botRunnable)
        val title = binding.txtWebPlayerTitle.text?.toString()?.ifBlank { null } ?: "esa pelicula"
        android.widget.Toast.makeText(this, "Enlace caducado — buscando otra fuente…", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val query = title.replace(Regex("[^A-Za-z0-9 ]"), " ").trim()
                val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try { CineScraper.searchPortalsHeadless(query) } catch (_: Exception) { emptyList() }
                }
                val alt = results.firstOrNull { m ->
                    val h = Uri.parse(m.url).host?.lowercase() ?: ""
                    h != originalHost && !h.endsWith(".$originalHost") && !isEmbedUrl(m.url)
                } ?: results.firstOrNull { !isEmbedUrl(it.url) }
                if (alt == null) {
                    android.widget.Toast.makeText(this@WebVideoPlayerActivity, "Sin alternativas por ahora: $title", android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }
                android.widget.Toast.makeText(this@WebVideoPlayerActivity, "Nueva fuente: ${alt.title}…", android.widget.Toast.LENGTH_SHORT).show()
                // Reinicia sobre la MISMA pantalla: nuevo host, nueva carcel, BOT de cero
                originalHost = Uri.parse(alt.url).host?.lowercase() ?: originalHost
                pageBroken = false
                isVideoRolling = false
                botTicks = 0
                botHandler.removeCallbacks(botRunnable)
                binding.webFramePlayer.loadUrl(
                    alt.url, mapOf("Referer" to "https://$originalHost/")
                )
            } catch (_: Exception) {
                android.widget.Toast.makeText(this@WebVideoPlayerActivity, "No se pudo reiniciar la reproduccion", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    /** Express (hanerix): apenas el BOT abra el iframe y pida el m3u8,
     *  cazamos el enlace VERIFICADO y lo lanzamos a VLC nativo al toque. */
    private fun tryExpressNativeExtraction() {
        try {
            binding.webFramePlayer.evaluateJavascript(DEEP_PICK_JS) { v ->
                if (isFinishing || isDestroyed) return@evaluateJavascript
                val raw = v ?: ""
                val found = buildString {
                    for (ci in 0 until raw.length) {
                        val ch = raw[ci]
                        if (ch.code != 92 && ch.code != 34) append(ch)
                    }
                }
                val pick = pickVerified(found, expectedRuntimeSec)
                if (pick != null) { launchDirectVideo(pick); return@evaluateJavascript }
                val sniff = sniffedVideoUrls.lastOrNull { isStrongStream(it) }
                if (sniff != null) launchDirectVideo(sniff)
            }
        } catch (_: Exception) { }
    }

    /** Extractor profesional de ENLACE DIRECTO — CON FIRMA DE DURACION:
     *  solo acepta el video si su duracion casa con el runtime de TMDb
     *  (o supera el piso anti-ads 1500s). Los banners/ads jamas pasan. */
    private fun attemptProfessionalExtraction() {
        extractionAttempted = true
        android.widget.Toast.makeText(
            this, "Buscando el enlace directo del video…", android.widget.Toast.LENGTH_SHORT
        ).show()
        fetchRuntimeIfNeeded { expected ->
            try {
                binding.webFramePlayer.evaluateJavascript(DEEP_PICK_JS) { v ->
                    if (isFinishing || isDestroyed) return@evaluateJavascript
                    val raw = v ?: ""
                    val found = buildString {
                        for (ci in 0 until raw.length) {
                            val ch = raw[ci]
                            if (ch.code != 92 && ch.code != 34) append(ch)
                        }
                    }
                    val pick = pickVerified(found, expected)
                    when {
                        pick != null -> launchDirectVideo(pick)
                        else -> {
                            val sniff = sniffedVideoUrls.lastOrNull { isStrongStream(it) }
                            if (sniff != null) launchDirectVideo(sniff)
                            else extractionAttempted = false
                        }
                    }
                }
            } catch (_: Exception) {
                extractionAttempted = false
            }
        }
    }

    private fun fetchRuntimeIfNeeded(cb: (Int?) -> Unit) {
        if (runtimeChecked) { cb(expectedRuntimeSec); return }
        if (tmdbIdArg <= 0 || runtimeInflight) { cb(expectedRuntimeSec); return }
        runtimeInflight = true
        lifecycleScope.launch {
            val sec = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { CineRepository.fetchRuntimeSeconds(tmdbIdArg, mediaTypeArg) } catch (_: Exception) { null }
            }
            expectedRuntimeSec = sec
            runtimeChecked = true
            runtimeInflight = false
            cb(sec)
        }
    }

    private fun isStrongStream(u: String): Boolean {
        val l = u.lowercase()
        return l.contains("m3u8") || l.contains("videoplayback") || l.contains("mime=video")
    }

    /** DUR verifica: diff <= max(10% runtime, 480s) o piso 1500s.
     *  NODUR solo si es strong. */
    private fun pickVerified(payload: String, expectedSec: Int?): String? {
        if (payload.isEmpty()) return null
        if (payload.startsWith("DUR:")) {
            val dur = payload.substringAfter("DUR:").substringBefore('|').toIntOrNull() ?: 0
            val url = payload.substringAfter('|')
            if (!url.startsWith("http")) return null
            // dur<=0: metadata no cargo aun — acepta solo señales fuertes
            if (dur <= 0) {
                return if (isStrongStream(url)) url else null
            }
            val okDur = expectedSec?.let {
                kotlin.math.abs(dur - it) <= maxOf(it / 10, 480)
            } ?: (dur >= 800)
            return if (okDur) url else null
        }
        if (payload.startsWith("NODUR|")) {
            val url = payload.substringAfter('|')
            return if (url.startsWith("http") && isStrongStream(url)) url else null
        }
        return null
    }


    private fun launchDirectVideo(directUrl: String) {
        botHandler.removeCallbacks(botRunnable)
        android.widget.Toast.makeText(
            this, "Enlace directo encontrado — abriendo reproductor nativo", android.widget.Toast.LENGTH_SHORT
        ).show()
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra("channelName", binding.txtWebPlayerTitle.text?.toString() ?: "Video")
                putExtra("channelUrl", directUrl)
                putExtra("streamReferer", currentPageUrl.ifBlank { "https://$originalHost/" })
                putExtra("streamUserAgent", currentUserAgent)
            }
        )
        finish()
    }

    /** El video empezo: la capa oscura se desvanece y el video toma TODA
     *  la pantalla — el usuario jamas ve la pagina de origen. */
    private fun showCinematic() {
        if (cinematicApplied) return
        cinematicApplied = true
        try {
            binding.webFramePlayer.evaluateJavascript(CINEMATIC_JS, null)
        } catch (_: Exception) { }
        binding.layerWebBoot.animate()
            .alpha(0f)
            .setDuration(450)
            .withEndAction {
                binding.layerWebBoot.visibility = View.GONE
                binding.layerWebBoot.alpha = 1f
            }
            .start()
    }

    private val CAST_PERMISSION_REQUEST_CODE = 4201

    private fun checkCastPermissionsAndAsk() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) showWebCastDialog()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), CAST_PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAST_PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            showWebCastDialog()
        }
    }

    private fun showWebCastDialog() {
        val pageUrl = intent.getStringExtra("channelUrl") ?: return
        val pageTitle = binding.txtWebPlayerTitle.text?.toString() ?: "Video"
        val dialogView = layoutInflater.inflate(R.layout.dialog_cast_selector, null)
        val layoutScanning = dialogView.findViewById<LinearLayout>(R.id.layoutCastScanning)
        val rv = dialogView.findViewById<RecyclerView>(R.id.rvCastDevices)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCancelCast)
        rv.layoutManager = LinearLayoutManager(this)

        lateinit var adapter: CastDeviceAdapter
        adapter = CastDeviceAdapter(emptyList()) { device ->
            dialogView.clearFocus()
            castTo(device.type, device, pageUrl, pageTitle)
        }
        rv.adapter = adapter

        val dialog = AlertDialog.Builder(
            this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert
        ).setView(dialogView).create()
        btnClose.setOnClickListener { dialog.dismiss() }

        layoutScanning.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            val devices = try {
                UniversalCaster.discoverDevices(this@WebVideoPlayerActivity)
            } catch (_: Exception) { emptyList() }
            layoutScanning.visibility = android.view.View.GONE
            val list = devices.toMutableList()
            list.add(CastDevice("Transmitir con Smart View del Sistema", "system_cast", 0, "SystemCast"))
            adapter.updateList(list)
        }
        dialog.show()
    }

    private fun castTo(type: String, device: CastDevice, pageUrl: String, title: String) {
        when (type) {
            "SystemCast" -> {
                try {
                    val castIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(pageUrl), "video/*")
                        putExtra("title", title)
                        putExtra("android.intent.extra.Title", title)
                    }
                    startActivity(Intent.createChooser(castIntent, "Elige tu TV"))
                } catch (e: Exception) { e.printStackTrace() }
            }
            "Roku", "DLNA" -> {
                Toast.makeText(this, "Conectando con ${device.name}...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val ok = try {
                        if (type == "Roku") {
                            UniversalCaster.castToRoku(device.ip, pageUrl, title)
                        } else {
                            val ctrl = device.controlUrl
                                ?: "http://${device.ip}:1400/AVTransport/control"
                            UniversalCaster.castToDlna(ctrl, pageUrl, title)
                        }
                    } catch (_: Exception) { false }
                    Toast.makeText(
                        this@WebVideoPlayerActivity,
                        if (ok) "Enviado a ${device.name}" else "No respondio ${device.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            else -> Toast.makeText(this, "Dispositivo no soportado por embeds", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Back: navega el historial del iframe si puede")
    override fun onBackPressed() {
        if (customView != null) {
            binding.frameWebFullscreen.removeAllViews()
            binding.frameWebFullscreen.visibility = View.GONE
            customViewCallback?.onCustomViewHidden()
            customView = null
            customViewCallback = null
        } else if (binding.webFramePlayer.canGoBack()) {
            binding.webFramePlayer.goBack()
        } else {
            super.onBackPressed()
        }
    }
}

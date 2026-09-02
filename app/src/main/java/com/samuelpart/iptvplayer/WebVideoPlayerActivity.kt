package com.samuelpart.iptvplayer

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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
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
                    } catch (e4) {}
                }
                tryPlay(document);
                try {
                    document.querySelectorAll('iframe').forEach(function(f){
                        try { if (f.contentDocument) tryPlay(f.contentDocument); } catch (e5) {}
                    });
                } catch (e6) {}
                $AD_OVERLAY_JS
                return playedNow ? '1' : '0';
            })();
        """.trimIndent()

        fun isEmbedUrl(url: String): Boolean {
            val host = Uri.parse(url).host?.lowercase() ?: return false
            return EMBED_HOSTS.any { host.contains(it) }
        }
    }

    private val botRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            botTicks++
            try {
                binding.webFramePlayer.evaluateJavascript(BOT_JS, null)
            } catch (_: Exception) { }
            if (botTicks < 200) botHandler.postDelayed(this, 900)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        val title = intent.getStringExtra("channelName") ?: "Reproduciendo"
        val pageUrl = intent.getStringExtra("channelUrl") ?: ""
        originalHost = Uri.parse(pageUrl).host?.lowercase() ?: ""
        binding.txtWebPlayerTitle.text = title

        binding.btnWebPlayerBack.setOnClickListener { finish() }

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

        val emptyResponse = WebResourceResponse(
            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
        )

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return true
                val host = Uri.parse(url).host?.lowercase() ?: ""
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
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.webPlayerProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.webPlayerProgress.visibility = View.GONE
                // Dispara el BOT por PRIMERA vez al terminarse la carga…
                botHandler.removeCallbacks(botRunnable)
                botTicks = 0
                botHandler.postDelayed(botRunnable, 400)
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

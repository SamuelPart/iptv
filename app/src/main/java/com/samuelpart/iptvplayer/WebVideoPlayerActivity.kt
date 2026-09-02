package com.samuelpart.iptvplayer

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.samuelpart.iptvplayer.databinding.ActivityWebVideoPlayerBinding

/**
 * Reproductor que NO extrae nada: reproduce la página iframe del host
 * (bugs que mejor dejamos al reproductor de ellos) dentro de la nuestra.
 * Se usa para URLs cuyo host vive en [EMBED_HOSTS] (p. ej. nupload.top).
 */
class WebVideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebVideoPlayerBinding
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    companion object {
        private val EMBED_HOSTS = listOf("nupload")

        fun isEmbedUrl(url: String): Boolean {
            val host = Uri.parse(url).host?.lowercase() ?: return false
            return EMBED_HOSTS.any { host.contains(it) }
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
        ws.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                // Toda la navegación se queda dentro del iframe de nuestra app
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.webPlayerProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.webPlayerProgress.visibility = View.GONE
                // Intento de auto-play silencioso para players tipo video-js
                view?.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');" +
                        "if(v){v.muted=false;var p=v.play();if(p&&p.catch)p.catch(function(){});}})();",
                    null
                )
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
                "Referer" to "https://${Uri.parse(pageUrl).host ?: ""}/",
                "User-Agent" to ws.userAgentString
            )
            web.loadUrl(pageUrl, headers)
        }
    }

    override fun onPause() {
        super.onPause()
        binding.webFramePlayer.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webFramePlayer.onResume()
    }

    override fun onDestroy() {
        binding.frameWebFullscreen.removeAllViews()
        binding.webFramePlayer.destroy()
        super.onDestroy()
    }

    @Deprecated("Back en el iframe: navega atrás si puede")
    override fun onBackPressed() {
        if (customView != null) {
            val w = binding.webFramePlayer
            w.webChromeClient?.let { }
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

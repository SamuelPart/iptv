package com.samuelpart.iptvplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Real-time video URL extractor for JavaScript-heavy streaming pages
 * (works with embeds from any hoster: dr0pstream, doodstream, streamtape,
 * filemoon, voe, mixdrop, etc.).
 *
 * Loads the source page in a hidden WebView that behaves exactly like a
 * browser (executes JS, stores cookies, auto-plays the player) and intercepts
 * the fresh, temporary video request (.m3u8/.mp4) the moment the page's own
 * player asks for it. Nothing is stored: every press of "Play" performs a
 * brand-new extraction against the source site.
 *
 * It also captures the Referer and User-Agent the page used, because most
 * video hosters reject the stream if the player doesn't present them.
 */
object WebViewResolver {

    data class Resolved(
        val url: String,
        val referer: String,
        val userAgent: String
    )

    private val resolveMutex = Mutex()

    private val VIDEO_MARKERS = listOf(
        ".m3u8", ".mpd", ".mp4", ".mkv", ".webm",
        "videoplayback", "mime=video", "/manifest", "/pass_md5/"
    )

    fun looksLikeVideoUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val lower = url.lowercase()
        return VIDEO_MARKERS.any { lower.contains(it) }
    }

    /** Returns the first real video URL visible in the DOM or in the page's network history. */
    private val VIDEO_PICK_JS = """
        (function(){
            function ok(u){
                if(!u || typeof u !== 'string') return false;
                if(!/^https?:/i.test(u)) return false;
                return /\.(m3u8|mpd|mp4|mkv|webm)(\?|&|#|$)/i.test(u)
                    || /videoplayback|mime=video|\/manifest|\/pass_md5\//i.test(u);
            }
            var vs = document.querySelectorAll('video');
            for (var i = 0; i < vs.length; i++) {
                var s = vs[i].currentSrc || vs[i].src;
                if (ok(s)) return s;
                var ss = vs[i].querySelectorAll('source');
                for (var k = 0; k < ss.length; k++) { if (ok(ss[k].src)) return ss[k].src; }
            }
            var all = document.querySelectorAll('source');
            for (var j = 0; j < all.length; j++) { if (ok(all[j].src)) return all[j].src; }
            try {
                var es = performance.getEntriesByType('resource');
                for (var r = es.length - 1; r >= 0; r--) { if (ok(es[r].name)) return es[r].name; }
            } catch (e) {}
            return '';
        })();
    """.trimIndent()

    /** Muted auto-play + click on the most common play buttons (hosters only fetch the video after "Play"). */
    private val AUTO_PLAY_JS = """
        (function(){
            try {
                var v = document.querySelector('video');
                if (v) { v.muted = true; var p = v.play(); if (p && p.catch) { p.catch(function(){}); } }
                var b = document.querySelector('.vjs-big-play-button,.jw-icon-playback,.jw-display-icon-container,.plyr__control--overlaid,.play-button,.play-btn,#play-button,button[aria-label="Play"],.play.overlay');
                if (b) { b.click(); }
            } catch (e) {}
        })();
    """.trimIndent()

    /**
     * Resolves [pageUrl] to the real video stream. Returns null on timeout/failure.
     * Serialized with a mutex so two resolutions never fight over the WebView.
     *
     * NOTE: WebView callbacks like shouldInterceptRequest run on a BACKGROUND
     * thread, but stopLoading()/destroy() must run on the MAIN thread — that's
     * why cleanup is always posted to the handler (an off-thread destroy used
     * to crash and restart the app).
     */
    suspend fun resolve(context: Context, pageUrl: String, timeoutMs: Long = 22000L): Resolved? {
        resolveMutex.withLock {
            return withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Resolved?> { cont ->
                    val handler = Handler(Looper.getMainLooper())
                    val finished = AtomicBoolean(false)
                    var webView: WebView? = null

                    fun finish(result: Resolved?) {
                        if (!finished.compareAndSet(false, true)) return
                        handler.removeCallbacksAndMessages(null)
                        if (webView != null) {
                            val wv = webView
                            webView = null
                            if (wv != null) {
                                // WebView lifecycle MUST run on the main thread
                                handler.post {
                                    try { wv.stopLoading() } catch (_: Exception) {}
                                    try { wv.destroy() } catch (_: Exception) {}
                                }
                            }
                        }
                        if (cont.isActive) cont.resume(result)
                    }

                    try {
                        val wv = WebView(context)
                        webView = wv
                        val pageOrigin = run {
                            val u = Uri.parse(pageUrl)
                            "${u.scheme}://${u.host}/"
                        }
                        // Capture once on the main thread (WebView reads from the
                        // background network thread are unsafe)
                        val webViewUA = wv.settings.userAgentString ?: CineScraper.CHROME_UA

                        wv.settings.javaScriptEnabled = true
                        wv.settings.domStorageEnabled = true
                        wv.settings.mediaPlaybackRequiresUserGesture = false
                        // Muchos mirrors sirven el player raiz (con m3u8 visible) solo a desktop
                        wv.settings.userAgentString = CineScraper.CHROME_UA
                        // Embedded players open tons of ad popup windows: forbid them
                        wv.settings.setSupportMultipleWindows(false)
                        wv.settings.javaScriptCanOpenWindowsAutomatically = false

                        // Poll the DOM periodically: catches video URLs that the
                        // media stack fetches internally (invisible to request interception)
                        val pollRunnable = object : Runnable {
                            var ticks = 0
                            override fun run() {
                                val v = webView ?: return
                                ticks++
                                v.evaluateJavascript(VIDEO_PICK_JS) { value ->
                                    val found = decodeJsString(value)
                                    if (!found.isNullOrEmpty() && looksLikeVideoUrl(found)) {
                                        finish(Resolved(found, pageUrl, webViewUA))
                                    }
                                }
                                if (ticks % 2 == 0) v.evaluateJavascript(AUTO_PLAY_JS, null)
                                if (!finished.get()) handler.postDelayed(this, 1500)
                            }
                        }

                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val reqUrl = request.url?.toString() ?: return null
                                if (isBlockedDomain(reqUrl)) return emptyResponse()
                                if (!request.isForMainFrame && looksLikeVideoUrl(reqUrl)) {
                                    finish(Resolved(reqUrl, pageUrl, webViewUA))
                                    return emptyResponse()
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val reqUrl = request.url?.toString() ?: return true
                                if (!reqUrl.startsWith("http")) return true // block intent://, market://, app links
                                if (request.isForMainFrame && isBlockedDomain(reqUrl)) return true // block ad popups
                                return false
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                view.evaluateJavascript(AUTO_PLAY_JS, null)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                                if (failingUrl != null && failingUrl == pageUrl) {
                                    finish(null) // main frame failed: no point waiting
                                }
                            }
                        }
                        wv.webChromeClient = WebChromeClient()

                        handler.postDelayed({ finish(null) }, timeoutMs)
                        handler.postDelayed(pollRunnable, 1200)

                        wv.loadUrl(pageUrl, mapOf("Referer" to pageOrigin))

                        cont.invokeOnCancellation { handler.post { finish(null) } }

                    } catch (e: Exception) {
                        e.printStackTrace()
                        finish(null)
                    }
                }
            }
        }
    }

    private fun isBlockedDomain(url: String): Boolean {
        val lower = url.lowercase()
        return ScraperConfig.adDomains.any { lower.contains(it) }
    }

    private fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    /** evaluateJavascript returns a JSON-encoded string: unwrap and unescape it. */
    private fun decodeJsString(value: String?): String? {
        if (value.isNullOrEmpty() || value == "null" || value == "\"\"") return null
        var s = value.trim()
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        s = s.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\n", "\n")
        val firstLine = s.lines().firstOrNull { it.isNotBlank() } ?: return null
        return firstLine.takeIf { it.isNotEmpty() }
    }
}

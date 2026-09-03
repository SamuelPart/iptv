package com.samuelpart.iptvplayer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Remote, hot-updatable configuration of the streaming portals used by the
 * real-time scraper. The JSON lives in the GitHub repo, so when a portal dies
 * or migrates to a new domain, editing that single file fixes every installed
 * app the next time it opens — no APK update required.
 */
object ScraperConfig {

    data class Portal(
        val name: String,
        val baseUrl: String,
        val searchPath: String,
        val enabled: Boolean = true
    ) {
        val domain: String
            get() = Uri.parse(baseUrl).host?.removePrefix("www.")
                ?: baseUrl.removePrefix("http://").removePrefix("https://").trimEnd('/')
    }

    private const val PREFS_NAME = "scraper_cfg"
    private const val KEY_CONFIG_JSON = "config_json"

    private val CONFIG_URLS = listOf(
        "https://raw.githubusercontent.com/SamuelPart/iptv/main/scraper_config.json",
        "https://raw.githubusercontent.com/SamuelPart/iptv/arena/01a04133-iptv/scraper_config.json"
    )

    private val DEFAULT_PORTALS = listOf(
        Portal("Cuevana8", "https://www.cuevana8.plus", "/?s={query}"),
        Portal("RePelis24", "https://repelis24.life", "/?s={query}"),
        Portal("RePelis24 Oficial", "https://repelis24-oficial.site", "/?s={query}"),
        Portal("PelisFlix", "https://pelisflix1.fans", "/?s={query}")
    )

    private val DEFAULT_EXTRA_WEB_DOMAINS = listOf("repelis24.ing", "pelisflix1.fans", "pelisflix", "nupload.top", "nupload", "cuevana8.plus", "cuevana8", "cuevana")

    private val DEFAULT_AD_DOMAINS = listOf(
        "doubleclick.net", "googlesyndication.com", "google-analytics.com",
        "adservice.google.com", "adnxs.com", "popads.net", "popcash.net",
        "exoclick.com", "juicyads.com", "trafficjunky.com", "tsyndicate.com",
        "chaturbate.com", "adskeeper.com", "onclicka.com"
    )

    // Popular video hoster/embed servers whose pages must be resolved in real
    // time. Substring match, so "doodstream" also covers dood.watch, dood.re...
    private val DEFAULT_HOSTER_DOMAINS = listOf(
        "dr0pstream.com", "doodstream", "dood.", "streamtape", "voe.sx",
        "filemoon", "mixdrop", "upstream", "vidmoly", "uqload", "fembed",
        "luluvdo", "wolfstream", "streamwish", "filelions", "earnvids",
        "streamlare", "hydrax", "yourupload", "supervideo", "dropload",
        "embedsito", "ok.ru", "rumble.com", "sendvid.com", "nupload", "gscdn.cam"
    )

    private val SOCIAL_DOMAINS = listOf(
        "facebook.com", "twitter.com", "x.com", "instagram.com",
        "tiktok.com", "youtube.com", "youtu.be", "disqus.com"
    )

    @Volatile
    var portals: List<Portal> = DEFAULT_PORTALS
        private set

    @Volatile
    var extraWebPageDomains: List<String> = DEFAULT_EXTRA_WEB_DOMAINS
        private set

    @Volatile
    var adDomains: List<String> = DEFAULT_AD_DOMAINS
        private set

    @Volatile
    var hosterDomains: List<String> = DEFAULT_HOSTER_DOMAINS
        private set

    /** Domains whose URLs are HTML pages that must be resolved in real time, never played directly. */
    fun webPageDomains(): Set<String> {
        val set = LinkedHashSet<String>()
        portals.filter { it.enabled }.forEach { set.add(it.domain) }
        set.addAll(extraWebPageDomains)
        return set
    }

    fun isWebPageUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val host = Uri.parse(url).host?.removePrefix("www.")?.lowercase() ?: return false
        return webPageDomains().any { d -> host == d.lowercase() || host.endsWith("." + d.lowercase()) }
    }

    fun isSocialOrAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return SOCIAL_DOMAINS.any { lower.contains(it) } || adDomains.any { lower.contains(it) }
    }

    /** True if the URL belongs to a known video embed hoster (dr0pstream, doodstream...). */
    fun isKnownHosterUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val host = Uri.parse(url).host?.lowercase() ?: return false
        return hosterDomains.any { host.contains(it) }
    }

    /** Builds "domain -> search URL" pairs for every enabled portal. */
    fun searchConfigsFor(query: String): List<Pair<String, String>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return portals.filter { it.enabled }.map { portal ->
            portal.domain to (portal.baseUrl.trimEnd('/') + portal.searchPath.replace("{query}", encoded))
        }
    }

    /** Instantly loads the last downloaded config from disk (cold start, before network). */
    fun loadCached(context: Context) {
        try {
            val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CONFIG_JSON, null)
            if (!saved.isNullOrEmpty()) applyJson(saved)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Downloads the freshest config from GitHub in real time and persists it for offline starts. */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        for (configUrl in CONFIG_URLS) {
            try {
                val conn = URL(configUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("User-Agent", "IPTV-ScraperConfig/1.0")
                conn.setRequestProperty("Cache-Control", "no-cache")
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (applyJson(body)) {
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(KEY_CONFIG_JSON, body).apply()
                        return@withContext
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun applyJson(body: String): Boolean {
        return try {
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("portals") ?: return false
            val parsed = mutableListOf<Portal>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                val base = o.optString("baseUrl")
                if (name.isEmpty() || base.isEmpty()) continue
                parsed.add(
                    Portal(
                        name = name,
                        baseUrl = base.trimEnd('/'),
                        searchPath = o.optString("searchPath", "/?s={query}"),
                        enabled = o.optBoolean("enabled", true)
                    )
                )
            }
            if (parsed.isEmpty()) return false
            portals = parsed

            obj.optJSONArray("extraWebPageDomains")?.let { a ->
                val l = mutableListOf<String>()
                for (i in 0 until a.length()) {
                    val v = a.optString(i)
                    if (v.isNotEmpty()) l.add(v)
                }
                if (l.isNotEmpty()) extraWebPageDomains = l
            }
            obj.optJSONArray("adDomains")?.let { a ->
                val l = mutableListOf<String>()
                for (i in 0 until a.length()) {
                    val v = a.optString(i)
                    if (v.isNotEmpty()) l.add(v)
                }
                if (l.isNotEmpty()) adDomains = l
            }
            obj.optJSONArray("hosterDomains")?.let { a ->
                val l = mutableListOf<String>()
                for (i in 0 until a.length()) {
                    val v = a.optString(i)
                    if (v.isNotEmpty()) l.add(v)
                }
                if (l.isNotEmpty()) hosterDomains = l
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

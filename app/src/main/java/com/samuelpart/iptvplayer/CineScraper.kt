package com.samuelpart.iptvplayer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * Real-time streaming scraper. No video link (.mp4/.m3u8) is ever stored in a
 * database: every time the user presses "Play", this visits the source page at
 * that exact moment, extracts the fresh temporary URL and hands it to the
 * player immediately.
 *
 * The portal list comes from [ScraperConfig], hot-updated from GitHub, so when
 * a source invalidates its links or migrates to a new domain, the fix reaches
 * every installed app without an update.
 */
object CineScraper {

    const val CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"

    // Never read more than this from any single page: if a URL turns out to be
    // a raw multi-GB .mp4 instead of HTML, we stop early instead of freezing
    // the whole app while downloading it.
    private const val MAX_HTML_CHARS = 1_200_000

    suspend fun scrapeVideoUrls(title: String): List<String> = withContext(Dispatchers.IO) {
        val list = mutableListOf<String>()

        for ((domain, searchUrl) in ScraperConfig.searchConfigsFor(title)) {
            try {
                val html = fetchHtml(searchUrl) ?: continue

                // Extract first movie/series link from search results page
                val pageLink = extractFirstResultLink(html, domain) ?: continue

                // Fetch the detail/movie page html
                val pageHtml = fetchHtml(pageLink) ?: continue

                // Extract any video stream / embedded player links from the page
                val streams = extractVideoStreams(pageHtml)
                if (streams.isNotEmpty()) {
                    list.addAll(streams)
                    break // Stop if we successfully found streams from this portal!
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext list.distinct()
    }

    suspend fun searchPortalsHeadless(query: String): List<CineMedia> = withContext(Dispatchers.IO) {
        val list = mutableListOf<CineMedia>()

        for ((domain, searchUrl) in ScraperConfig.searchConfigsFor(query)) {
            try {
                val html = fetchHtml(searchUrl) ?: continue

                // Split HTML by card/item containers
                val items = html.split(Regex("<article|<div class=\"item|class=\"poster|class=\"result-item"))
                for (item in items) {
                    if (item.length < 100) continue

                    // Extract detail link
                    val escapedDomain = Regex.escape(domain)
                    val hrefPattern = Pattern.compile("href=\"(https?://(?:www\\.)?$escapedDomain/[^\"]+)\"")
                    val hrefMatcher = hrefPattern.matcher(item)
                    if (!hrefMatcher.find()) continue
                    val detailLink = hrefMatcher.group(1)

                    if (detailLink.contains("?s=") || detailLink.contains("/category/") || detailLink.contains("/tag/") ||
                        detailLink == "https://$domain/" || detailLink == "https://$domain" ||
                        detailLink == "https://www.$domain/" || detailLink == "https://www.$domain"
                    ) {
                        continue
                    }

                    // Extract poster image
                    var posterUrl = ""
                    val imgPattern = Pattern.compile("src=\"([^\"]+)\"")
                    val imgMatcher = imgPattern.matcher(item)
                    if (imgMatcher.find()) {
                        posterUrl = imgMatcher.group(1)
                    }

                    // Extract title
                    var title = ""
                    val altPattern = Pattern.compile("alt=\"([^\"]+)\"")
                    val altMatcher = altPattern.matcher(item)
                    if (altMatcher.find()) {
                        title = altMatcher.group(1)
                    } else {
                        val titlePattern = Pattern.compile("title=\"([^\"]+)\"")
                        val titleMatcher = titlePattern.matcher(item)
                        if (titleMatcher.find()) {
                            title = titleMatcher.group(1)
                        }
                    }

                    if (title.isEmpty()) {
                        title = detailLink.trimEnd('/').split("/").last().replace("-", " ")
                            .replaceFirstChar { it.uppercase() }
                    }

                    if (title.isNotEmpty()) {
                        val isSeries = title.lowercase().contains("temporada") || title.lowercase().contains("serie") ||
                                detailLink.contains("/tvshows/") || detailLink.contains("/series/")
                        list.add(
                            CineMedia(
                                title = title,
                                searchTitle = title,
                                url = detailLink,
                                rawLogo = posterUrl,
                                type = if (isSeries) "series" else "movie",
                                group = if (isSeries) "Series VOD" else "Películas VOD"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext list.distinctBy { it.title.lowercase().trim() }
    }

    /**
     * Decides whether a URL is a web page / embedded player that must be
     * resolved in real time, as opposed to a direct stream VLC can play.
     * Known portals and known video hosters always resolve; any extension-less
     * URL that looks like an embed page (/e/, /v/, embed, player, watch...)
     * resolves too. Direct IPTV endpoints (with or without extension) are
     * never touched.
     */
    fun shouldResolvePage(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
            if (CineRepository.isDirectStreamUrl(url)) return false // already a direct stream
        if (ScraperConfig.isWebPageUrl(url)) return true
        if (ScraperConfig.isKnownHosterUrl(url)) return true
        val path = (Uri.parse(url).path ?: "").lowercase()
        return path.contains("/e/") || path.contains("/v/") || path.contains("embed") ||
                path.contains("player") || path.contains("watch") || path.contains("/video")
    }

    /**
     * Full real-time extraction chain used on every press of "Play":
     *  1) Cheap HTTP extraction (regex over the page, follows one iframe level).
     *  2) If the page needs JavaScript, a hidden WebView runs it like a browser
     *     and intercepts the fresh video request, headers included.
     */
    private fun absolutize(src: String, pageUrl: String): String {
        return when {
            src.startsWith("//") -> "https:$src"
            src.startsWith("/") -> {
                val u = Uri.parse(pageUrl)
                "${u.scheme}://${u.host}$src"
            }
            else -> src
        }
    }

    private fun fetchHtml(urlString: String): String? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", CHROME_UA)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            val host = url.host ?: ""
            conn.setRequestProperty("Referer", "${url.protocol}://$host/")

            if (conn.responseCode in 200..299) {
                val contentType = conn.contentType?.lowercase() ?: ""
                // Never try to "read HTML" out of a real video/audio binary
                if (contentType.startsWith("video/") || contentType.startsWith("audio/")) return null

                val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
                val out = StringBuilder()
                val buf = CharArray(8192)
                var total = 0
                while (true) {
                    val read = try { reader.read(buf) } catch (e: Exception) { break }
                    if (read == -1) break
                    out.append(buf, 0, read)
                    total += read
                    if (total >= MAX_HTML_CHARS) break
                }
                try { reader.close() } catch (_: Exception) {}
                return out.toString()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
        return null
    }

    private fun extractFirstResultLink(html: String, domain: String): String? {
        val escapedDomain = Regex.escape(domain)
        val pattern = Pattern.compile("href=\"(https?://(?:www\\.)?$escapedDomain/[^\"]+)\"")
        val matcher = pattern.matcher(html)
        while (matcher.find()) {
            val link = matcher.group(1)
            if (!link.contains("?s=") && !link.contains("/category/") && !link.contains("/tag/") &&
                link != "https://$domain/" && link != "https://$domain" &&
                link != "https://www.$domain/" && link != "https://www.$domain"
            ) {
                return link
            }
        }
        return null
    }

    private fun extractVideoStreams(html: String): List<String> {
        val list = mutableListOf<String>()

        // 1. Direct Video URLs (mp4, mkv, webm, m3u8, mpd)
        val videoPattern = Pattern.compile(
            "(https?://[^\\s\"'<>]+\\.(?:mp4|mkv|webm|m3u8|mpd)[^\\s\"'<>]*)",
            Pattern.CASE_INSENSITIVE
        )
        val videoMatcher = videoPattern.matcher(html)
        while (videoMatcher.find()) {
            val u = videoMatcher.group(1)
            if (!ScraperConfig.isSocialOrAdUrl(u)) list.add(u)
        }

        // 2. Embedded players: <iframe src="..."> and lazy-loaded <iframe data-src="...">
        val iframePattern = Pattern.compile("<iframe[^>]+(?:data-src|src)=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
        val iframeMatcher = iframePattern.matcher(html)
        while (iframeMatcher.find()) {
            var src = iframeMatcher.group(1)
            if (src.startsWith("//")) src = "https:$src"
            if (!src.startsWith("http")) continue
            if (ScraperConfig.isSocialOrAdUrl(src)) continue

            val looksLikePlayer = src.contains("embed") || src.contains("player") || src.contains("video") ||
                    src.contains("stream") || src.contains("/e/") || src.contains("/v/") ||
                    src.contains("drive") || src.contains("dropbox") || src.contains("play")
            val host = Uri.parse(src).host?.removePrefix("www.") ?: continue
            val isOwnPortal = ScraperConfig.webPageDomains().any { d -> host == d || host.endsWith(".$d") }

            // On streaming pages, an external iframe is almost always the player itself
            if (looksLikePlayer || !isOwnPortal) {
                list.add(src)
            }
        }

        return list
    }
}

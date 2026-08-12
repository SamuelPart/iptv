package com.samuelpart.iptvplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

object CineScraper {

    suspend fun scrapeVideoUrls(title: String): List<String> = withContext(Dispatchers.IO) {
        val list = mutableListOf<String>()
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        
        // Portals to query silently in second plane: 1. TioPlus, 2. Repelis24
        val searchUrls = listOf(
            "https://tioplus.app/?s=$encodedTitle",
            "https://repelis24.ing/?s=$encodedTitle"
        )
        
        for (searchUrl in searchUrls) {
            try {
                val html = fetchHtml(searchUrl) ?: continue
                
                // Extract first movie/series link from search results page
                val pageLink = extractFirstResultLink(html, searchUrl) ?: continue
                
                // Fetch the detail/movie page html
                val pageHtml = fetchHtml(pageLink) ?: continue
                
                // Extract any video stream links from the page
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
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        val searchConfigs = listOf(
            Pair("tioplus.app", "https://tioplus.app/?s=$encodedQuery"),
            Pair("repelis24.ing", "https://repelis24.ing/?s=$encodedQuery")
        )
        
        for ((domain, searchUrl) in searchConfigs) {
            try {
                val html = fetchHtml(searchUrl) ?: continue
                
                // Split HTML by card/item containers
                val items = html.split(Regex("<article|<div class=\"item|class=\"poster|class=\"result-item"))
                for (item in items) {
                    if (item.length < 100) continue
                    
                    // Extract detail link
                    val hrefPattern = Pattern.compile("href=\"(https?://$domain/[^\"]+)\"")
                    val hrefMatcher = hrefPattern.matcher(item)
                    if (!hrefMatcher.find()) continue
                    val detailLink = hrefMatcher.group(1)
                    
                    if (detailLink.contains("?s=") || detailLink.contains("/category/") || detailLink.contains("/tag/") || detailLink == "https://$domain/" || detailLink == "https://$domain") {
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
                        title = detailLink.trimEnd('/').split("/").last().replace("-", " ").capitalize()
                    }
                    
                    if (title.isNotEmpty() && detailLink.isNotEmpty()) {
                        val isSeries = title.lowercase().contains("temporada") || title.lowercase().contains("serie") || detailLink.contains("/tvshows/") || detailLink.contains("/series/")
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

    private fun fetchHtml(urlString: String): String? {
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
            if (conn.responseCode == 200) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun extractFirstResultLink(html: String, searchUrl: String): String? {
        val domain = if (searchUrl.contains("tioplus.app")) "tioplus.app" else "repelis24.ing"
        
        val pattern = Pattern.compile("href=\"(https?://$domain/[^\"]+)\"")
        val matcher = pattern.matcher(html)
        while (matcher.find()) {
            val link = matcher.group(1)
            if (!link.contains("?s=") && !link.contains("/category/") && !link.contains("/tag/") && link != "https://$domain/" && link != "https://$domain") {
                return link
            }
        }
        return null
    }

    private fun extractVideoStreams(html: String): List<String> {
        val list = mutableListOf<String>()
        
        // 1. Direct Video URLs (mp4, mkv, m3u8)
        val videoPattern = Pattern.compile("(https?://[^\\s\"'<>]+\\.(?:mp4|mkv|m3u8)[^\\s\"'<>]*)", Pattern.CASE_INSENSITIVE)
        val videoMatcher = videoPattern.matcher(html)
        while (videoMatcher.find()) {
            list.add(videoMatcher.group(1))
        }
        
        // 2. Iframe sources (look for embed, fembed, player, mixdrop, etc.)
        val iframePattern = Pattern.compile("<iframe[^>]+src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
        val iframeMatcher = iframePattern.matcher(html)
        while (iframeMatcher.find()) {
            val src = iframeMatcher.group(1)
            if (src.contains("embed") || src.contains("player") || src.contains("video") || src.contains("stream") || src.contains("drive") || src.contains("dropbox")) {
                list.add(src)
            }
        }
        
        return list
    }

    suspend fun resolveWebVideoUrl(webUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val html = fetchHtml(webUrl) ?: return@withContext null
            
            // 1. First, search for standard HTML5 source tags like: <source src="https://..." type="video/mp4">
            val sourcePattern = Pattern.compile("<source[^>]+src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
            val sourceMatcher = sourcePattern.matcher(html)
            while (sourceMatcher.find()) {
                val src = sourceMatcher.group(1)
                if (src.contains(".mp4") || src.contains(".m3u8") || src.contains(".mkv")) {
                    return@withContext src
                }
            }
            
            // 2. Fallback: Search for any raw mp4, mkv or m3u8 link in the HTML page source
            val streams = extractVideoStreams(html)
            if (streams.isNotEmpty()) {
                return@withContext streams[0]
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}

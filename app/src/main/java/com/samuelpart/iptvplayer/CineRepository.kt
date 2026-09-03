package com.samuelpart.iptvplayer

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object CineRepository {
    const val TMDB_API_KEY = "9decd750b7484d309d1ad88b5239ef93"

    /**
     * MASTER CATALOG, hot-updated from GitHub: editing
     * app/src/main/res/raw/cine_catalog.m3u in the repo (even from the browser)
     * is enough to add/remove movies & series in every installed app the next
     * time it opens. No reinstall, no APK update. The last downloaded copy is
     * cached on disk and the bundled copy in the APK remains as final fallback.
     */
    private const val CATALOG_CACHE_FILE = "cine_catalog_remote.m3u"
    private val CATALOG_URLS = listOf(
        "https://raw.githubusercontent.com/SamuelPart/iptv/arena/01a04133-iptv/app/src/main/res/raw/cine_catalog.m3u",
        "https://raw.githubusercontent.com/SamuelPart/iptv/main/app/src/main/res/raw/cine_catalog.m3u"
    )

    private var cachedCatalog: List<CineMedia>? = null

    /** Real episode labels only appear at the START of the name.
     *  A movie like "La guerra de las galaxias. Episodio IV..." must stay a movie. */
    private val EPISODE_NAME = Regex("""^\s*(ep[ií]s?odio|cap[ií]tulo|cap|ep)[:\s.\-]*\d+|^\s*[st]\d{1,2}e\d{1,3}""", RegexOption.IGNORE_CASE)

    /** Direct video files must NEVER be treated as playlists/series. */
    private val DIRECT_VIDEO_EXTENSIONS = listOf(
        ".mp4", ".mkv", ".webm", ".m3u8", ".mpd", ".mov",
        ".avi", ".ts", ".flv", ".wmv", ".mpg", ".mpeg", ".m4v"
    )

    private val CATALOG_URL_REWRITES = emptyMap<String, String>()

    private fun rewriteUrl(u: String): String = CATALOG_URL_REWRITES[u] ?: u

    fun looksLikeDirectVideo(url: String): Boolean {
        val lower = url.lowercase().substringBefore('?').substringBefore('#')
        return DIRECT_VIDEO_EXTENSIONS.any { lower.endsWith(it) }
    }

    fun isRemotePlaylist(url: String): Boolean {
        // A direct .mp4/.mkv on archive.org or GitHub is a MOVIE, not a playlist
        if (looksLikeDirectVideo(url)) return false
        val lower = url.lowercase()
        return lower.endsWith(".m3u") || lower.contains(".m3u?") || lower.contains("raw.githubusercontent.com") || lower.contains("archive.org/download") || lower.contains("github.com") || lower.endsWith(".txt") || lower.contains(".txt?")
    }

    suspend fun getCineCatalog(context: Context): List<CineMedia> = withContext(Dispatchers.IO) {
        if (cachedCatalog != null) return@withContext cachedCatalog!!

        val movies = mutableListOf<CineMedia>()
        val episodes = mutableListOf<ParsedEpisode>()
        val tvShows = mutableListOf<CineMedia>()

        // 1. Master catalog: freshest copy wins. Try downloading the updated
        // catalog from GitHub right now (a few seconds); if offline or it fails,
        // use the last downloaded copy on disk; final fallback: the bundled one.
        try {
            val freshText = downloadRemoteCatalog(context)
            val diskText = if (freshText == null) readCachedCatalog(context) else null
            val reader = when {
                freshText != null -> BufferedReader(StringReader(freshText))
                diskText != null -> BufferedReader(StringReader(diskText))
                else -> BufferedReader(InputStreamReader(context.resources.openRawResource(R.raw.cine_catalog)))
            }
            parseM3uStream(reader, movies, episodes, tvShows)
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
            // Absolute fallback: bundled catalog inside the APK
            try {
                val reader = BufferedReader(InputStreamReader(context.resources.openRawResource(R.raw.cine_catalog)))
                parseM3uStream(reader, movies, episodes, tvShows)
                reader.close()
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }

        // 2. Asynchronously fetch and parse the remote Bflix list from GitHub in real-time,
        // and seamlessly merge all of its individual movies & series directly into the main lists!
        val remoteUrl = "https://raw.githubusercontent.com/BrianRVP/Bflix34567/main/lista%20iptv%20de%20peliculas.txt"
        try {
            val url = java.net.URL(remoteUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                parseM3uStream(reader, movies, episodes, tvShows)
                reader.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Group episodes by TV show
        val episodesByShow = episodes.groupBy {
            val grp = it.group.lowercase()
            when {
                grp.contains("lucifer") -> "Lucifer"
                grp.contains("vikings") -> "Vikings"
                else -> it.group.split("Temporada")[0].trim()
            }
        }

        for ((showName, showEpisodes) in episodesByShow) {
            val showLogo = showEpisodes.firstOrNull()?.rawLogo ?: ""
            val mappedEpisodes = showEpisodes.map { ep ->
                val seasonNum = extractSeasonNumber(ep.group)
                val epNum = extractEpisodeNumber(ep.title)
                Episode(
                    title = ep.title,
                    url = ep.url,
                    rawLogo = ep.rawLogo,
                    season = seasonNum,
                    episodeNumber = epNum
                )
            }.sortedWith(compareBy<Episode> { it.season }.thenBy { it.episodeNumber })

            tvShows.add(
                CineMedia(
                    title = showName,
                    searchTitle = showName,
                    url = "",
                    rawLogo = showLogo,
                    type = "series",
                    group = "Series",
                    episodes = mappedEpisodes
                )
            )
        }

        // Group duplicates into alternate servers!
        val groupedMovies = movies.groupBy { it.searchTitle.lowercase().trim() }
        val uniqueMovies = mutableListOf<CineMedia>()

        for ((_, movieGroup) in groupedMovies) {
            val first = movieGroup.first()
            val allUrls = movieGroup.map { it.url }.distinct()
            
            val uniqueMovie = CineMedia(
                title = first.title,
                searchTitle = first.searchTitle,
                url = first.url,
                rawLogo = first.rawLogo,
                type = "movie",
                group = first.group,
                urls = ArrayList(allUrls)
            )
            uniqueMovies.add(uniqueMovie)
        }

        val fullCatalog = uniqueMovies + tvShows

        cachedCatalog = fullCatalog

        // Start non-blocking background pre-fetching for TMDb metadata/images
        launchBackgroundPrefetch(fullCatalog)

        return@withContext fullCatalog
    }

    /** Warms the catalog as soon as the app starts so the Cine tab opens instantly. */
    suspend fun prefetchCatalog(context: Context) {
        getCineCatalog(context)
    }

    /** Downloads the master catalog from GitHub and persist it on disk. Returns null if it fails. */
    private fun downloadRemoteCatalog(context: Context): String? {
        for (catalogUrl in CATALOG_URLS) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(catalogUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", "IPTV-Catalog/1.0")
                conn.setRequestProperty("Cache-Control", "no-cache")
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    if (isValidCatalog(text)) {
                        try {
                            File(context.filesDir, CATALOG_CACHE_FILE).writeText(text)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        return text
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        return null
    }

    /** Last successfully downloaded catalog, for offline/cold starts. */
    private fun readCachedCatalog(context: Context): String? {
        return try {
            val f = File(context.filesDir, CATALOG_CACHE_FILE)
            if (!f.exists()) return null
            val text = f.readText()
            if (isValidCatalog(text)) text else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Basic sanity check so a 404 page or truncated download never wipes the catalog. */
    private fun isValidCatalog(text: String): Boolean {
        if (!text.contains("#EXTM3U")) return false
        var count = 0
        var idx = 0
        while (count < 10) {
            idx = text.indexOf("#EXTINF", idx)
            if (idx == -1) break
            count++
            idx += 7
        }
        return count >= 10
    }

    private fun parseM3uStream(
        reader: BufferedReader,
        movies: MutableList<CineMedia>,
        episodes: MutableList<ParsedEpisode>,
        tvShows: MutableList<CineMedia>
    ) {
        var line: String?
        var currentName = ""
        var currentLogo = ""
        var currentGroup = ""
        var hasMetadata = false

        while (reader.readLine().also { line = it } != null) {
            val trimmed = line!!.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#EXTM3U")) continue
            if (trimmed.startsWith("++***")) continue
            
            if (trimmed.startsWith("#EXTINF:")) {
                currentLogo = parseAttribute(trimmed, "tvg-logo") ?: parseAttribute(trimmed, "logo") ?: ""
                currentGroup = parseAttribute(trimmed, "group-title") ?: ""
                
                val commaIndex = trimmed.lastIndexOf(',')
                if (commaIndex != -1 && commaIndex < trimmed.length - 1) {
                    val contentPart = trimmed.substring(commaIndex + 1).trim()
                    
                    // Check if it's the inline format
                    val inlineParts = contentPart.split("++").filter { it.trim().isNotEmpty() }
                    if (inlineParts.size >= 2) {
                        val name = inlineParts[0].trim()
                        val url = rewriteUrl(inlineParts[1].trim())
                        
                        val isSeries = currentGroup.lowercase().contains("temporada") ||
                                       EPISODE_NAME.containsMatchIn(name) ||
                                       isRemotePlaylist(url)
                        
                        if (isSeries) {
                            if (isRemotePlaylist(url)) {
                                tvShows.add(
                                    CineMedia(
                                        title = name,
                                        searchTitle = cleanMediaTitle(name),
                                        url = url,
                                        rawLogo = currentLogo,
                                        type = "series",
                                        group = "Series"
                                    )
                                )
                            } else {
                                episodes.add(ParsedEpisode(name, url, currentLogo, currentGroup))
                            }
                        } else {
                            movies.add(CineMedia(name, cleanMediaTitle(name), url, currentLogo, "movie", currentGroup))
                        }
                        hasMetadata = false
                    } else {
                        currentName = contentPart
                        hasMetadata = true
                    }
                }
            } else if (!trimmed.startsWith("#") && hasMetadata) {
                val url = rewriteUrl(trimmed)
                val isSeries = currentGroup.lowercase().contains("temporada") ||
                               EPISODE_NAME.containsMatchIn(currentName) ||
                               isRemotePlaylist(url)
                               
                if (isSeries) {
                    if (isRemotePlaylist(url)) {
                        tvShows.add(
                            CineMedia(
                                title = currentName,
                                searchTitle = cleanMediaTitle(currentName),
                                url = url,
                                rawLogo = currentLogo,
                                type = "series",
                                group = "Series"
                            )
                        )
                    } else {
                        episodes.add(ParsedEpisode(currentName, url, currentLogo, currentGroup))
                    }
                } else {
                    movies.add(CineMedia(currentName, cleanMediaTitle(currentName), url, currentLogo, "movie", currentGroup))
                }
                hasMetadata = false
            }
        }
    }

    private var cachedBundled: List<CineMedia>? = null

    /** Parse solo del catalogo empaquetado (sin red): para pintar el premier al instante. */
    suspend fun getBundledQuickCatalog(context: Context): List<CineMedia> = withContext(Dispatchers.IO) {
        cachedBundled?.let { return@withContext it }
        val movies = mutableListOf<CineMedia>()
        val episodes = mutableListOf<ParsedEpisode>()
        val tvShows = mutableListOf<CineMedia>()
        try {
            val reader = BufferedReader(InputStreamReader(context.resources.openRawResource(R.raw.cine_catalog)))
            parseM3uStream(reader, movies, episodes, tvShows)
            reader.close()
        } catch (_: Exception) { }
        val quick = ArrayList<CineMedia>(movies.size + tvShows.size)
        quick.addAll(movies)
        quick.addAll(tvShows)
        cachedBundled = quick
        quick
    }

    private fun launchBackgroundPrefetch(catalog: List<CineMedia>) {
        CoroutineScope(Dispatchers.IO).launch {
            // Only warm the first screens worth of posters: TMDB allows ~40 req/10s,
            // detail screens fetch their own data on demand, and hammering 9k titles
            // gets the API key rate-limited (which used to blank out everything).
            for (media in catalog.take(150)) {
                try {
                    if (media.tmdbId == null) {
                        fetchTmdMetadata(media)
                        kotlinx.coroutines.delay(100)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun parseAttribute(line: String, attributeName: String): String? {
        val patterns = listOf(
            Regex("""$attributeName\s*=\s*"([^"]*)""""),
            Regex("""$attributeName\s*=\s*'([^']*)'""")
        )
        for (pattern in patterns) {
            val match = pattern.find(line)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun extractSeasonNumber(group: String): Int {
        val regex = Regex("""Temporada\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(group)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    private fun extractEpisodeNumber(title: String): Int {
        val regex = Regex("""Episodio\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(title)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    fun extractSeasonFromTitle(title: String): Int {
        val sPattern = Regex("""S\s*(\d+)""", RegexOption.IGNORE_CASE)
        sPattern.find(title)?.let { return it.groupValues[1].toInt() }
        
        val xPattern = Regex("""(\d+)\s*[xX]\s*\d+""")
        xPattern.find(title)?.let { return it.groupValues[1].toInt() }
        
        val tempPattern = Regex("""Temp(?:orada)?\s*(\d+)""", RegexOption.IGNORE_CASE)
        tempPattern.find(title)?.let { return it.groupValues[1].toInt() }
        
        return 1
    }

    fun extractEpisodeFromTitle(title: String, defaultNum: Int): Int {
        val ePattern = Regex("""E\s*P?(?:isodio)?\s*(\d+)""", RegexOption.IGNORE_CASE)
        ePattern.find(title)?.let { return it.groupValues[1].toInt() }
        
        val xPattern = Regex("""\d+\s*[xX]\s*(\d+)""")
        xPattern.find(title)?.let { return it.groupValues[1].toInt() }
        
        val capPattern = Regex("""Cap(?:itulo)?\s*(\d+)""", RegexOption.IGNORE_CASE)
        capPattern.find(title)?.let { return it.groupValues[1].toInt() }
        
        val numPattern = Regex("""\b(\d+)\s*$""")
        numPattern.find(title)?.let { return it.groupValues[1].toInt() }
        
        return defaultNum
    }

    private fun cleanMediaTitle(title: String): String {
        return title.replace(Regex("""\s+LEG\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+DUB\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""–\s+A Vingança de Salazar""", RegexOption.IGNORE_CASE), "")
            .replace(Regex(""":\s+O Círculo Dourado""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+PRO\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\(20\d\d\)\s*$"""), "")
            .trim()
    }

    private val YEAR_IN_TITLE = Regex("""\b(19\d{2}|20\d{2})\b""")

    /** One TMDB search round. Returns the results array, or null if empty/failed. */
    private fun tmdbSearch(typePath: String, query: String, language: String): org.json.JSONArray? {
        var conn: HttpURLConnection? = null
        try {
            val encQ = URLEncoder.encode(query, "UTF-8")
            val urlString = "https://api.themoviedb.org/3/search/$typePath?api_key=$TMDB_API_KEY&query=$encQ&language=$language"
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONObject(jsonStr).optJSONArray("results")
                if (arr != null && arr.length() > 0) return arr
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
        return null
    }

    /** Among the top results, prefer the one whose release year matches the title's year
     *  (protects against remakes: "Paprika (1991)" must not load the 2006 anime). */
    private fun pickBestResult(results: org.json.JSONArray, year: String?): JSONObject {
        if (year != null) {
            val limit = minOf(results.length(), 6)
            for (i in 0 until limit) {
                val r = results.getJSONObject(i)
                val date = r.optString("release_date", "").ifEmpty { r.optString("first_air_date", "") }
                if (date.startsWith(year)) return r
            }
        }
        return results.getJSONObject(0)
    }

    /** Estilo visual de los grandes servicios. */
    private fun stylizeProvider(name: String): String {
        val n = name.lowercase()
        return when {
            "netflix" in n -> "NETFLIX"
            "disney" in n -> "DISNEY+"
            "hbo" in n || "max" in n -> "MAX"
            "prime" in n || "amazon" in n -> "PRIME VIDEO"
            "apple" in n -> "APPLE TV+"
            "hulu" in n -> "HULU"
            "paramount" in n -> "PARAMOUNT+"
            "crunchy" in n -> "CRUNCHYROLL"
            "tubi" in n -> "TUBI"
            "peacock" in n -> "PEACOCK"
            else -> name.uppercase()
        }
    }

    /** Plataforma real donde se transmite (TMDB watch/providers). Pone en
     *  media.platformName el primer flatrate de la region (PE, si no US). */
    fun fetchWatchProviders(media: CineMedia) {
        val id = media.tmdbId ?: return
        val typePath = if (media.type == "movie") "movie" else "tv"
        var conn: HttpURLConnection? = null
        try {
            val urlString = "https://api.themoviedb.org/3/$typePath/$id/watch/providers?api_key=$TMDB_API_KEY"
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val results = json.optJSONObject("results") ?: return
                val region = results.optJSONObject("PE") ?: results.optJSONObject("US") ?: return
                val flat = region.optJSONArray("flatrate")
                    ?: region.optJSONArray("free")
                    ?: region.optJSONArray("rent")
                    ?: return
                if (flat.length() > 0) {
                    // Preferir el primer provider de marca reconocible
                    var pick = flat.getJSONObject(0)
                    val known = listOf("netflix", "disney", "hbo", "max", "prime", "amazon", "apple", "hulu", "paramount", "crunchy", "tubi", "peacock")
                    for (i in 0 until flat.length()) {
                        val o = flat.getJSONObject(i)
                        if (known.any { it in o.optString("provider_name", "").lowercase() }) { pick = o; break }
                    }
                    val name = pick.optString("provider_name", "")
                    if (name.isNotEmpty()) media.platformName = stylizeProvider(name)
                    val lp = pick.optString("logo_path", "")
                    if (lp.isNotEmpty()) media.platformLogoUrl = "https://image.tmdb.org/t/p/original$lp"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    fun fetchTmdMetadata(media: CineMedia) {
        try {
            val typePath = if (media.type == "movie") "movie" else "tv"
            val year = YEAR_IN_TITLE.find(media.title)?.groupValues?.getOrNull(1)
            val base = media.searchTitle.trim()
            // Simplified variant: before ":", without "(...)" groups
            val simplified = base.substringBefore(':')
                .replace(Regex("""\([^()]*\)"""), "")
                .trim()
                .ifEmpty { base }

            // Fallback chain verified against the real API:
            // es title -> en title -> simplified es -> simplified en
            var usedLanguage = "es"
            var results = tmdbSearch(typePath, base, "es")
            if (results == null) {
                usedLanguage = "en-US"
                results = tmdbSearch(typePath, base, usedLanguage)
            }
            if (results == null && simplified != base) {
                usedLanguage = "es"
                results = tmdbSearch(typePath, simplified, usedLanguage)
            }
            if (results == null && simplified != base) {
                usedLanguage = "en-US"
                results = tmdbSearch(typePath, simplified, usedLanguage)
            }

            if (results != null) {
                val firstResult = pickBestResult(results, year)
                media.tmdbId = firstResult.optInt("id")
                media.overview = firstResult.optString("overview")

                // Spanish overview missing -> backfill from English (best effort)
                if (media.overview.isNullOrEmpty() && usedLanguage == "es") {
                    val enResults = tmdbSearch(typePath, base, "en-US")
                    if (enResults != null) {
                        val enPick = pickBestResult(enResults, year)
                        media.overview = enPick.optString("overview")
                    }
                }

                val posterPath = firstResult.optString("poster_path")
                if (!posterPath.isNullOrEmpty() && posterPath != "null") {
                    media.posterUrl = "https://image.tmdb.org/t/p/w500$posterPath"
                } else {
                    media.posterUrl = media.rawLogo
                }

                val backdropPath = firstResult.optString("backdrop_path")
                if (!backdropPath.isNullOrEmpty() && backdropPath != "null") {
                    media.backdropUrl = "https://image.tmdb.org/t/p/w780$backdropPath"
                } else {
                    media.backdropUrl = media.rawLogo
                }

                media.rating = firstResult.optDouble("vote_average", 0.0)
                media.releaseDate = firstResult.optString("release_date", "")
                if (media.releaseDate.isNullOrEmpty()) media.releaseDate = firstResult.optString("first_air_date", "")
            } else {
                media.posterUrl = media.rawLogo
                media.backdropUrl = media.rawLogo
            }
        } catch (e: Exception) {
            e.printStackTrace()
            media.posterUrl = media.rawLogo
            media.backdropUrl = media.rawLogo
        }
    }

    fun fetchTmdTrailer(media: CineMedia) {
        val tmdbId = media.tmdbId ?: return
        try {
            val typeStr = if (media.type == "movie") "movie" else "tv"
            var urlString = "https://api.themoviedb.org/3/$typeStr/$tmdbId/videos?api_key=$TMDB_API_KEY&language=es"
            var key = getTrailerKeyFromJson(urlString)
            
            if (key == null) {
                urlString = "https://api.themoviedb.org/3/$typeStr/$tmdbId/videos?api_key=$TMDB_API_KEY"
                key = getTrailerKeyFromJson(urlString)
            }
            
            if (key != null) {
                media.trailerUrl = "https://www.youtube.com/watch?v=$key"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Duracion real segun TMDb (segundos) para el extractor con firma. */
    fun fetchRuntimeSeconds(tmdbId: Int, type: String): Int? {
        return try {
            val typeStr = if (type == "movie") "movie" else "tv"
            val url = URL(
                "https://api.themoviedb.org/3/$typeStr/$tmdbId?api_key=$TMDB_API_KEY&language=es"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val min = if (typeStr == "movie") {
                json.optInt("runtime", 0)
            } else {
                val arr = json.optJSONArray("episode_run_time")
                if (arr != null && arr.length() > 0) arr.optInt(0, 0) else 0
            }
            if (min > 0) min * 60 else null
        } catch (_: Exception) { null }
    }

    fun fetchTmdCredits(media: CineMedia) {
        val tmdbId = media.tmdbId ?: return
        try {
            val typeStr = if (media.type == "movie") "movie" else "tv"
            val urlString = "https://api.themoviedb.org/3/$typeStr/$tmdbId/credits?api_key=$TMDB_API_KEY&language=es"
            
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val response = JSONObject(jsonStr)
                val castArray = response.optJSONArray("cast")
                if (castArray != null && castArray.length() > 0) {
                    val castList = mutableListOf<CastMember>()
                    val maxMembers = minOf(castArray.length(), 6) // Get top 6 cast members
                    for (i in 0 until maxMembers) {
                        val member = castArray.getJSONObject(i)
                        val name = member.optString("name", "")
                        val character = member.optString("character", "")
                        val profilePath = member.optString("profile_path")
                        val profileUrl = if (!profilePath.isNullOrEmpty() && profilePath != "null") {
                            "https://image.tmdb.org/t/p/w185$profilePath"
                        } else {
                            null
                        }
                        castList.add(CastMember(name, character, profileUrl))
                    }
                    media.cast = castList
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getTrailerKeyFromJson(urlString: String): String? {
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val response = JSONObject(jsonStr)
                val results = response.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    var fallbackKey: String? = null
                    for (i in 0 until results.length()) {
                        val video = results.getJSONObject(i)
                        val site = video.optString("site", "")
                        val type = video.optString("type", "")
                        val key = video.optString("key", "")
                        
                        if (site.lowercase() == "youtube" && key.isNotEmpty()) {
                            if (type.lowercase() == "trailer") {
                                return key
                            }
                            fallbackKey = key
                        }
                    }
                    return fallbackKey
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    suspend fun fetchEpisodesFromPlaylist(playlistUrl: String): List<Episode> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Episode>()
        try {
            val url = URL(playlistUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                var line: String?
                var currentTitle = ""
                var currentLogo = ""
                var hasMetadata = false
                
                var count = 1
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#EXTM3U")) continue
                    
                    if (trimmed.startsWith("#EXTINF:")) {
                        currentLogo = parseAttribute(trimmed, "tvg-logo") ?: parseAttribute(trimmed, "logo") ?: ""
                        val commaIndex = trimmed.lastIndexOf(',')
                        if (commaIndex != -1 && commaIndex < trimmed.length - 1) {
                            currentTitle = trimmed.substring(commaIndex + 1).trim()
                            hasMetadata = true
                        }
                    } else if (!trimmed.startsWith("#") && hasMetadata) {
                        val videoUrl = trimmed
                        val season = extractSeasonFromTitle(currentTitle)
                        val epNum = extractEpisodeFromTitle(currentTitle, count)
                        list.add(
                            Episode(
                                title = currentTitle,
                                url = videoUrl,
                                rawLogo = currentLogo,
                                season = season,
                                episodeNumber = epNum
                            )
                        )
                        count++
                        hasMetadata = false
                    }
                }
                reader.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext list
    }

    suspend fun fetchUrlsFromPlaylist(playlistUrl: String): List<String> = withContext(Dispatchers.IO) {
        val list = mutableListOf<String>()
        try {
            val url = URL(playlistUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    list.add(trimmed)
                }
                reader.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext list
    }
}

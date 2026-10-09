package com.samuelpart.iptvplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plataformas de streaming reconocidas: empareja el catalogo (grupo del M3U,
 * platformName de TMDB o titulo) con las plataformas mayores, y trae sus
 * LOGOS oficiales desde TMDB /watch/providers (actualizable en caliente).
 * Tambien define las SAGAS (franquicias) para la seccion de Inicio.
 */
object PlatformCatalog {

    data class Platform(
        val key: String,
        val name: String,
        val tmdbNames: List<String>,   // nombres en TMDB watch/providers
        val aliases: List<String>      // matching en grupo / platformName
    ) {
        /** Alias adicionales para buscar en el TITULO (sin ambiguos como "max"). */
        val titleAliases: List<String> get() = aliases.filter { it != "max" }
    }

    val ALL: List<Platform> = listOf(
        Platform("netflix", "Netflix", listOf("Netflix"), listOf("netflix")),
        Platform("disney", "Disney+", listOf("Disney Plus", "Disney+"), listOf("disney")),
        Platform("max", "Max", listOf("Max", "HBO Max", "HBO"), listOf("hbo", "max")),
        Platform("prime", "Prime Video", listOf("Amazon Prime Video"), listOf("prime", "amazon")),
        Platform("apple", "Apple TV+", listOf("Apple TV+", "Apple TV Plus"), listOf("apple")),
        Platform("hulu", "Hulu", listOf("Hulu"), listOf("hulu")),
        Platform("paramount", "Paramount+", listOf("Paramount Plus", "Paramount+"), listOf("paramount")),
        Platform("peacock", "Peacock", listOf("Peacock"), listOf("peacock")),
        Platform("skyshowtime", "SkyShowtime", listOf("SkyShowtime"), listOf("skyshowtime")),
        Platform("movistar", "Movistar Plus+", listOf("Movistar Plus+", "Movistar Plus"), listOf("movistar"))
    )

    /** key -> logo TMDB (w154). Se llena con fetchLogos(). */
    var logoByKey: Map<String, String> = emptyMap()
        private set

    /** Logo de plataforma de un titulo (o null si no pertenece a ninguna). */
    fun logoOf(m: CineMedia): String? = keyOf(m)?.let { logoByKey[it] }

    /** Detecta la plataforma de un titulo: primero el GRUPO del M3U, luego
     *  platformName de TMDB y por ultimo el titulo (sin alias ambiguos). */
    fun keyOf(m: CineMedia): String? {
        val group = m.group.lowercase()
        val pname = m.platformName?.lowercase() ?: ""
        for (p in ALL) {
            if (p.aliases.any { group.contains(it) || pname.contains(it) }) return p.key
        }
        val title = "${m.title} ${m.searchTitle}".lowercase()
        for (p in ALL) {
            if (p.titleAliases.any { title.contains(it) }) return p.key
        }
        return null
    }

    /** Trae los logos oficiales de las plataformas (regiones US y ES). */
    suspend fun fetchLogos(apiKey: String) {
        if (logoByKey.isNotEmpty()) return
        val result = withContext(Dispatchers.IO) {
            val found = HashMap<String, String>()
            for (region in listOf("US", "ES")) {
                try {
                    val conn = URL(
                        "https://api.themoviedb.org/3/watch/providers/movie" +
                            "?api_key=$apiKey&watch_region=$region"
                    ).openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    if (conn.responseCode == 200) {
                        val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                            .optJSONArray("results")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val r = arr.optJSONObject(i) ?: continue
                                val name = r.optString("provider_name", "").lowercase()
                                val path = r.optString("logo_path", "")
                                if (name.isEmpty() || path.isEmpty()) continue
                                val p = ALL.firstOrNull { plat ->
                                    plat.tmdbNames.any { name == it.lowercase() } ||
                                        plat.aliases.any { name.contains(it) }
                                } ?: continue
                                if (!found.containsKey(p.key)) {
                                    found[p.key] = "https://image.tmdb.org/t/p/w154$path"
                                }
                            }
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
            found
        }
        if (result.isNotEmpty()) logoByKey = result
    }
}

/** Franquicias (sagas) reconocidas por palabras clave del titulo. */
object Sagas {

    val MAP: LinkedHashMap<String, List<String>> = linkedMapOf(
        "Rápidas y Furiosas" to listOf("fast & furious", "fast and furious", "rápidos y furiosos", "rapidos y furiosos", "rápido y furioso"),
        "Avengers" to listOf("avengers", "vengadores"),
        "Jurassic Park" to listOf("jurassic", "jurásica", "mundo jurásico"),
        "Misión Imposible" to listOf("misión imposible", "mision imposible", "mission: impossible"),
        "John Wick" to listOf("john wick"),
        "Harry Potter" to listOf("harry potter", "animales fantásticos"),
        "El Señor de los Anillos" to listOf("señor de los anillos", "el hobbit", "hobbit"),
        "Transformers" to listOf("transformers"),
        "Mad Max" to listOf("mad max"),
        "Expediente Warren" to listOf("expediente warren", "conjuring", "la monja"),
        "Bad Boys" to listOf("bad boys"),
        "Toy Story" to listOf("toy story"),
        "Terminator" to listOf("terminator"),
        "Rocky" to listOf("rocky", "creed")
    )

    /** Grupos reales del catalogo (>=2 titulos), la mas popular primero. */
    fun buildGroups(catalog: List<CineMedia>): List<Pair<String, List<CineMedia>>> {
        val out = ArrayList<Pair<String, List<CineMedia>>>()
        for ((name, keys) in MAP) {
            val items = catalog
                .filter { m -> keys.any { m.title.lowercase().contains(it) } }
                .sortedByDescending { it.releaseDate ?: "" }
            if (items.size >= 2) out.add(name to items)
        }
        return out.sortedByDescending { grp ->
            grp.second.maxOfOrNull { it.rating ?: 0.0 } ?: 0.0
        }
    }
}

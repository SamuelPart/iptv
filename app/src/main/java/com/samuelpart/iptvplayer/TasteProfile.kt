package com.samuelpart.iptvplayer

import android.content.Context

/**
 * TASTE PROFILE local (sin backend): la app aprende con el uso.
 *
 * Cada vez que el usuario ABRE una peli/serie (detalle), VE tiempo real en
 * el player, o GUARDA un favorito, el perfil sube puntos a sus actores y
 * generos persistidos en SharedPreferences. El deck del cine puntua
 * candidatos como: 3.0 * actores compartidos + 2.0 * generos compartidos
 * + 0.4 * rating TMDB. Cuanto mas se usa la app, mejor el mazo.
 */
object TasteProfile {

    private const val PREFS = "taste_profile_v1"
    private const val KEY_ACTORS = "actors"
    private const val KEY_GENRES = "genres"

    // lexico genero -> palabras clave (esp/eng) encontradas en titulo/sinopsis/grupo
    private val GENRE_KEYS: Map<String, List<String>> = linkedMapOf(
        "acción" to listOf("accion", "acción", "action", "aventura", "combate", "batalla", "giro", "asalto"),
        "ciencia ficción" to listOf("ciencia ficción", "sci-fi", "scifi", "alien", "ovni", "robot", "nave espacial", "distop", "time travel", "viaje en el tiempo", "matrix", "espacial"),
        "comedia" to listOf("comedia", "comedy", "humor", "parodia", "romance cómic", "feel good"),
        "drama" to listOf("drama", "melodrama", "biopic", "basada en hechos", "true story"),
        "terror" to listOf("terror", "horror", "scary", "maldici", "poseída", "demonio", "zombi", "asusta", "jumpscare"),
        "thriller" to listOf("thriller", "suspenso", "suspense", "detective", "misterio", "asesin", "policía", "crimen", "mafia", "cartel"),
        "fantasía" to listOf("fantasía", "fantasy", "magia", "hechicero", "dragones", "dragón", "hadas", "épica medieval"),
        "animación" to listOf("animación", "animated", "animation", "anime", "dibujos", "pixar", "ghibli"),
        "romance" to listOf("romance", "romántic", "amor", "love story", "boda"),
        "familia" to listOf("familia", "familiar", "kids", "infantil", "niños", "navideña"),
        "histórica" to listOf("histórica", "historical", "época", "period", "siglo", "guerra mundial", "napole"),
        "música" to listOf("musical", "música", "concierto", "concert", "rap", "jazz")
    )

    fun genreKeysOf(media: CineMedia): List<String> {
        val blob = "${media.title} ${media.searchTitle} ${media.group} ${media.overview ?: ""}".lowercase()
        return GENRE_KEYS.filter { (_, keys) -> keys.any { blob.contains(it) } }.keys.toList()
    }

    // ---------- persistencia ----------

    private fun load(context: Context, key: String): MutableMap<String, Int> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "") ?: return mutableMapOf()
        val out = mutableMapOf<String, Int>()
        raw.lines().forEach { line ->
            val parts = line.split("~")
            if (parts.size == 3) out[parts[1]] = parts[2].toIntOrNull() ?: 0
        }
        return out
    }

    private fun save(context: Context, key: String, map: Map<String, Int>) {
        val raw = map.entries.joinToString("\n") { "${key.uppercase().first()}~${it.key}~${it.value}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, raw).apply()
    }

    fun actors(context: Context): Map<String, Int> = load(context, KEY_ACTORS)
    fun genres(context: Context): Map<String, Int> = load(context, KEY_GENRES)

    // ---------- aprendizaje ----------

    /** Señal fuerte: abriste el detalle (curiosidad concreta). */
    fun recordOpen(context: Context, media: CineMedia) = bump(context, media, actorPts = 1, genrePts = 1)

    /** Señal mas fuerte: viste tiempo real en el player. */
    fun recordWatch(context: Context, media: CineMedia) = bump(context, media, actorPts = 2, genrePts = 2)

    /** La mas fuerte: marcaste favorito. */
    fun recordFavorite(context: Context, media: CineMedia) = bump(context, media, actorPts = 3, genrePts = 3)

    private fun bump(context: Context, media: CineMedia, actorPts: Int, genrePts: Int) {
        val actors = load(context, KEY_ACTORS)
        media.cast.take(6).forEach { c -> actors[c.name] = (actors[c.name] ?: 0) + actorPts }
        val genres = load(context, KEY_GENRES)
        genreKeysOf(media).forEach { g -> genres[g] = (genres[g] ?: 0) + genrePts }
        save(context, KEY_ACTORS, actors)
        save(context, KEY_GENRES, genres)
    }

    // ---------- recomendacion ----------

    /** actor matchs (nombres compartidos) con tu perfil */
    private fun sharedActors(context: Context, media: CineMedia): List<String> {
        val a = load(context, KEY_ACTORS)
        if (a.isEmpty()) return emptyList()
        return media.cast.map { it.name }.filter { a.containsKey(it) }.sortedByDescending { a[it] ?: 0 }
    }

    private fun sharedGenres(context: Context, media: CineMedia): List<String> {
        val g = load(context, KEY_GENRES)
        if (g.isEmpty()) return emptyList()
        return genreKeysOf(media).filter { g.containsKey(it) }.sortedByDescending { g[it] ?: 0 }
    }

    /** Score final: 3x actores + 2x generos + 0.4x rating TMDB. */
    fun score(context: Context, media: CineMedia): Double {
        val act = sharedActors(context, media).sumOf { actors(context)[it] ?: 0 }
        val gen = sharedGenres(context, media).sumOf { genres(context)[it] ?: 0 }
        valid(act); // no-op for smartcast warnings
        return 3.0 * act + 2.0 * gen + 0.4 * (media.rating ?: 0.0)
    }

    private fun valid(x: Int) {} // mantener linters felices

    /** Razón humana de por que esta carta: "con Zendaya" / "ciencia ficción" o null. */
    fun topReason(context: Context, media: CineMedia): String? {
        val act = sharedActors(context, media).firstOrNull()
        if (act != null) return "con $act"
        val gen = sharedGenres(context, media).firstOrNull()
        if (gen != null) return gen
        return null
    }
}

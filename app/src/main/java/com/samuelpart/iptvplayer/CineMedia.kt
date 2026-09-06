package com.samuelpart.iptvplayer

import java.io.Serializable

data class CineMedia(
    val title: String,
    val searchTitle: String,
    val url: String,
    val rawLogo: String,
    val type: String, // "movie" or "series"
    val group: String,
    var tmdbId: Int? = null,
    var overview: String? = null,
    var backdropUrl: String? = null,
    var posterUrl: String? = null,
    var rating: Double? = null,
    var releaseDate: String? = null,
    var episodes: List<Episode> = emptyList(),
    var urls: ArrayList<String> = arrayListOf(), // List of duplicate server URLs grouped under same title
    var trailerUrl: String? = null, // YouTube trailer link
    var platformName: String? = null, // proveedor de streaming (TMDB watch/providers)
    var platformLogoUrl: String? = null, // logo oficial del proveedor (image.tmdb.org original)
    var cast: List<CastMember> = emptyList() // Dynamic TMDb Cast and Crew list!
) : Serializable

data class CastMember(
    val name: String,
    val character: String,
    val profileUrl: String?
) : Serializable

data class Episode(
    val title: String,
    val url: String,
    val rawLogo: String,
    val season: Int,
    val episodeNumber: Int,
    var overview: String? = null,
    var stillUrl: String? = null
) : Serializable

data class ParsedEpisode(
    val title: String,
    val url: String,
    val rawLogo: String,
    val group: String
)

/**
 * Ficha técnica completa de una SERIE (TMDb /tv/{id}), con todos los campos
 * que muestra la pantalla de detalle: datos básicos, equipo, datos generales
 * y contenido.
 */
data class TvDetails(
    var originalTitle: String? = null,
    var localTitle: String? = null,
    var firstAirDate: String? = null,
    var lastAirDate: String? = null,
    var numberOfSeasons: Int = 0,
    var numberOfEpisodes: Int = 0,
    var episodeRuntime: Int? = null,
    var genres: List<String> = emptyList(),
    var originCountries: List<String> = emptyList(),
    var originalLanguage: String? = null,
    var overview: String? = null,
    var networks: List<String> = emptyList(),
    var productionCompanies: List<String> = emptyList(),
    var creators: List<String> = emptyList(),
    var cast: List<CastMember> = emptyList(),
    var director: String? = null,
    var writers: List<String> = emptyList(),
    var musicComposer: String? = null,
    var cinematographer: String? = null,
    var ageRating: String? = null,
    var advisories: List<String> = emptyList(),
    var posterUrl: String? = null,
    var backdropUrl: String? = null,
    var rating: Double? = null
) : Serializable

/**
 * Ficha técnica completa de una PELÍCULA (TMDb /movie/{id}), espejo de
 * [TvDetails] para que la pantalla de detalle de películas muestre los mismos
 * bloques profesionales: datos básicos, equipo, datos generales y contenido.
 */
data class MovieDetails(
    var originalTitle: String? = null,
    var localTitle: String? = null,
    var releaseDate: String? = null,
    var runtime: Int? = null,
    var genres: List<String> = emptyList(),
    var originCountries: List<String> = emptyList(),
    var originalLanguage: String? = null,
    var overview: String? = null,
    var productionCompanies: List<String> = emptyList(),
    var cast: List<CastMember> = emptyList(),
    var director: String? = null,
    var writers: List<String> = emptyList(),
    var musicComposer: String? = null,
    var cinematographer: String? = null,
    var ageRating: String? = null,
    var advisories: List<String> = emptyList(),
    var posterUrl: String? = null,
    var backdropUrl: String? = null,
    var rating: Double? = null
) : Serializable

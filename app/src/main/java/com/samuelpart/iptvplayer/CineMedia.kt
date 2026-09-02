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

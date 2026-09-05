package com.samuelpart.iptvplayer

import androidx.mediarouter.media.MediaRouter

data class CastDevice(
    val name: String,
    val ip: String,
    val port: Int,
    val type: String, // "Google Cast", "Roku", "DLNA"
    val routeInfo: MediaRouter.RouteInfo? = null,
    val controlUrl: String? = null
)

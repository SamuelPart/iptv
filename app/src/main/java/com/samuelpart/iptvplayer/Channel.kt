package com.samuelpart.iptvplayer

import java.io.Serializable

data class Channel(
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val group: String? = null,
    val country: String? = null,
    val language: String? = null // Dynamic parsed language name
) : Serializable

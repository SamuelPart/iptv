package com.samuelpart.iptvplayer

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

object M3UParser {
    fun parse(inputStream: InputStream): List<Channel> {
        val channels = mutableListOf<Channel>()
        val reader = BufferedReader(InputStreamReader(inputStream))
        var line: String?
        
        var currentName = ""
        var currentLogoUrl: String? = null
        var currentGroup: String? = null
        var currentCountry: String? = null
        var currentLanguage: String? = null
        var hasExtInf = false

        try {
            while (reader.readLine().also { line = it } != null) {
                val trimmedLine = line!!.trim()
                if (trimmedLine.isEmpty()) continue

                if (trimmedLine.startsWith("#EXTM3U")) {
                    continue
                }

                if (trimmedLine.startsWith("#EXTINF:")) {
                    hasExtInf = true
                    
                    // Parse tvg-logo
                    currentLogoUrl = parseAttribute(trimmedLine, "tvg-logo") ?: parseAttribute(trimmedLine, "logo")
                    
                    // Parse group-title
                    currentGroup = parseAttribute(trimmedLine, "group-title")
                    
                    // Parse country (tvg-country or country)
                    val rawCountry = parseAttribute(trimmedLine, "tvg-country") ?: parseAttribute(trimmedLine, "country")
                    currentCountry = getCountryNameWithEmoji(rawCountry) ?: currentGroup?.let { getCountryFromGroup(it) }
                    
                    // Parse language (tvg-language or language)
                    val rawLanguage = parseAttribute(trimmedLine, "tvg-language") ?: parseAttribute(trimmedLine, "language")
                    currentLanguage = getLanguageNameWithEmoji(rawLanguage) ?: getLanguageFromGroup(currentGroup)
                    
                    // Parse name (everything after the last comma)
                    val commaIndex = trimmedLine.lastIndexOf(',')
                    currentName = if (commaIndex != -1 && commaIndex < trimmedLine.length - 1) {
                        trimmedLine.substring(commaIndex + 1).trim()
                    } else {
                        parseAttribute(trimmedLine, "tvg-name") ?: "Canal Desconocido"
                    }
                } else if (!trimmedLine.startsWith("#")) {
                    // This must be the URL
                    if (hasExtInf && currentName.isNotEmpty()) {
                        channels.add(
                            Channel(
                                name = currentName,
                                url = trimmedLine,
                                logoUrl = currentLogoUrl,
                                group = currentGroup,
                                country = currentCountry,
                                language = currentLanguage
                            )
                        )
                    }
                    // Reset for next channel
                    currentName = ""
                    currentLogoUrl = null
                    currentGroup = null
                    currentCountry = null
                    currentLanguage = null
                    hasExtInf = false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                reader.close()
            } catch (e: Exception) {
                // Ignore
            }
        }

        return channels
    }

    private fun parseAttribute(line: String, attributeName: String): String? {
        val patterns = listOf(
            Regex("""$attributeName\s*=\s*"([^"]*)""""), // attribute="value"
            Regex("""$attributeName\s*=\s*'([^']*)'""")    // attribute='value'
        )
        for (pattern in patterns) {
            val match = pattern.find(line)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun getCountryNameWithEmoji(code: String?): String? {
        if (code == null || code.isEmpty()) return null
        return when (code.uppercase().trim()) {
            "ES" -> "España 🇪🇸"
            "MX" -> "México 🇲🇽"
            "AR" -> "Argentina 🇦🇷"
            "CO" -> "Colombia 🇨🇴"
            "CL" -> "Chile 🇨🇱"
            "PE" -> "Perú 🇵🇪"
            "VE" -> "Venezuela 🇻🇪"
            "EC" -> "Ecuador 🇪🇨"
            "US" -> "Estados Unidos 🇺🇸"
            "UY" -> "Uruguay 🇺🇾"
            "PY" -> "Paraguay 🇵🇾"
            "BO" -> "Bolivia 🇧🇴"
            "BR" -> "Brasil 🇧🇷"
            "IT" -> "Italia 🇮🇹"
            "FR" -> "Francia 🇫🇷"
            "DE" -> "Alemania 🇩🇪"
            "GB" -> "Reino Unido 🇬🇧"
            else -> null
        }
    }

    private fun getCountryFromGroup(group: String): String? {
        val lowercaseGroup = group.lowercase()
        return when {
            lowercaseGroup.contains("spain") || lowercaseGroup.contains("españa") -> "España 🇪🇸"
            lowercaseGroup.contains("mexico") || lowercaseGroup.contains("méxico") -> "México 🇲🇽"
            lowercaseGroup.contains("argentina") -> "Argentina 🇦🇷"
            lowercaseGroup.contains("colombia") -> "Colombia 🇨🇴"
            lowercaseGroup.contains("chile") -> "Chile 🇨🇱"
            lowercaseGroup.contains("peru") || lowercaseGroup.contains("perú") -> "Perú 🇵🇪"
            lowercaseGroup.contains("usa") || lowercaseGroup.contains("united states") || lowercaseGroup.contains("eeuu") -> "Estados Unidos 🇺🇸"
            lowercaseGroup.contains("venezuela") -> "Venezuela 🇻🇪"
            lowercaseGroup.contains("ecuador") -> "Ecuador 🇪🇨"
            else -> null
        }
    }

    private fun getLanguageNameWithEmoji(code: String?): String? {
        if (code == null || code.isEmpty()) return null
        return when (code.uppercase().trim()) {
            "ES", "SPA", "SPANISH" -> "Español 🇪🇸"
            "MX", "LAT", "LATIN" -> "Español Latino 🇲🇽"
            "EN", "ENG", "ENGLISH" -> "Inglés 🇬🇧"
            "FR", "FRE", "FRENCH" -> "Francés 🇫🇷"
            "IT", "ITA", "ITALIAN" -> "Italiano 🇮🇹"
            "DE", "GER", "GERMAN" -> "Alemán 🇩🇪"
            "PT", "POR", "PORTUGUESE" -> "Portugués 🇵🇹"
            else -> null
        }
    }

    private fun getLanguageFromGroup(group: String?): String? {
        if (group == null) return null
        val lowercaseGroup = group.lowercase()
        return when {
            lowercaseGroup.contains("latino") || lowercaseGroup.contains("latin") || lowercaseGroup.contains("mexico") -> "Español Latino 🇲🇽"
            lowercaseGroup.contains("castellano") || lowercaseGroup.contains("spain") || lowercaseGroup.contains("españa") || lowercaseGroup.contains("espanol") || lowercaseGroup.contains("español") -> "Español 🇪🇸"
            lowercaseGroup.contains("english") || lowercaseGroup.contains("inglés") || lowercaseGroup.contains("ingles") -> "Inglés 🇬🇧"
            lowercaseGroup.contains("french") || lowercaseGroup.contains("francés") || lowercaseGroup.contains("frances") -> "Francés 🇫🇷"
            else -> null
        }
    }
}

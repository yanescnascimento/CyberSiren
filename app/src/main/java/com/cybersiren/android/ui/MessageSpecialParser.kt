package com.cybersiren.android.ui

import androidx.compose.ui.text.AnnotatedString
import android.util.Patterns

object MessageSpecialParser {

    private val standaloneGeohashRegex = Regex("(^|[^A-Za-z0-9_#])#([0-9bcdefghjkmnpqrstuvwxyz]{2,})($|[^A-Za-z0-9_])", RegexOption.IGNORE_CASE)

    data class GeohashMatch(val start: Int, val endExclusive: Int, val geohash: String)
    data class UrlMatch(val start: Int, val endExclusive: Int, val url: String)

    fun findStandaloneGeohashes(text: String): List<GeohashMatch> {
        if (text.isEmpty()) return emptyList()
        val matches = mutableListOf<GeohashMatch>()
        var index = 0
        while (index < text.length) {
            val m = standaloneGeohashRegex.find(text, index) ?: break

            val fullRange = m.range

            val sub = text.substring(fullRange)
            val hashPos = sub.indexOf('#')
            if (hashPos >= 0) {
                val tokenStart = fullRange.first + hashPos

                var cursor = tokenStart + 1
                while (cursor < text.length) {
                    val ch = text[cursor].lowercaseChar()
                    val isGeoChar = (ch in '0'..'9') || (ch in "bcdefghjkmnpqrstuvwxyz")
                    if (!isGeoChar) break
                    cursor++
                }
                val token = text.substring(tokenStart + 1, cursor)
                if (token.length >= 2) {
                    matches.add(GeohashMatch(tokenStart, cursor, token.lowercase()))
                }
                index = cursor
            } else {
                index = fullRange.last + 1
            }
        }
        return matches
    }

    fun findUrls(text: String): List<UrlMatch> {
        if (text.isEmpty()) return emptyList()
        val results = mutableListOf<UrlMatch>()

        val webUrl = Patterns.WEB_URL
        val matcher = webUrl.matcher(text)
        while (matcher.find()) {
            var start = matcher.start()
            var endExclusive = matcher.end()
            var token = text.substring(start, endExclusive)

            while (token.isNotEmpty() && token.last() in setOf('.', ',', ';', ':', '!', '?', '\'', '"')) {
                endExclusive -= 1
                token = text.substring(start, endExclusive)
            }
            results.add(UrlMatch(start, endExclusive, token))
        }

        val bare = Regex("(?<!@)(?<![A-Za-z0-9_-])([A-Za-z0-9-]+\\.[A-Za-z]{2,}(?:/[A-Za-z0-9@:%._+~#=/?&!$'()*,-]*)?)")
        for (m in bare.findAll(text)) {
            val start = m.range.first
            var endExclusive = m.range.last + 1
            var token = text.substring(start, endExclusive)

            val overlapsExisting = results.any { start < it.endExclusive && endExclusive > it.start }
            if (overlapsExisting) continue

            if (token.contains('@')) continue

            while (token.isNotEmpty() && token.last() in setOf('.', ',', ';', ':', '!', '?', '\'', '"')) {
                endExclusive -= 1
                token = text.substring(start, endExclusive)
            }

            if (!token.contains('.')) continue

            results.add(UrlMatch(start, endExclusive, token))
        }

        results.sortBy { it.start }
        return results
    }
}

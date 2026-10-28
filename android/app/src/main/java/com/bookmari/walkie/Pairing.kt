package com.bookmari.walkie

import org.json.JSONObject

/** What the pairing QR holds ({"url":…,"key":…}), also accepted as pasted text in any of the connection fields. */
object Pairing {
    class Result(val url: String, val key: String)

    /**
     * The pairing in [text], or null when it isn't one. Tolerates text around the JSON (the "Or paste this…" line printed by
     * pair.ts --text, quotes or whitespace from a chat app) and needs both an address and a key, so a half pairing never wipes a field.
     */
    fun parse(text: String): Result? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < start) return null
        val j = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null
        val url = j.optString("url").trim()
        val key = j.optString("key").trim()
        if (url.isEmpty() || key.isEmpty()) return null
        return Result(normalizeUrl(url), key)
    }

    /** A bare address gets the http:// the app needs. */
    fun normalizeUrl(url: String) = if (url.isEmpty() || "://" in url) url else "http://$url"
}

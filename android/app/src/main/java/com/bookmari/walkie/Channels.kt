package com.bookmari.walkie

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.concurrent.thread

/**
 * Channels = walkie sessions on the PC, one per port: the paired address's port and the 9 after it
 * (see README, "Channels"). The first channel keeps the pre-channel file names, so old history stays put.
 */
object Channels {
    private const val COUNT = 10
    private const val PORT = "port" // active port; 0 = the paired one
    const val ANNOUNCE = "announce_channel" // speak the channel once the restarted walkie is ready

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(WalkieService.PREFS, Context.MODE_PRIVATE)

    /** "http://host" and the first channel's port, from the paired address. */
    private fun base(p: SharedPreferences): Pair<String, Int> {
        val u = runCatching { URI(p.getString("url", "")!!.trim().trimEnd('/')) }.getOrNull()
        if (u?.host == null) return "" to 7700
        return "${u.scheme ?: "http"}://${u.host}" to if (u.port > 0) u.port else 7700
    }

    fun port(ctx: Context): Int {
        val p = prefs(ctx)
        val first = base(p).second
        return p.getInt(PORT, 0).takeIf { it in first until first + COUNT } ?: first
    }

    fun number(ctx: Context) = port(ctx) - base(prefs(ctx)).second + 1
    fun url(ctx: Context) = base(prefs(ctx)).first + ":" + port(ctx)
    private fun first(ctx: Context, port: Int) = port == base(prefs(ctx)).second
    fun url(ctx: Context, port: Int) = base(prefs(ctx)).first + ":" + port
    fun talkFile(ctx: Context, port: Int = port(ctx)) = File(ctx.filesDir, if (first(ctx, port)) "talk.json" else "talk-$port.json")
    fun cursorKey(ctx: Context, port: Int = port(ctx)) = if (first(ctx, port)) "cursor" else "cursor-$port"

    /** Last name the channel gave; empty for a plugin from before channels. */
    fun name(ctx: Context, port: Int = port(ctx)) = prefs(ctx).getString("name-$port", "")!!
    fun label(ctx: Context, port: Int = port(ctx)) =
        ctx.getString(R.string.channel, port - base(prefs(ctx)).second + 1, name(ctx, port).ifEmpty { ctx.getString(R.string.channel_unnamed) })
    fun spoken(ctx: Context) = ctx.getString(R.string.channel_say, number(ctx), name(ctx))

    /** Ports that answered the last [scan]; null before the first one. */
    var live: List<Int>? = null

    /** Channels seen at least once (they answered a scan), plus the active one. */
    fun known(ctx: Context): List<Int> {
        val p = prefs(ctx)
        val first = base(p).second
        return (first until first + COUNT).filter { p.contains("name-$it") || it == port(ctx) }
    }

    /** Asks every channel port at once; returns the ports that answer, saving their names. Blocking: not on the main thread. */
    fun scan(ctx: Context): List<Int> {
        val p = prefs(ctx)
        val (host, first) = base(p)
        if (host.isEmpty()) return emptyList()
        val key = p.getString("key", "")!!
        val names = arrayOfNulls<String>(COUNT)
        (0 until COUNT).map { i ->
            thread {
                val c = runCatching { URL("$host:${first + i}/hello").openConnection() as HttpURLConnection }.getOrNull() ?: return@thread
                try {
                    c.connectTimeout = 1_500
                    c.readTimeout = 1_500
                    c.setRequestProperty("Authorization", "Bearer $key")
                    names[i] = when (c.responseCode) {
                        200 -> org.json.JSONObject(c.inputStream.bufferedReader().readText()).optString("name")
                        404 -> "" // a walkie plugin from before channels
                        else -> null
                    }
                } catch (_: Exception) {
                } finally {
                    c.disconnect()
                }
            }
        }.forEach { it.join() }
        val e = p.edit()
        names.forEachIndexed { i, n -> if (n != null) e.putString("name-${first + i}", n) }
        e.apply()
        return names.indices.filter { names[it] != null }.map { first + it }.also { live = it }
    }

    /** A running walkie moving to [port] by itself (WalkieService.FOLLOW): no restart, it already polls every channel. Main thread. */
    fun follow(ctx: Context, port: Int) {
        prefs(ctx).edit().putInt(PORT, port).apply()
        Talk.reload(talkFile(ctx))
    }

    /** Makes [port] the active channel: its conversation on screen; a running walkie restarts on it and says its name. */
    fun switchTo(a: android.app.Activity, port: Int) {
        prefs(a).edit().putInt(PORT, port).putBoolean(ANNOUNCE, WalkieService.instance != null).apply()
        Talk.reload(talkFile(a))
        if (WalkieService.instance != null) a.startWalkie()
    }
}

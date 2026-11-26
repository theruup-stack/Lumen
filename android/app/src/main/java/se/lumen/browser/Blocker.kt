package se.lumen.browser

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Annonsblockering: en mängd domäner för annonser och spårare.
 * Börjar med Lumens inbyggda lista och laddar sedan ner en fullständig
 * lista (StevenBlack/hosts, ~80 000 domäner) som förnyas varje vecka.
 */
object Blocker {
    @Volatile var enabled = true
    @Volatile private var domains: Set<String> = emptySet()
    private const val LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val WEEK = 7L * 24 * 60 * 60 * 1000

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        thread(name = "lumen-blocklist") {
            val builtin = try {
                app.assets.open("ui/blocklist.txt").bufferedReader().readLines()
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toSet()
            } catch (e: Exception) { emptySet() }
            domains = builtin
            val file = File(app.filesDir, "hosts.txt")
            if (file.exists()) domains = builtin + parse(file.readLines())
            if (!file.exists() || System.currentTimeMillis() - file.lastModified() > WEEK) {
                try {
                    val conn = URL(LIST_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    if (conn.responseCode == 200) {
                        val text = conn.inputStream.bufferedReader().readText()
                        val parsed = parse(text.lines())
                        if (parsed.size > 1000) {
                            file.writeText(text)
                            domains = builtin + parsed
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    // Ingen anslutning – fortsätt med det vi har.
                }
            }
        }
    }

    private fun parse(lines: List<String>): Set<String> {
        val out = HashSet<String>(lines.size)
        for (raw in lines) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            if (parts[0] != "0.0.0.0" && parts[0] != "127.0.0.1") continue
            val host = parts[1].lowercase()
            if (host == "localhost" || host == "0.0.0.0" || !host.contains('.')) continue
            out.add(host)
        }
        return out
    }

    fun blocked(host: String?): Boolean {
        if (!enabled || host.isNullOrEmpty()) return false
        val set = domains
        var h = host.lowercase()
        while (true) {
            if (set.contains(h)) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
            if (!h.contains('.')) return false
        }
    }
}

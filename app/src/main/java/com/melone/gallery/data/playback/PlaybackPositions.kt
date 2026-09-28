package com.melone.gallery.data.playback

import android.content.Context

/**
 * Dauerhafter Merker der Wiedergabeposition (für Filme). Speichert je Datei die zuletzt
 * gesehene Position in SharedPreferences, damit ein Film beim erneuten Öffnen dort
 * weiterläuft, wo man aufgehört hat. Nahe Anfang oder Ende wird nichts gemerkt, damit ein
 * kurz angetipptes oder fertig geschautes Video nicht mittendrin wieder startet.
 */
object PlaybackPositions {

    private const val PREFS = "playback_positions"
    private const val MIN_POSITION_MS = 10_000L // darunter lohnt sich kein Merken
    private const val END_MARGIN_MS = 15_000L   // in den letzten 15 s gilt der Film als fertig

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, key: String): Long =
        prefs(context).getLong(key, 0L).coerceAtLeast(0L)

    fun save(context: Context, key: String, positionMs: Long, durationMs: Long) {
        val nearEnd = durationMs > 0 && positionMs >= durationMs - END_MARGIN_MS
        val p = prefs(context)
        if (positionMs < MIN_POSITION_MS || nearEnd) {
            p.edit().remove(key).apply()
        } else {
            p.edit().putLong(key, positionMs).apply()
        }
    }

    fun clear(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }
}

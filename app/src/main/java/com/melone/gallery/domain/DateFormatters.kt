package com.melone.gallery.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Deutsche Datums-/Zeitformate wie in der Referenz-Galerie. */
object DateFormatters {

    private val locale = Locale.GERMANY
    private val dayFormat = SimpleDateFormat("EEEE, d. MMMM yyyy", locale)
    private val monthFormat = SimpleDateFormat("MMMM yyyy", locale)
    private val fullFormat = SimpleDateFormat("d. MMMM yyyy, HH:mm", locale)

    fun dayHeader(ts: Long): String = when {
        isSameDay(ts, now()) -> "Heute"
        isSameDay(ts, now() - DAY_MS) -> "Gestern"
        else -> dayFormat.format(Date(ts))
    }

    fun monthHeader(ts: Long): String = monthFormat.format(Date(ts))

    fun fullDateTime(ts: Long): String = fullFormat.format(Date(ts))

    private fun now(): Long = System.currentTimeMillis()

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}

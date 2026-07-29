package com.melone.gallery.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * Begrenzt die Breite im Querformat auf 16:9 (bezogen auf die Bildschirmhöhe) und
 * lässt das Hochformat unverändert. Moderne Handys sind quer deutlich breiter als
 * 16:9; ohne Begrenzung laufen Leisten/Raster bis an beide Ränder (und damit unter
 * die Systemleisten). Der Aufrufer zentriert den Inhalt (z. B. Box + TopCenter).
 */
@Composable
fun Modifier.landscape16by9(): Modifier {
    val config = LocalConfiguration.current
    return if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        val maxWidth = (config.screenHeightDp * 16f / 9f).dp
        this.widthIn(max = maxWidth)
    } else {
        this
    }
}

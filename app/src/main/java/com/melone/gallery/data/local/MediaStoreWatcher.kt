package com.melone.gallery.data.local

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Meldet Änderungen am Medienbestand des Geräts: ein neues Bild von WhatsApp, ein
 * Screenshot, eine Kameraaufnahme, oder eine Löschung durch eine andere App.
 *
 * Ohne das müsste man in der App von Hand aktualisieren, damit neue Bilder auftauchen.
 * Android bietet dafür den [ContentObserver] auf den MediaStore-Adressen.
 *
 * Der Beobachter meldet pro neuer Datei **mehrere** Ereignisse (erst der Eintrag, dann die
 * nachgereichten Metadaten). Der Empfänger muss also drosseln, siehe
 * `GalleryViewModel.onLocalMediaChanged`.
 */
class MediaStoreWatcher(private val context: Context) {

    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        val resolver = context.contentResolver
        val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Deckt ALLE gemeinsamen Speicher ab, also auch die SD-Karte. Die alten
            // EXTERNAL_CONTENT_URI-Adressen meinen ab Android 10 nur den Hauptspeicher.
            listOf(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            )
        } else {
            listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            )
        }
        uris.forEach { resolver.registerContentObserver(it, true, observer) }
        awaitClose { resolver.unregisterContentObserver(observer) }
    }
}

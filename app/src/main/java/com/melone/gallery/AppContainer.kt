package com.melone.gallery

import android.content.Context
import com.melone.gallery.data.local.MediaStoreSource
import com.melone.gallery.data.settings.SettingsRepository
import com.melone.gallery.data.smb.SmbCredentials
import com.melone.gallery.data.smb.SmbManager
import com.melone.gallery.data.smb.SmbMediaSource
import com.melone.gallery.data.transfer.MediaTransfer
import com.melone.gallery.domain.GalleryRepository
import com.melone.gallery.domain.MetadataReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Einfacher Service-Locator (kein DI-Framework). In [GalleryApplication] erzeugt.
 */
class AppContainer(context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository = SettingsRepository(context)
    val serverCache = com.melone.gallery.data.settings.ServerCache(context.applicationContext)
    val smbManager = SmbManager()
    val mediaStoreSource = MediaStoreSource(context)
    private val smbMediaSource = SmbMediaSource(smbManager)

    val metadataReader = MetadataReader(context.applicationContext, smbManager)

    val mediaTransfer = MediaTransfer(context.applicationContext, smbManager)

    val galleryRepository = GalleryRepository(
        local = mediaStoreSource,
        smb = smbMediaSource,
    )

    init {
        // Serverdaten (inkl. verschlüsseltem Passwort) in den SmbManager spiegeln.
        settingsRepository.serverConfig
            .onEach { config ->
                val password = settingsRepository.secure.password
                if (config.isConfigured && password.isNotEmpty()) {
                    smbManager.updateCredentials(
                        SmbCredentials(config.host, config.username, password),
                    )
                } else {
                    smbManager.updateCredentials(null)
                }
            }
            .launchIn(appScope)
    }
}

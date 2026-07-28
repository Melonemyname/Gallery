package com.melone.gallery.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.melone.gallery.GalleryApplication
import com.melone.gallery.ui.gallery.GalleryViewModel
import com.melone.gallery.ui.settings.SettingsViewModel
import com.melone.gallery.ui.trash.TrashViewModel
import com.melone.gallery.ui.viewer.ViewerViewModel

/** Zentrale ViewModel-Factory (kein DI-Framework). */
object AppViewModelFactories {

    val gallery: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val app = app()
            GalleryViewModel(app.container.galleryRepository, app.container.settingsRepository, app.container.serverCache)
        }
    }

    val settings: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val app = app()
            SettingsViewModel(app.container.settingsRepository, app.container.smbManager)
        }
    }

    val viewer: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val app = app()
            ViewerViewModel(app.container.metadataReader)
        }
    }

    val trash: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val app = app()
            TrashViewModel(app.container.mediaStoreSource, app.container.smbManager, app.container.settingsRepository)
        }
    }

    private fun CreationExtras.app(): GalleryApplication =
        this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as GalleryApplication
}

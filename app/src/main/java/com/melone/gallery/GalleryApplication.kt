package com.melone.gallery

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.melone.gallery.data.smb.SmbCoilFetcher
import com.melone.gallery.data.smb.SmbThumbFetcher
import com.melone.gallery.data.smb.SmbVideoFetcher

class GalleryApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        instance = this
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(SmbThumbFetcher.Factory(container.smbManager))
                add(SmbCoilFetcher.Factory(container.smbManager))
                add(SmbVideoFetcher.Factory(container.smbManager))
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024) // 512 MB
                    .build()
            }
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: GalleryApplication
            private set
    }
}

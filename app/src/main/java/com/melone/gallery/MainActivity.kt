package com.melone.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.melone.gallery.ui.GalleryApp
import com.melone.gallery.ui.theme.GalleryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // Nur beim frischen App-Start (nicht beim Drehen) den Ton wieder stummschalten.
        if (savedInstanceState == null) {
            com.melone.gallery.ui.viewer.VideoAudioState.muted = true
        }
        super.onCreate(savedInstanceState)
        setContent {
            GalleryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    GalleryApp()
                }
            }
        }
    }
}

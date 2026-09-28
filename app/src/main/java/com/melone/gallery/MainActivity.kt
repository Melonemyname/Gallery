package com.melone.gallery

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.melone.gallery.ui.GalleryApp
import com.melone.gallery.ui.theme.GalleryTheme
import com.melone.gallery.ui.viewer.PipController

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

    /**
     * Verlässt der Nutzer die App (Home-Taste), während ein Video läuft, automatisch in den
     * Bild-in-Bild-Miniplayer wechseln — so läuft das Video außerhalb der App weiter.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            PipController.isVideoActive && PipController.isPlaying &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            val ratio = PipController.aspectRatio ?: Rational(16, 9)
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(ratio).build(),
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Steuerung ausblenden, solange der Miniplayer aktiv ist (siehe ViewerScreen).
        PipController.inPip = isInPictureInPictureMode
    }
}

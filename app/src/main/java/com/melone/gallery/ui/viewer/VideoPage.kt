package com.melone.gallery.ui.viewer

import android.net.Uri
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.melone.gallery.GalleryApplication
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import com.melone.gallery.data.smb.SmbDataSource
import com.melone.gallery.ui.components.MediaThumbnail
import com.melone.gallery.ui.components.landscape16by9
import kotlinx.coroutines.delay

/**
 * Ton-Zustand für die laufende App-Sitzung: Schaltet man den Ton bei einem Video
 * ein, bleibt er auch bei den nächsten Videos an. Beim frischen App-Start wird er
 * wieder auf stumm gesetzt (siehe MainActivity), Drehen zählt nicht als Neustart.
 */
object VideoAudioState {
    var muted: Boolean = true
}

/**
 * Video-Seite im Viewer mit eigener Compose-Steuerung (kein Media3-Controller):
 * Play/Pause zentriert neben der Zeitleiste, Zeiten darunter (current links, gesamt
 * rechts), Mute rechts über der Leiste (Ton standardmäßig aus). Beim Scrubben wird
 * live im Player gesucht (Vorschau groß im Bild, wie Vor-/Zurückspulen).
 * Ein-/Ausblenden folgt [chromeVisible] (Tippen togglet, kein Auto-Hide).
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPage(
    item: MediaItem,
    isActive: Boolean,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    leftInset: androidx.compose.ui.unit.Dp = 0.dp,
    rightInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    if (!isActive) {
        MediaThumbnail(item = item, modifier = Modifier.fillMaxSize())
        return
    }

    val context = LocalContext.current
    val app = context.applicationContext as GalleryApplication
    var playerView by remember(item.id) { mutableStateOf<PlayerView?>(null) }

    // Position überlebt einen Neuaufbau (z. B. wenn Android die App doch beendet),
    // damit das Video nicht wieder von vorn beginnt.
    var savedPos by rememberSaveable(item.id) { mutableStateOf(0L) }

    val player = remember(item.id) {
        // Großzügigere Pufferung, damit Server-Videos (SMB über Tailscale) nicht stottern:
        // mehr Vorlauf puffern und die Puffergröße nicht an einem Byte-Limit deckeln
        // (wichtig bei hoher Bitrate wie GoPro/DJI-Rohmaterial).
        // Niedrige Startschwelle (startet früh), aber weiterhin viel Vorlauf im
        // Hintergrund, damit es nach dem Start nicht stottert.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 700,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
            when (item.source) {
                MediaSource.LOCAL -> {
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.parse(item.id)))
                }
                MediaSource.SERVER -> {
                    val uri = Uri.Builder()
                        .scheme("smb")
                        .authority(item.smbShare)
                        .path("/" + (item.smbPath ?: ""))
                        .build()
                    val factory = ProgressiveMediaSource.Factory(
                        SmbDataSource.Factory(app.container.smbManager),
                    )
                    setMediaSource(factory.createMediaSource(androidx.media3.common.MediaItem.fromUri(uri)))
                }
            }
            volume = if (VideoAudioState.muted) 0f else 1f // Ton-Zustand der Sitzung
            prepare()
            if (savedPos > 0L) seekTo(savedPos)
            playWhenReady = true
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            // Surface sofort lösen und stoppen (billig), damit beim Zurück nicht das
            // letzte Bild hängt. Das teure release() wird aus dem aktuellen Frame
            // verschoben (nach der Pop-Transition), sonst ruckelt das Schließen kurz.
            playerView?.player = null
            val p = player
            runCatching { p.playWhenReady = false; p.stop() }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { p.release() }
            }, 300)
        }
    }

    LaunchedEffect(isActive) { player.playWhenReady = isActive }

    // Keine Hintergrundwiedergabe: pausieren, sobald die App in den Hintergrund geht
    // (z. B. beim Wechsel in den Videoplayer oder eine andere App).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE ||
                event == androidx.lifecycle.Lifecycle.Event.ON_STOP
            ) {
                runCatching { player.pause() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var position by remember(item.id) { mutableStateOf(0L) }
    var duration by remember(item.id) { mutableStateOf(0L) }
    var playing by remember(item.id) { mutableStateOf(true) }
    var muted by remember(item.id) { mutableStateOf(VideoAudioState.muted) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            playing = player.isPlaying
            savedPos = position
            delay(400)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    setOnClickListener { onToggleChrome() }
                    playerView = this
                }
            },
        )

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            VideoControls(
                playing = playing,
                position = position,
                duration = duration,
                muted = muted,
                bottomInset = bottomInset,
                leftInset = leftInset,
                rightInset = rightInset,
                onPlayPause = {
                    if (player.isPlaying) player.pause() else player.play()
                    playing = player.isPlaying
                },
                onSeek = { player.seekTo(it); position = it },
                onToggleMute = {
                    muted = !muted
                    player.volume = if (muted) 0f else 1f
                    VideoAudioState.muted = muted // für die restliche Sitzung merken
                },
            )
        }
    }
}

@Composable
private fun VideoControls(
    playing: Boolean,
    position: Long,
    duration: Long,
    muted: Boolean,
    bottomInset: androidx.compose.ui.unit.Dp,
    leftInset: androidx.compose.ui.unit.Dp,
    rightInset: androidx.compose.ui.unit.Dp,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos by remember { mutableStateOf(0f) }
    var lastSeek by remember { mutableStateOf(0L) }
    val max = duration.coerceAtLeast(1L).toFloat()
    val shown = if (scrubbing) scrubPos else position.coerceIn(0L, duration).toFloat()

    Column(
        modifier = Modifier
            // Im Querformat auf 16:9 begrenzen (wie das Videobild), sonst zieht sich die
            // Steuerung über die ganze Breite bis unter die Systemleisten.
            // Erst begrenzen, dann füllen — sonst bleibt die Begrenzung wirkungslos.
            .landscape16by9()
            .fillMaxWidth()
            .padding(start = 12.dp + leftInset, end = 12.dp + rightInset, top = 8.dp, bottom = bottomInset + 88.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onToggleMute, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(
                    imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (muted) "Ton an" else "Ton aus",
                    tint = Color.White,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlayPause, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Wiedergabe",
                    tint = Color.White,
                )
            }
            Slider(
                value = shown.coerceIn(0f, max),
                valueRange = 0f..max,
                onValueChange = {
                    scrubbing = true
                    scrubPos = it
                    // Live im Player suchen (Vorschau groß im Bild), gedrosselt.
                    val now = SystemClock.uptimeMillis()
                    if (now - lastSeek > 80) {
                        onSeek(it.toLong())
                        lastSeek = now
                    }
                },
                onValueChangeFinished = { onSeek(scrubPos.toLong()); scrubbing = false },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(fmtTime(shown.toLong()), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text(fmtTime(duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun fmtTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

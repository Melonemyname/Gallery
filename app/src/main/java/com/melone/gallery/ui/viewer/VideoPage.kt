package com.melone.gallery.ui.viewer

import android.app.Activity
import android.content.ContextWrapper
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.melone.gallery.GalleryApplication
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import com.melone.gallery.data.playback.PlaybackPositions
import com.melone.gallery.data.smb.ServerScrubPreview
import com.melone.gallery.data.smb.SmbDataSource
import com.melone.gallery.ui.components.MediaThumbnail
import com.melone.gallery.ui.components.landscape16by9
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/**
 * Ton-Zustand für die laufende App-Sitzung: Schaltet man den Ton bei einem Video
 * ein, bleibt er auch bei den nächsten Videos an. Beim frischen App-Start wird er
 * wieder auf stumm gesetzt (siehe MainActivity), Drehen zählt nicht als Neustart.
 */
object VideoAudioState {
    var muted: Boolean = true
}

/**
 * Zustand für den Bild-in-Bild-Miniplayer (nur Videos). Die aktive [VideoPage] meldet hier,
 * dass gerade ein Video läuft und mit welchem Seitenverhältnis. [MainActivity] nutzt das, um
 * beim Verlassen der App (Home-Taste) automatisch in den Miniplayer zu wechseln; [inPip]
 * (Compose-State) blendet die Bedienelemente aus, solange der Miniplayer aktiv ist.
 */
object PipController {
    /** true, solange eine Video-Seite im Viewer sichtbar ist. */
    var isVideoActive: Boolean = false
    /** true, wenn das aktive Video gerade abspielt (für Auto-PiP beim Verlassen). */
    var isPlaying: Boolean = false
    /** Seitenverhältnis des aktiven Videos (PiP-tauglich eingegrenzt). */
    var aspectRatio: Rational? = null
    /** Compose-State: gerade im Bild-in-Bild-Modus? */
    var inPip by mutableStateOf(false)
}

/** PiP verlangt ein Seitenverhältnis zwischen ~0.42 und ~2.39 → hart eingrenzen. */
private fun clampPipRatio(w: Int, h: Int): Rational {
    if (w <= 0 || h <= 0) return Rational(16, 9)
    val r = (w.toDouble() / h.toDouble()).coerceIn(0.42, 2.38)
    return Rational((r * 1000).toInt().coerceAtLeast(1), 1000)
}

/** Filme (Ordner „Filme") merken sich die Wiedergabeposition und laufen dort weiter. */
private fun isMovieItem(item: MediaItem): Boolean {
    if (item.source != MediaSource.SERVER || !item.isVideo) return false
    val segments = ((item.smbShare ?: "") + "/" + (item.smbPath ?: "")).split('/')
    return segments.any { it.equals("Filme", ignoreCase = true) }
}

private fun resumeKeyOf(item: MediaItem): String = "${item.smbShare}|${item.smbPath}"

private fun android.content.Context.findActivity(): Activity? {
    var c: android.content.Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Video-Seite im Viewer mit eigener Compose-Steuerung (kein Media3-Controller):
 * Play/Pause in der Mitte, links/rechts davon 10-Sekunden-Skip; Doppeltippen aufs Bild
 * springt ebenfalls (rechts vor, links zurück). Miniplayer (Bild-in-Bild) über einen
 * eigenen Knopf oder automatisch beim Verlassen der App. Zeitleiste darunter, Mute rechts
 * oben (Ton standardmäßig aus). Beim Scrubben wird live im Player gesucht.
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
    onPosition: (Long) -> Unit = {},
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

    // Server-Videos: Vorschau-Frames beim Scrubben (lokal reicht die Live-Suche im Player).
    val scrubPreview = remember(item.id) {
        if (item.source == MediaSource.SERVER && item.isVideo && item.smbShare != null && item.smbPath != null) {
            ServerScrubPreview(app.container.smbManager, item.smbShare!!, item.smbPath!!)
        } else {
            null
        }
    }
    DisposableEffect(scrubPreview) { onDispose { scrubPreview?.release() } }

    // Filme merken die Position dauerhaft (SharedPreferences) und laufen dort weiter.
    val resumeKey = remember(item.id) { if (isMovieItem(item)) resumeKeyOf(item) else null }
    val startPos = remember(item.id) {
        when {
            savedPos > 0L -> savedPos // frischer Config-Change/Prozess-Neuaufbau
            resumeKey != null -> PlaybackPositions.get(context, resumeKey)
            else -> 0L
        }
    }

    val player = remember(item.id) {
        // Pufferung bewusst KONTINUIERLICH statt in großen Schüben: ExoPlayer lädt bis
        // maxBuffer voraus und liest dann erst wieder nach, wenn der Puffer unter minBuffer
        // fällt. Ein großes Fenster (z. B. 15↔120 s) hieße ~1,75 min lang GAR nicht lesen —
        // in dieser Ruhephase bricht die SMB-Verbindung über Tailscale ab (Socket-Timeout
        // 60 s) und der nächste Read stockt sichtbar. Ein enges Fenster (30↔45 s) lädt alle
        // ~15 s nach: Verbindung bleibt warm, die Sendelast des (wackligen) Server-NICs kommt
        // nicht in Spitzen, und 30 s Vorlauf federn Netz-Schwankungen weiterhin ab.
        // Startschwelle klein, damit es früh losspielt.
        //
        // ACHTUNG, hier lag ein OutOfMemory-Fehler: Mit
        // `setPrioritizeTimeOverSizeThresholds(true)` ignoriert ExoPlayer seine
        // Größenbeschränkung und puffert stur nach Zeit. Bei einem Film mit hoher Datenrate
        // sind 45 Sekunden schnell über 100 MB, und der Puffer liegt im Java-Heap (256 MB).
        // Folge: erst wird das Nachladen zäh (die Speicherbereinigung läuft dauernd), dann
        // stirbt die App. Deshalb jetzt eine ausdrückliche Obergrenze in Byte statt des
        // Schalters. Es gilt, was zuerst erreicht wird: 45 Sekunden oder [MAX_BUFFER_BYTES].
        // Bei den DVD-Rips (kleine Datenrate) entscheidet weiterhin die Zeit, bei großen
        // 1080p-Dateien greift die Byte-Grenze und der Vorlauf ist immer noch komfortabel.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 45_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500,
            )
            .setTargetBufferBytes(MAX_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
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
            if (startPos > 0L) seekTo(startPos)
            playWhenReady = true
        }
    }

    // Letzte Rettung, falls ein SMB-/Netz-Aussetzer trotz DataSource-Wiederholung als
    // Player-Fehler durchschlägt: an aktueller Position neu vorbereiten und weiterspielen,
    // damit das Video nicht endgültig stehen bleibt. Begrenzt, um Endlosschleifen bei
    // wirklich totem Server zu vermeiden (Zähler wird bei erfolgreichem Puffern zurückgesetzt).
    var errorRetries by remember(item.id) { mutableStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    PipController.aspectRatio = clampPipRatio(videoSize.width, videoSize.height)
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) errorRetries = 0
            }
            override fun onPlayerError(error: PlaybackException) {
                if (errorRetries < MAX_ERROR_RETRIES) {
                    errorRetries++
                    val pos = player.currentPosition
                    runCatching {
                        player.prepare()
                        if (pos > 0) player.seekTo(pos)
                        player.playWhenReady = true
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    DisposableEffect(item.id) {
        onDispose {
            // Filmposition sichern (vor dem Stoppen, damit die Position noch stimmt),
            // damit es beim nächsten Öffnen dort weitergeht.
            if (resumeKey != null) {
                runCatching {
                    val pos = player.currentPosition
                    val dur = player.duration.takeIf { it > 0 } ?: 0L
                    PlaybackPositions.save(context, resumeKey, pos, dur)
                }
            }
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

    // Diese Video-Seite ist aktiv (nicht-aktive Seiten kehren oben früh zurück) →
    // für Auto-PiP beim Verlassen der App merken.
    DisposableEffect(Unit) {
        PipController.isVideoActive = true
        onDispose {
            PipController.isVideoActive = false
            PipController.isPlaying = false
        }
    }

    LaunchedEffect(isActive) { player.playWhenReady = isActive }

    // Keine Hintergrundwiedergabe: pausieren, sobald die App in den Hintergrund geht —
    // ABER nicht im Bild-in-Bild-Modus, dort soll der Miniplayer weiterlaufen.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE ||
                event == androidx.lifecycle.Lifecycle.Event.ON_STOP
            ) {
                val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                    context.findActivity()?.isInPictureInPictureMode == true
                if (!inPip) runCatching { player.pause() }
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
        var sinceSave = 0
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            playing = player.isPlaying
            savedPos = position
            PipController.isPlaying = player.isPlaying
            onPosition(position)
            // Filmposition regelmäßig sichern (~alle 5 s), damit auch bei App-Kill etwas da ist.
            if (resumeKey != null && ++sinceSave >= 12) {
                PlaybackPositions.save(context, resumeKey, position, duration)
                sinceSave = 0
            }
            delay(400)
        }
    }

    // Bildschirm anlassen, solange wirklich abgespielt wird (bei Pause darf er wieder aus).
    LaunchedEffect(playing, playerView) { playerView?.keepScreenOn = playing }

    // Um [delta] ms springen (für Skip-Knöpfe und Doppeltippen), an den Videogrenzen geklemmt.
    fun seekBy(delta: Long) {
        val dur = player.duration
        val target = (player.currentPosition + delta).let {
            if (dur > 0) it.coerceIn(0L, dur) else it.coerceAtLeast(0L)
        }
        player.seekTo(target)
        position = target
    }

    // Play/Pause. Ist das Video bereits durchgelaufen (STATE_ENDED), startet es wieder von vorn.
    fun togglePlay() {
        when {
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(0); player.play() }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
        playing = player.isPlaying
    }

    // Zoom bei PAUSE: Das laufende Video sitzt auf einer SurfaceView, die sich nicht
    // zuverlässig zoomen lässt. Deshalb wird im pausierten Zustand ein Standbild des
    // aktuellen Frames als zoombares Overlay darüber gelegt (lokal exakt, Server nächster
    // Keyframe). Beim Weiterspielen verschwindet es wieder.
    var pausedFrame by remember(item.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    suspend fun captureFrame(pos: Long): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        when (item.source) {
            MediaSource.SERVER -> scrubPreview?.bigFrameAt(pos)
            MediaSource.LOCAL -> {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, Uri.parse(item.id))
                    val us = pos.coerceAtLeast(0L) * 1000L
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, 1920, 1920)
                            ?: r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
                    } else {
                        r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
                    }
                } catch (_: Throwable) {
                    null
                } finally {
                    runCatching { r.release() }
                }
            }
        }
    }
    // Bei Pause den aktuellen Frame holen; beim Weiterspielen verwerfen. Der zweite Key
    // (Position pro Sekunde) sorgt dafür, dass nach einem Spulen im Pausezustand neu erfasst wird.
    LaunchedEffect(playing, if (playing) 0L else position / 1000L) {
        pausedFrame = if (playing) null else captureFrame(player.currentPosition)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    // Seitenverhältnis beibehalten (nie strecken), notfalls mit Balken.
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    // Einfaches Tippen togglet die Steuerung, Doppeltippen springt 10 s
                    // (rechte Bildhälfte vor, linke zurück). GestureDetector statt Compose-
                    // pointerInput, weil das über der SurfaceView zuverlässiger ist.
                    val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(e: MotionEvent): Boolean = true
                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            onToggleChrome(); return true
                        }
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (e.x > width / 2f) seekBy(10_000L) else seekBy(-10_000L)
                            return true
                        }
                    })
                    setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }
                    playerView = this
                }
            },
        )

        // Zoombares Standbild über dem pausierten Video (Tippen togglet die Steuerung).
        val frame = pausedFrame
        if (!playing && frame != null) {
            val zoomState = rememberZoomableState()
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .zoomable(zoomState, onClick = { onToggleChrome() }),
            )
        }

        // Play/Pause + 10-Sekunden-Skip mittig über dem Bild.
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            TransportControls(
                playing = playing,
                onPlayPause = { togglePlay() },
                onSkipBack = { seekBy(-10_000L) },
                onSkipForward = { seekBy(10_000L) },
            )
        }

        // Ton + Zeitleiste unten.
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            VideoBottomBar(
                position = position,
                duration = duration,
                muted = muted,
                bottomInset = bottomInset,
                leftInset = leftInset,
                rightInset = rightInset,
                onSeek = { player.seekTo(it); position = it },
                onToggleMute = {
                    muted = !muted
                    player.volume = if (muted) 0f else 1f
                    VideoAudioState.muted = muted // für die restliche Sitzung merken
                },
                // Lokal: beim Ziehen live im Player suchen (schnell). Server: SMB-Seeks sind
                // teuer → erst beim Loslassen suchen, während des Ziehens die Vorschau-Bubble.
                liveSeek = item.source == MediaSource.LOCAL,
                serverPreview = scrubPreview?.let { p -> p::frameAt },
            )
        }
    }
}

@Composable
private fun TransportControls(
    playing: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onSkipBack, modifier = Modifier.size(64.dp)) {
            Icon(
                imageVector = Icons.Filled.Replay10,
                contentDescription = "10 Sekunden zurück",
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.width(40.dp))
        IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp)) {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Wiedergabe",
                tint = Color.White,
                modifier = Modifier.size(48.dp),
            )
        }
        Spacer(Modifier.width(40.dp))
        IconButton(onClick = onSkipForward, modifier = Modifier.size(64.dp)) {
            Icon(
                imageVector = Icons.Filled.Forward10,
                contentDescription = "10 Sekunden vor",
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun VideoBottomBar(
    position: Long,
    duration: Long,
    muted: Boolean,
    bottomInset: androidx.compose.ui.unit.Dp,
    leftInset: androidx.compose.ui.unit.Dp,
    rightInset: androidx.compose.ui.unit.Dp,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    liveSeek: Boolean,
    serverPreview: (suspend (Long) -> android.graphics.Bitmap?)?,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos by remember { mutableStateOf(0f) }
    var lastSeek by remember { mutableStateOf(0L) }
    var previewBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val max = duration.coerceAtLeast(1L).toFloat()
    val shown = if (scrubbing) scrubPos else position.coerceIn(0L, duration).toFloat()

    // Server-Videos: beim Ziehen gedrosselt den nächsten Keyframe als Vorschau holen.
    // collectLatest bricht einen noch laufenden Frame-Abruf ab, sobald sich die Position
    // ändert → immer nur der aktuellste Frame wird geladen.
    if (serverPreview != null) {
        LaunchedEffect(Unit) {
            snapshotFlow { if (scrubbing) scrubPos.toLong() else -1L }
                .collectLatest { pos ->
                    if (pos < 0L) {
                        previewBmp = null
                    } else {
                        runCatching { serverPreview(pos) }.getOrNull()?.let { previewBmp = it }
                    }
                }
        }
    }

    // Im Querformat ist der Bildschirm nur etwa halb so hoch: derselbe Abstand von unten
    // würde die Steuerung optisch in die Bildmitte schieben.
    val bottomGap = if (com.melone.gallery.ui.components.isLandscape()) 44.dp else 88.dp

    Column(
        modifier = Modifier
            // Im Querformat auf 16:9 begrenzen (wie das Videobild), sonst zieht sich die
            // Steuerung über die ganze Breite bis unter die Systemleisten.
            // Erst begrenzen, dann füllen — sonst bleibt die Begrenzung wirkungslos.
            .landscape16by9()
            .fillMaxWidth()
            .padding(start = 12.dp + leftInset, end = 12.dp + rightInset, top = 8.dp, bottom = bottomInset + bottomGap),
    ) {
        // Ton rechtsbündig über der Zeitleiste.
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onToggleMute, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(
                    imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (muted) "Ton an" else "Ton aus",
                    tint = Color.White,
                )
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val trackWidthPx = constraints.maxWidth.toFloat()
            Slider(
                value = shown.coerceIn(0f, max),
                valueRange = 0f..max,
                onValueChange = {
                    scrubbing = true
                    scrubPos = it
                    // Nur lokal live im Player suchen (Server: erst beim Loslassen, s. o.).
                    if (liveSeek) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastSeek > 80) {
                            onSeek(it.toLong())
                            lastSeek = now
                        }
                    }
                },
                onValueChangeFinished = { onSeek(scrubPos.toLong()); scrubbing = false },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            // Vorschau-Bubble (Server) über dem Reglerdaumen an der Ziehposition.
            val bmp = previewBmp
            if (scrubbing && bmp != null && serverPreview != null) {
                val density = LocalDensity.current
                val bubbleW = 168.dp
                val bubbleH = 96.dp
                val bubbleWpx = with(density) { bubbleW.toPx() }
                val frac = (shown / max).coerceIn(0f, 1f)
                val xPx = (frac * trackWidthPx - bubbleWpx / 2f)
                    .coerceIn(0f, (trackWidthPx - bubbleWpx).coerceAtLeast(0f))
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset(x = with(density) { xPx.toDp() }, y = (-112).dp)
                        .size(bubbleW, bubbleH)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                )
            }
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

private const val MAX_ERROR_RETRIES = 5

/**
 * Obergrenze für den Wiedergabepuffer. Er liegt im Java-Heap, und der ist bei dieser App
 * auf 256 MB gedeckelt (kein `largeHeap`). 40 MB lassen genug Luft für den Bild-Cache und
 * reichen selbst bei hoher Datenrate für über zehn Sekunden Vorlauf.
 */
private const val MAX_BUFFER_BYTES = 40 * 1024 * 1024

private fun fmtTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

package com.melone.gallery.ui.viewer

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melone.gallery.GalleryApplication
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.data.model.MediaSource
import com.melone.gallery.data.settings.ServerFolder
import com.melone.gallery.data.transfer.TransferTarget
import com.melone.gallery.domain.MediaDetails
import com.melone.gallery.ui.AppViewModelFactories
import com.melone.gallery.ui.components.landscape16by9
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState

/**
 * Aktionen für den Papierkorb-Modus des Viewers. Ist das gesetzt, zeigt die untere Leiste
 * statt Bearbeiten/Info/Teilen/Löschen nur **Wiederherstellen** und **Endgültig löschen**,
 * und das 3-Punkte-Menü oben ist ausgeblendet.
 */
data class TrashActions(
    val onRestore: (MediaItem) -> Unit,
    val onDeletePermanent: (MediaItem) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(
    items: List<MediaItem>,
    startIndex: Int,
    onBack: () -> Unit,
    onDeleted: (MediaItem) -> Unit = {},
    trashActions: TrashActions? = null,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val vm: ViewerViewModel = viewModel(factory = AppViewModelFactories.viewer)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as GalleryApplication
    val transfer = app.container.mediaTransfer
    val serverConfig by app.container.settingsRepository.serverConfig
        .collectAsStateWithLifecycle(initialValue = com.melone.gallery.data.settings.ServerConfig())

    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size },
    )
    val currentItem = items[pagerState.currentPage.coerceIn(0, items.lastIndex)]

    // Nachbarbilder vorausladen, damit Weiterswipen ohne Wartezeit ist.
    // WICHTIG: nur in den Festplatten-Cache (Bytes schon da), NICHT in den
    // Arbeitsspeicher — sonst häufen sich dekodierte Vollbilder an und die App
    // läuft bei großen Fotos in einen OutOfMemory-Absturz.
    LaunchedEffect(pagerState.currentPage) {
        // ERST das aktuelle Bild, dann die Nachbarn. Über SMB gibt es nur wenige
        // gleichzeitige Leseplätze (siehe SmbManager.fetchDispatcher). Wurden die Nachbarn
        // sofort angestoßen, standen Vorschaubild und Original des aktuellen Bildes hinten
        // in der Schlange, und man sah so lange nur Schwarz.
        delay(600)
        val loader = coil.Coil.imageLoader(context)
        val cur = pagerState.currentPage
        listOf(cur + 1, cur - 1, cur + 2, cur - 2).forEach { idx ->
            items.getOrNull(idx)?.let { neighbor ->
                if (!neighbor.isVideo) {
                    loader.enqueue(
                        coil.request.ImageRequest.Builder(context)
                            .data(neighbor.coilModel)
                            .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                            .build(),
                    )
                }
            }
        }
    }

    var showInfo by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var transferring by remember { mutableStateOf(false) }
    var pendingItem by remember { mutableStateOf<MediaItem?>(null) }
    var pendingMove by remember { mutableStateOf(false) }
    var showDest by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // Für die System-Dialoge (Löschen/Papierkorb): welches Item betroffen ist.
    var pendingDelete by remember { mutableStateOf<MediaItem?>(null) }
    // Eigenes Server-Vorschaubild (Cover) setzen: läuft gerade / aktuelle Videoposition /
    // für welches Item der Bild-Picker geöffnet wurde.
    var thumbBusy by remember { mutableStateOf(false) }
    var currentVideoPositionMs by remember { mutableStateOf(0L) }
    var thumbTargetItem by remember { mutableStateOf<MediaItem?>(null) }
    // Leiste/Steuerung (oben + unten) per Berührung ein-/ausblenden.
    var chromeVisible by remember { mutableStateOf(true) }
    // Im Bild-in-Bild-Miniplayer soll nur das Video sichtbar sein (keine Bedienelemente).
    val effectiveChrome = chromeVisible && !PipController.inPip
    // Vollbild: hebt im Querformat die 16:9-Begrenzung des Mediums auf.
    // Das Seitenverhältnis bleibt in beiden Fällen erhalten (nie verzerrt).
    var expanded by rememberSaveable { mutableStateOf(false) }

    // System-Leisten-Größen einmalig merken (immersive setzt die Insets später auf 0),
    // inkl. links/rechts fürs Querformat (Navigationsleiste/Kamera-Ausschnitt an der Seite),
    // damit die Steuerung nicht unter die Systemleisten läuft.
    val sysBars = WindowInsets.systemBars.asPaddingValues()
    val cutout = WindowInsets.displayCutout.asPaddingValues()
    val insetTop = remember { mutableStateOf(0.dp) }
    val insetBottom = remember { mutableStateOf(0.dp) }
    val insetLeft = remember { mutableStateOf(0.dp) }
    val insetRight = remember { mutableStateOf(0.dp) }
    LaunchedEffect(sysBars, cutout) {
        val ld = androidx.compose.ui.unit.LayoutDirection.Ltr
        val t = sysBars.calculateTopPadding()
        val b = sysBars.calculateBottomPadding()
        val l = maxOf(sysBars.calculateLeftPadding(ld), cutout.calculateLeftPadding(ld))
        val r = maxOf(sysBars.calculateRightPadding(ld), cutout.calculateRightPadding(ld))
        if (t > insetTop.value) insetTop.value = t
        if (b > insetBottom.value) insetBottom.value = b
        if (l > insetLeft.value) insetLeft.value = l
        if (r > insetRight.value) insetRight.value = r
    }
    val details by vm.details.collectAsStateWithLifecycle()

    LaunchedEffect(showInfo, currentItem.id) {
        if (showInfo) vm.loadDetails(currentItem)
    }

    // Android-Zurück: erst Info-Karte schließen, sonst den Viewer verlassen (in der App
    // zurück statt die App zu beenden).
    BackHandler { if (showInfo) showInfo = false else onBack() }

    // System-Leisten (Status-/Navigationsleiste) im Viewer immersiv aus-/einblenden,
    // gekoppelt an die eigene Leiste. Beim Verlassen wieder einblenden.
    val view = LocalView.current
    fun insetsController(): WindowInsetsControllerCompat? {
        val window = (context.findActivityOrNull())?.window ?: return null
        return WindowCompat.getInsetsController(window, view)
    }
    LaunchedEffect(chromeVisible) {
        insetsController()?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chromeVisible) show(WindowInsetsCompat.Type.systemBars())
            else hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose { insetsController()?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // In den Bild-in-Bild-Miniplayer wechseln (nur Videos). Das Seitenverhältnis meldet der
    // aktive Player über PipController; der Player läuft im PiP-Fenster weiter.
    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val activity = context.findActivityOrNull() ?: return
        val ratio = PipController.aspectRatio ?: android.util.Rational(16, 9)
        runCatching {
            activity.enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).build(),
            )
        }
    }

    // Ergebnis des System-Löschdialogs beim Verschieben (Original entfernen).
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            toast("Verschoben")
            pendingDelete?.let { onDeleted(it) }
            pendingDelete = null
            onBack()
        } else {
            toast("Kopiert (Original behalten)")
            pendingDelete = null
        }
    }

    // Eigenes Server-Vorschaubild setzen. [produce] liefert das JPEG (aktuelles Videobild
    // oder gewähltes Gerät-Bild). Ergebnis landet unter `.thumbs/<pfad>.jpg` auf dem Server;
    // dieser Ordner ist in Syncthing ignoriert → wird NICHT synchronisiert, nur die App liest ihn.
    fun setThumbnail(item: MediaItem, produce: suspend () -> ByteArray?) {
        val share = item.smbShare ?: return
        val path = item.smbPath ?: return
        scope.launch {
            thumbBusy = true
            val jpeg = runCatching { produce() }.getOrNull()
            val ok = jpeg != null && com.melone.gallery.data.thumb.CustomThumbnail
                .setServerThumbnail(context, app.container.smbManager, share, path, jpeg)
            thumbBusy = false
            toast(if (ok) "Vorschaubild gesetzt" else "Konnte Vorschaubild nicht setzen")
        }
    }

    // Bild vom Gerät als Cover wählen (Ergebnis siehe setThumbnail).
    val pickThumbLauncher = rememberLauncherForActivityResult(
        remember { PickContentQuiet() },
    ) { uri ->
        val item = thumbTargetItem
        thumbTargetItem = null
        if (uri != null && item != null) {
            setThumbnail(item) {
                com.melone.gallery.data.thumb.CustomThumbnail.imageToThumbJpeg(context, uri)
            }
        }
    }

    fun deleteLocalSource(item: MediaItem) {
        val uri = Uri.parse(item.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingDelete = item
            val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
            deleteLauncher.launch(noUserActionRequest(pi.intentSender))
        } else {
            runCatching { context.contentResolver.delete(uri, null, null) }
            toast("Verschoben"); onDeleted(item); onBack()
        }
    }

    fun runTransfer(item: MediaItem, target: TransferTarget, move: Boolean) {
        scope.launch {
            transferring = true
            val res = transfer.copy(item, target)
            transferring = false
            if (res.isFailure) {
                toast("Fehler: ${res.exceptionOrNull()?.message ?: "unbekannt"}")
                return@launch
            }
            if (!move) {
                toast("Kopiert")
                return@launch
            }
            when (item.source) {
                MediaSource.SERVER -> {
                    val del = transfer.deleteServerSource(item)
                    if (del.isSuccess) { toast("Verschoben"); onDeleted(item); onBack() }
                    else toast("Kopiert (Original behalten)")
                }
                MediaSource.LOCAL -> deleteLocalSource(item)
            }
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(
        remember { OpenTreeQuiet() },
    ) { uri ->
        val item = pendingItem
        if (uri != null && item != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            runTransfer(item, TransferTarget.LocalTree(uri), pendingMove)
        }
    }

    // Eigenständiges Löschen → in den Papierkorb (nicht Teil von "Verschieben").
    val trashStandaloneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            toast("In den Papierkorb")
            pendingDelete?.let { onDeleted(it) }
            pendingDelete = null
            onBack()
        } else {
            pendingDelete = null
        }
    }

    // Bearbeiten: übergibt das Bild an einen Editor auf dem Gerät (z. B. Samsung-Galerie
    // mit ihren KI-Funktionen). Server-Bilder werden dafür kurz lokal zwischengespeichert
    // und das Ergebnis danach als NEUE Datei zurück in den Server-Ordner geschrieben
    // (Original bleibt unangetastet).
    fun editCurrent() {
        val item = currentItem
        when (item.source) {
            MediaSource.LOCAL -> {
                // Lokal: der Editor speichert selbst (inkl. Samsungs Frage Kopie/Original).
                if (!openForEditing(context, Uri.parse(item.id), item.mimeType, skipEditAction = item.isVideo)) {
                    toast("Keine App zum Bearbeiten oder Öffnen gefunden")
                }
            }
            MediaSource.SERVER -> {
                // Der Editor bietet für Dateien aus einer fremden App keine Werkzeuge an.
                // Deshalb das Server-Bild als echtes Foto auf dem Gerät ablegen und dieses
                // öffnen — dann verhält es sich wie jedes lokale Bild (voller Editor).
                scope.launch {
                    transferring = true
                    val saved = withContext(Dispatchers.IO) {
                        com.melone.gallery.ui.edit.saveServerImageToGallery(
                            context, item.smbShare!!, item.smbPath!!, item.displayName, item.mimeType,
                        )
                    }
                    transferring = false
                    if (saved == null) {
                        toast("Konnte das Bild nicht auf dem Gerät speichern")
                        return@launch
                    }
                    toast("Auf dem Gerät gespeichert (Bilder/Galerie)")
                    // Dauerhaft vermerken: Android kann die App beenden, während der
                    // Editor läuft. Die Auswertung passiert in GalleryApp beim Zurückkommen.
                    com.melone.gallery.ui.edit.EditWatchStore.save(
                        context,
                        com.melone.gallery.ui.edit.EditWatchStore.Entry(
                            share = item.smbShare!!,
                            path = item.smbPath!!,
                            displayName = item.displayName,
                            savedId = android.content.ContentUris.parseId(saved),
                            startSec = System.currentTimeMillis() / 1000 - 2,
                        ),
                    )
                    if (!openForEditing(context, saved, item.mimeType)) {
                        toast("Keine App zum Bearbeiten oder Öffnen gefunden")
                        com.melone.gallery.ui.edit.EditWatchStore.clear(context)
                    }
                }
            }
        }
    }

    fun deleteCurrent() {
        val item = currentItem
        when (item.source) {
            MediaSource.LOCAL -> {
                val uri = Uri.parse(item.id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // System-Papierkorb: automatische Löschung nach 30 Tagen.
                    pendingDelete = item
                    val pi = MediaStore.createTrashRequest(context.contentResolver, listOf(uri), true)
                    trashStandaloneLauncher.launch(noUserActionRequest(pi.intentSender))
                } else {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    toast("Gelöscht"); onDeleted(item); onBack()
                }
            }
            MediaSource.SERVER -> showDeleteConfirm = true
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    // Im Querformat auf 16:9 begrenzen (wie die Bedienelemente), außer
                    // der Nutzer schaltet auf Vollbild. Erst begrenzen, dann füllen.
                    .then(if (expanded) Modifier else Modifier.landscape16by9())
                    .fillMaxSize(),
                // Nachbarseiten schon mitkomponieren → deren Bild lädt vorab.
                beyondViewportPageCount = 1,
            ) { page ->
                val item = items[page]
                val isActive = page == pagerState.currentPage
                if (item.isVideo) {
                    VideoPage(
                        item = item,
                        isActive = isActive,
                        chromeVisible = effectiveChrome,
                        onToggleChrome = { chromeVisible = !chromeVisible },
                        bottomInset = insetBottom.value,
                        leftInset = insetLeft.value,
                        rightInset = insetRight.value,
                        onPosition = { currentVideoPositionMs = it },
                    )
                } else {
                    val zoomState = rememberZoomableImageState()
                    // Sobald das Original wirklich angezeigt wird, blendet das Thumbnail aus.
                    // Sonst bliebe es beim Rauszoomen bildschirmfüllend als Hintergrund stehen,
                    // während das (kleiner gezoomte) Original davor liegt.
                    val thumbAlpha by animateFloatAsState(
                        targetValue = if (zoomState.isImageDisplayed) 0f else 1f,
                        animationSpec = tween(150),
                        label = "thumb",
                    )
                    // Solange das Original nicht angezeigt wird, kennt telephoto die Bildgröße
                    // nicht und verschluckt sämtliche Berührungen: kein Antippen, kein
                    // Weiterblättern. Deshalb bekommt es die Gesten erst, wenn es so weit ist,
                    // und bis dahin übernimmt ein eigener Tipp-Erkenner. Der greift KEINE
                    // Wischgesten ab, das Blättern im Pager funktioniert also weiter. Nur das
                    // Zoomen fehlt in dieser kurzen Zeit, und das ist ohne geladenes Bild
                    // ohnehin sinnlos.
                    val zoomReady = zoomState.isImageDisplayed
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (zoomReady) {
                                    Modifier
                                } else {
                                    Modifier.pointerInput(Unit) {
                                        detectTapGestures { chromeVisible = !chromeVisible }
                                    }
                                },
                            ),
                    ) {
                        // Schnelles Thumbnail als Sofort-Vorschau (meist schon im Cache),
                        // darüber lädt das Original in voller Auflösung nach.
                        var thumbLoaded by remember(item.id) { mutableStateOf(false) }
                        if (thumbAlpha > 0.01f) {
                            coil.compose.AsyncImage(
                                model = item.thumbModel,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().alpha(thumbAlpha),
                                onState = { st ->
                                    thumbLoaded = st is coil.compose.AsyncImagePainter.State.Success
                                },
                            )
                        }
                        // Ist weder Vorschau noch Original da, zeigte der Viewer bisher nur
                        // eine schwarze Fläche. Ein Ladekringel sagt wenigstens, dass etwas
                        // passiert.
                        if (!zoomReady && !thumbLoaded) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                        ZoomableAsyncImage(
                            state = zoomState,
                            model = coil.request.ImageRequest.Builder(context)
                                .data(item.coilModel)
                                .apply {
                                    // Ohne passenden diskCacheKey findet telephoto die
                                    // Datei im Cache nicht und stürzt ab.
                                    if (item.source == MediaSource.SERVER && item.smbShare != null && item.smbPath != null) {
                                        diskCacheKey(
                                            com.melone.gallery.data.smb.SmbCoilFetcher
                                                .cacheKey(item.smbShare, item.smbPath),
                                        )
                                    }
                                }
                                .build(),
                            contentDescription = item.displayName,
                            modifier = Modifier.fillMaxSize(),
                            gesturesEnabled = zoomReady,
                            onClick = { chromeVisible = !chromeVisible },
                        )
                    }
                }
            }

            val chromeAlpha by animateFloatAsState(
                targetValue = if (effectiveChrome) 1f else 0f,
                animationSpec = tween(220),
                label = "chrome",
            )
            if (chromeAlpha > 0.01f) {
                TopAppBar(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .alpha(chromeAlpha)
                        .landscape16by9()
                        .padding(top = insetTop.value, start = insetLeft.value, end = insetRight.value),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        // Lange Titel laufen als Marquee durch (zweimal je Einblenden),
                        // kurze bleiben statisch stehen. Dateiendung wird ausgeblendet.
                        Text(
                            stripDisplayExtension(currentItem.displayName),
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.basicMarquee(iterations = 2),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Color.White)
                        }
                    },
                    actions = {
                        if (transferring || thumbBusy) {
                            CircularProgressIndicator(Modifier.width(22.dp).height(22.dp), color = Color.White, strokeWidth = 2.dp)
                        }
                        // Miniplayer (Bild-in-Bild) nur für Videos, direkt neben Vollbild.
                        if (currentItem.isVideo) {
                            IconButton(onClick = { enterPip() }) {
                                Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "Miniplayer", tint = Color.White)
                            }
                        }
                        // Vollbild nur im Querformat sinnvoll (im Hochformat gibt es
                        // keine 16:9-Begrenzung).
                        if (com.melone.gallery.ui.components.isLandscape()) {
                            IconButton(onClick = { expanded = !expanded }) {
                                Icon(
                                    imageVector = if (expanded) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                    contentDescription = if (expanded) "16:9" else "Vollbild",
                                    tint = Color.White,
                                )
                            }
                        }
                        if (trashActions == null) Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Mehr", tint = Color.White)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Verschieben") },
                                    leadingIcon = { Icon(Icons.Filled.DriveFileMove, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false; pendingItem = currentItem; pendingMove = true; showDest = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Kopieren") },
                                    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false; pendingItem = currentItem; pendingMove = false; showDest = true
                                    },
                                )
                                if (!currentItem.isVideo) {
                                    DropdownMenuItem(
                                        text = { Text("Als Hintergrund festlegen") },
                                        leadingIcon = { Icon(Icons.Filled.Wallpaper, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            val item = currentItem
                                            scope.launch { setAsWallpaper(context, item) }
                                        },
                                    )
                                }
                                // Eigenes Vorschaubild (Cover) nur für Server-Videos: entweder das
                                // aktuell angezeigte Videobild oder ein Bild vom Gerät. Wird NICHT
                                // gesynct (landet im ignorierten `.thumbs`-Ordner auf dem Server).
                                if (currentItem.source == MediaSource.SERVER && currentItem.isVideo) {
                                    DropdownMenuItem(
                                        text = { Text("Aktuelles Bild als Vorschau") },
                                        leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            val item = currentItem
                                            val pos = currentVideoPositionMs
                                            setThumbnail(item) {
                                                com.melone.gallery.data.thumb.CustomThumbnail
                                                    .captureServerVideoFrame(
                                                        app.container.smbManager,
                                                        item.smbShare!!, item.smbPath!!, pos,
                                                    )
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Bild als Vorschau wählen") },
                                        leadingIcon = { Icon(Icons.Filled.Photo, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            thumbTargetItem = currentItem
                                            pickThumbLauncher.launch("image/*")
                                        },
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.35f),
                    ),
                )

                // Schwebende Buttons unten: Details + Senden. Bei Video unter der
                // Videosteuerung (die Steuerung bekommt in VideoPage Platz nach unten).
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .alpha(chromeAlpha)
                        .padding(
                            // Im Querformat näher an den unteren Rand (weniger Höhe verfügbar).
                            bottom = insetBottom.value + if (com.melone.gallery.ui.components.isLandscape()) 6.dp else 24.dp,
                            start = insetLeft.value,
                            end = insetRight.value,
                        ),
                    shape = RoundedCornerShape(28.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                ) {
                    Row(modifier = Modifier.padding(horizontal = 6.dp)) {
                        if (trashActions != null) {
                            // Papierkorb-Modus: nur Wiederherstellen + endgültig löschen.
                            IconButton(onClick = { trashActions.onRestore(currentItem) }) {
                                Icon(Icons.Filled.RestoreFromTrash, contentDescription = "Wiederherstellen", tint = Color.White)
                            }
                            IconButton(onClick = { trashActions.onDeletePermanent(currentItem) }) {
                                Icon(Icons.Filled.DeleteForever, contentDescription = "Endgültig löschen", tint = Color.White)
                            }
                        } else {
                            // Bearbeiten: alle lokalen Medien (auch Videos) und Server-Bilder.
                            // Server-Videos bleiben außen vor (müssten erst komplett geladen werden).
                            if (currentItem.source == MediaSource.LOCAL || !currentItem.isVideo) {
                                IconButton(onClick = { editCurrent() }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Bearbeiten", tint = Color.White)
                                }
                            }
                            IconButton(onClick = { showInfo = !showInfo; if (showInfo) chromeVisible = true }) {
                                Icon(Icons.Filled.Info, contentDescription = "Details", tint = Color.White)
                            }
                            if (sharing) {
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                                }
                            } else {
                                IconButton(onClick = {
                                    sharing = true
                                    scope.launch { shareMedia(context, currentItem); sharing = false }
                                }) {
                                    Icon(Icons.Filled.Share, contentDescription = "Senden", tint = Color.White)
                                }
                            }
                            IconButton(onClick = { deleteCurrent() }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Löschen", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }

        if (showInfo) {
            InfoOverlay(
                details = details,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                onClose = { showInfo = false },
            )
        }
    }

    if (showDest) {
        com.melone.gallery.ui.components.ServerFolderPickerDialog(
            smb = app.container.smbManager,
            move = pendingMove,
            serverFolders = serverConfig.folders,
            onDismiss = { showDest = false },
            onPickLocal = { showDest = false; treeLauncher.launch(null) },
            onPickServer = { share, path ->
                showDest = false
                pendingItem?.let { runTransfer(it, TransferTarget.Server(share, path), pendingMove) }
            },
        )
    }

    if (showDeleteConfirm) {
        val item = currentItem
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("In den Papierkorb?") },
            text = { Text("${item.displayName} in den Server-Papierkorb verschieben (Auto-Löschung nach 30 Tagen)?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        val res = transfer.trashServerSource(item, System.currentTimeMillis())
                        if (res.isSuccess) { toast("In den Papierkorb"); onDeleted(item); onBack() }
                        else toast("Fehler: ${res.exceptionOrNull()?.message ?: "unbekannt"}")
                    }
                }) { Text("In Papierkorb") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun InfoOverlay(
    details: MediaDetails?,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Details", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (details == null) {
            CircularProgressIndicator(Modifier.height(24.dp), strokeWidth = 2.dp)
        } else {
            InfoRow("Datum", details.dateText)
            InfoRow("Standort", details.locationText)
            InfoRow("Auflösung", details.resolutionText)
            InfoRow("Größe", details.sizeText)
            InfoRow("Dauer", details.durationText)
            InfoRow("Belichtungszeit", details.exposureText)
            InfoRow("ISO", details.isoText)
            InfoRow("Fokus", details.focalText)
            InfoRow("Blende", details.apertureText)
            InfoRow("Blitz", details.flashText)
            InfoRow("Kamera", details.cameraModel)
            InfoRow("Dateiname", details.fileName)
            InfoRow("Pfad", details.filePath)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(130.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Blendet eine echte Dateiendung aus (z. B. ".mp4", ".jpg"), lässt aber Punkte im Titel
 * selbst stehen: nur ein kurzes, rein alphanumerisches letztes Segment gilt als Endung.
 */
private fun stripDisplayExtension(name: String): String {
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return name
    val ext = name.substring(dot + 1)
    return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) name.substring(0, dot) else name
}

private fun android.content.Context.findActivityOrNull(): Activity? {
    var c: android.content.Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * System-Dialog (Löschen/Papierkorb) starten, ohne dass der automatische Miniplayer anspringt.
 *
 * Startet die App selbst eine fremde Activity, ruft Android vorher `onUserLeaveHint()` auf, und
 * genau daran hängt in [MainActivity] der Wechsel in Bild-in-Bild. Für Android sieht das aus wie
 * ein Druck auf die Home-Taste. `FLAG_ACTIVITY_NO_USER_ACTION` unterdrückt diesen Rückruf, der
 * Miniplayer bleibt damit dem echten Verlassen der App vorbehalten.
 */
private fun noUserActionRequest(sender: android.content.IntentSender): IntentSenderRequest =
    IntentSenderRequest.Builder(sender)
        .setFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION, Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        .build()

/** Wie [ActivityResultContracts.GetContent], nur ohne den Miniplayer auszulösen. */
private class PickContentQuiet : ActivityResultContracts.GetContent() {
    override fun createIntent(context: android.content.Context, input: String): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
}

/** Wie [ActivityResultContracts.OpenDocumentTree], nur ohne den Miniplayer auszulösen. */
private class OpenTreeQuiet : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: android.content.Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
}

/**
 * Öffnet ein Bild zum Bearbeiten: zuerst der Standard-Aufruf „Bearbeiten". Gibt es
 * dafür keine App (Samsung stellt seinen Editor nicht nach außen bereit), wird das
 * Bild stattdessen in der Galerie geöffnet, wo man den Editor per Stift erreicht.
 * Liefert false, wenn beides scheitert.
 */
private fun openForEditing(
    context: android.content.Context,
    uri: Uri,
    mimeType: String,
    /** Bei Videos direkt "Öffnen": ACTION_EDIT landet sonst im Video-Editor statt in der Galerie. */
    skipEditAction: Boolean = false,
): Boolean {
    // Bewusst OHNE createChooser: nur dann bietet Android im Auswahl-Dialog
    // "Immer/Nur einmal" an. Ist eine Standard-App gesetzt, öffnet es direkt.
    if (!skipEditAction) {
        val edit = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        }
        if (runCatching { context.startActivity(edit) }.isSuccess) return true
    } else {
        // Videos: die Samsung-Galerie nimmt sie nur per Schnellansicht an, und die
        // beantworten mehrere Apps. Daher die Galerie GEZIELT ansprechen; klappt das
        // nicht, geht es unten normal weiter (Videoplayer).
        val quick = Intent(Intent.ACTION_QUICK_VIEW).apply {
            setDataAndType(uri, mimeType)
            setPackage("com.sec.android.gallery3d")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        }
        if (runCatching { context.startActivity(quick) }.isSuccess) return true
    }

    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
    return runCatching { context.startActivity(view) }.isSuccess
}

/** Lädt eine Server-Datei in den lokalen Cache (für Bearbeiten). */
private fun cacheServerFile(context: android.content.Context, item: MediaItem): File? = runCatching {
    val app = context.applicationContext as GalleryApplication
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val out = File(dir, item.displayName)
    val f = app.container.smbManager.openFile(item.smbShare!!, item.smbPath!!)
    try {
        f.inputStream.use { input -> out.outputStream().use { output -> input.copyTo(output) } }
    } finally {
        runCatching { f.close() }
    }
    out
}.getOrNull()

/** Liefert eine teilbare content-URI. Server-Dateien werden vorher in den Cache kopiert. */
private suspend fun localContentUri(context: android.content.Context, item: MediaItem): Uri? =
    when (item.source) {
        MediaSource.LOCAL -> Uri.parse(item.id)
        MediaSource.SERVER -> withContext(Dispatchers.IO) {
            val app = context.applicationContext as GalleryApplication
            runCatching {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val out = File(dir, item.displayName)
                val f = app.container.smbManager.openFile(item.smbShare!!, item.smbPath!!)
                try {
                    f.inputStream.use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                } finally {
                    runCatching { f.close() }
                }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
            }.getOrNull()
        }
    }

/** Teilt lokal per content-URI, Server-Dateien werden vorher in den Cache geladen. */
private suspend fun shareMedia(context: android.content.Context, item: MediaItem) {
    val uri = localContentUri(context, item) ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    withContext(Dispatchers.Main) {
        context.startActivity(
            Intent.createChooser(intent, "Teilen")
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        )
    }
}

/** Öffnet den System-Dialog „Als Hintergrund festlegen" (nur Bilder). */
private suspend fun setAsWallpaper(context: android.content.Context, item: MediaItem) {
    val uri = localContentUri(context, item) ?: return
    val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        setDataAndType(uri, "image/*")
        putExtra("mimeType", "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    withContext(Dispatchers.Main) {
        context.startActivity(
            Intent.createChooser(intent, "Als Hintergrund festlegen")
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        )
    }
}


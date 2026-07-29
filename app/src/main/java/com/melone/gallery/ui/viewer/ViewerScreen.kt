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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    items: List<MediaItem>,
    startIndex: Int,
    onBack: () -> Unit,
    onDeleted: (MediaItem) -> Unit = {},
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
    // Leiste/Steuerung (oben + unten) per Berührung ein-/ausblenden.
    var chromeVisible by remember { mutableStateOf(true) }

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

    fun deleteLocalSource(item: MediaItem) {
        val uri = Uri.parse(item.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingDelete = item
            val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
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
        ActivityResultContracts.OpenDocumentTree(),
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
    var pendingEdit by remember { mutableStateOf<Pair<MediaItem, File>?>(null) }

    val editLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pending = pendingEdit
        pendingEdit = null
        if (result.resultCode == android.app.Activity.RESULT_OK && pending != null) {
            val (item, file) = pending
            scope.launch {
                transferring = true
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val dot = item.displayName.lastIndexOf('.')
                        val base = if (dot > 0) item.displayName.substring(0, dot) else item.displayName
                        val ext = if (dot > 0) item.displayName.substring(dot) else ".jpg"
                        val dir = (item.smbPath ?: "").substringBeforeLast('/', "")
                        val target = (if (dir.isEmpty()) "" else "$dir/") + base + "_bearbeitet" + ext
                        file.inputStream().use { input ->
                            app.container.smbManager.writeFile(item.smbShare!!, target, input)
                        }
                    }.isSuccess
                }
                transferring = false
                toast(if (ok) "Als neue Datei auf dem Server gespeichert" else "Speichern auf dem Server fehlgeschlagen")
            }
        }
    }

    fun editCurrent() {
        val item = currentItem
        when (item.source) {
            MediaSource.LOCAL -> {
                // Lokal: der Editor speichert selbst (inkl. Samsungs Frage Kopie/Original).
                val intent = Intent(Intent.ACTION_EDIT).apply {
                    setDataAndType(Uri.parse(item.id), item.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                runCatching { context.startActivity(Intent.createChooser(intent, "Bearbeiten mit…")) }
                    .onFailure { toast("Keine Bearbeitungs-App gefunden") }
            }
            MediaSource.SERVER -> {
                scope.launch {
                    transferring = true
                    val file = withContext(Dispatchers.IO) { cacheServerFile(context, item) }
                    transferring = false
                    if (file == null) {
                        toast("Konnte das Bild nicht laden")
                        return@launch
                    }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    pendingEdit = item to file
                    val intent = Intent(Intent.ACTION_EDIT).apply {
                        setDataAndType(uri, item.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    runCatching { editLauncher.launch(Intent.createChooser(intent, "Bearbeiten mit…")) }
                        .onFailure { toast("Keine Bearbeitungs-App gefunden"); pendingEdit = null }
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
                    trashStandaloneLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    toast("Gelöscht"); onDeleted(item); onBack()
                }
            }
            MediaSource.SERVER -> showDeleteConfirm = true
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // Nachbarseiten schon mitkomponieren → deren Bild lädt vorab.
                beyondViewportPageCount = 1,
            ) { page ->
                val item = items[page]
                val isActive = page == pagerState.currentPage
                if (item.isVideo) {
                    VideoPage(
                        item = item,
                        isActive = isActive,
                        chromeVisible = chromeVisible,
                        onToggleChrome = { chromeVisible = !chromeVisible },
                        bottomInset = insetBottom.value,
                        leftInset = insetLeft.value,
                        rightInset = insetRight.value,
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Schnelles Thumbnail als Sofort-Vorschau (meist schon im Cache),
                        // darüber lädt das Original in voller Auflösung nach.
                        coil.compose.AsyncImage(
                            model = item.thumbModel,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        ZoomableAsyncImage(
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
                            onClick = { chromeVisible = !chromeVisible },
                        )
                    }
                }
            }

            val chromeAlpha by animateFloatAsState(
                targetValue = if (chromeVisible) 1f else 0f,
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
                    title = { Text(currentItem.displayName, color = Color.White, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Color.White)
                        }
                    },
                    actions = {
                        if (transferring) {
                            CircularProgressIndicator(Modifier.width(22.dp).height(22.dp), color = Color.White, strokeWidth = 2.dp)
                        }
                        Box {
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
                                        text = { Text("Bearbeiten") },
                                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                        onClick = { menuOpen = false; editCurrent() },
                                    )
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
                        .padding(bottom = insetBottom.value + 24.dp, start = insetLeft.value, end = insetRight.value),
                    shape = RoundedCornerShape(28.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                ) {
                    Row(modifier = Modifier.padding(horizontal = 6.dp)) {
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

private fun android.content.Context.findActivityOrNull(): Activity? {
    var c: android.content.Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
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
        context.startActivity(Intent.createChooser(intent, "Teilen"))
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
        context.startActivity(Intent.createChooser(intent, "Als Hintergrund festlegen"))
    }
}


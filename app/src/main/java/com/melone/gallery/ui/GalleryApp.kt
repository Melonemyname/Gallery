package com.melone.gallery.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import com.melone.gallery.ui.components.landscape16by9
import com.melone.gallery.ui.components.landscapeSideInset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melone.gallery.data.model.StartTab
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.melone.gallery.R
import com.melone.gallery.ui.albums.AlbumsScreen
import com.melone.gallery.ui.gallery.GalleryScreen
import com.melone.gallery.ui.gallery.GalleryViewModel
import com.melone.gallery.ui.settings.SettingsScreen
import com.melone.gallery.ui.viewer.ViewerScreen

private object Routes {
    const val GALLERY = "gallery"
    const val ALBUMS = "albums"
    const val SETTINGS = "settings"
    const val TRASH = "trash"
    const val VIEWER = "viewer/{index}"
    fun viewer(index: Int) = "viewer/$index"
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun GalleryApp() {
    val navController = rememberNavController()
    val galleryVm: GalleryViewModel = viewModel(factory = AppViewModelFactories.gallery)

    val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
        )
    } else {
        listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val permissionState = rememberMultiplePermissionsState(mediaPermissions) { result ->
        galleryVm.onPermissionResult(result.values.any { it })
    }
    // Initialen Zustand melden
    galleryVm.onPermissionResult(permissionState.allPermissionsGranted)

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Alben-Navigationszustand ("" = Root, "L:.." lokales Album, "S:.." Server-Ordner).
    val albumsNav = rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // (Das Zurück im Album/Ordner wird jetzt in AlbumsScreen selbst behandelt, weil
    // es dort zuverlässig an das Alben-Ziel gebunden ist.)
    // Zurück auf einem Root-Tab (Bilder/Alben-Wurzel): zweimal drücken zum Beenden.
    var backOnce by remember { mutableStateOf(false) }
    val onRootTab = currentRoute == Routes.GALLERY ||
        (currentRoute == Routes.ALBUMS && albumsNav.value.isEmpty())
    BackHandler(enabled = onRootTab) {
        if (backOnce) {
            context.findActivityOrNull()?.finish()
        } else {
            backOnce = true
            Toast.makeText(context, "Erneut drücken zum Beenden", Toast.LENGTH_SHORT).show()
            scope.launch { delay(2000); backOnce = false }
        }
    }

    // Beim Zurückkehren in den Vordergrund den Server aktualisieren (gedrosselt).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        galleryVm.refreshServerOnResume()
    }

    // Start-Tab aus den Einstellungen einmalig ansteuern.
    val galleryState by galleryVm.state.collectAsStateWithLifecycle()
    var initialNavDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(galleryState.prefsLoaded) {
        if (galleryState.prefsLoaded && !initialNavDone) {
            initialNavDone = true
            if (galleryState.prefs.startTab == StartTab.ALBUMS) {
                navController.navigate(Routes.ALBUMS) {
                    popUpTo(Routes.GALLERY) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = Routes.GALLERY) {
        composable(Routes.GALLERY) {
            MainScaffold(
                currentRoute, navController, showBottomBar = true,
                onSelectTab = { route -> if (route == Routes.ALBUMS) albumsNav.value = "" },
            ) {
                GalleryScreen(
                    viewModel = galleryVm,
                    permissionState = permissionState,
                    onOpenViewer = { items, index ->
                        galleryVm.setViewerItems(items)
                        navController.navigate(Routes.viewer(index))
                    },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    contentPadding = it,
                )
            }
        }
        composable(Routes.ALBUMS) {
            // Bottom-Nav nur auf der Album-Wurzel; in einem Album/Ordner mehr Platz.
            MainScaffold(
                currentRoute, navController, showBottomBar = albumsNav.value.isEmpty(),
                onSelectTab = { route -> if (route == Routes.ALBUMS) albumsNav.value = "" },
            ) {
                AlbumsScreen(
                    viewModel = galleryVm,
                    nav = albumsNav.value,
                    onNavChange = { albumsNav.value = it },
                    onOpenViewer = { items, index ->
                        galleryVm.setViewerItems(items)
                        navController.navigate(Routes.viewer(index))
                    },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    contentPadding = it,
                )
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TRASH) {
            com.melone.gallery.ui.trash.TrashScreen(onBack = { navController.popBackStack() })
        }
        composable(
            Routes.VIEWER,
            popExitTransition = { ExitTransition.None },
        ) { entry ->
            val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
            ViewerScreen(
                items = galleryVm.viewerItems(),
                startIndex = index,
                onBack = { navController.popBackStack() },
                onDeleted = { item ->
                    when (item.source) {
                        com.melone.gallery.data.model.MediaSource.LOCAL -> galleryVm.removeLocalItems(setOf(item.id))
                        com.melone.gallery.data.model.MediaSource.SERVER -> galleryVm.removeServerItems(setOf(item.id))
                    }
                },
            )
        }
    }
}

private fun Context.findActivityOrNull(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private data class TopTab(val route: String, val labelRes: Int)

@Composable
private fun MainScaffold(
    currentRoute: String?,
    navController: androidx.navigation.NavHostController,
    showBottomBar: Boolean,
    onSelectTab: (String) -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    androidx.compose.material3.Scaffold(
        floatingActionButton = {
            if (showBottomBar) {
                // Im Querformat auf die Linie des 16:9-Inhalts einrücken (der FAB sitzt
                // im Scaffold-Slot, also außerhalb des begrenzten Bereichs).
                FloatingActionButton(
                    onClick = { navController.navigate(Routes.TRASH) },
                    modifier = Modifier.padding(end = landscapeSideInset()),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Papierkorb")
                }
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    val tabs = listOf(
                        TopTab(Routes.GALLERY, R.string.nav_pictures),
                        TopTab(Routes.ALBUMS, R.string.nav_albums),
                    )
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                onSelectTab(tab.route)
                                if (!selected) {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                val icon = when (tab.route) {
                                    Routes.GALLERY -> if (selected) Icons.Filled.Image else Icons.Outlined.Image
                                    else -> if (selected) Icons.Filled.Collections else Icons.Outlined.Collections
                                }
                                Icon(icon, contentDescription = null)
                            },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Im Querformat den Inhalt auf 16:9 begrenzen und zentrieren, damit nichts
        // bis an die Ränder (und unter die Systemleisten) läuft.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Reihenfolge wichtig: erst begrenzen, dann füllen (sonst legt fillMaxSize
            // die Breite schon fest und die Begrenzung bleibt wirkungslos).
            Box(modifier = Modifier.landscape16by9().fillMaxSize()) {
                content(padding)
            }
        }
    }
}

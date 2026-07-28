package com.melone.gallery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melone.gallery.data.model.StartTab
import com.melone.gallery.data.settings.ServerFolder
import com.melone.gallery.data.settings.SettingsRepository
import com.melone.gallery.data.smb.SmbCredentials
import com.melone.gallery.data.smb.SmbEntry
import com.melone.gallery.data.smb.SmbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val folders: List<ServerFolder> = emptyList(),
    val testing: Boolean = false,
    val testMessage: String? = null,
    val testSuccess: Boolean? = null,
    val saved: Boolean = false,
    val loaded: Boolean = false,
)

/** Zustand des SMB-Ordner-Browsers (Dialog). */
data class BrowseState(
    val active: Boolean = false,
    /** Leer = auf der Freigaben-Ebene (Auswahl der "Platten"). */
    val share: String = "",
    val path: String = "",
    val entries: List<SmbEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Verfügbare Freigaben (Platten) des Servers. */
    val shares: List<String> = emptyList(),
    val loadingShares: Boolean = false,
) {
    /** True, solange keine Freigabe gewählt ist (oberste Ebene). */
    val atRoot: Boolean get() = share.isBlank()
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val smb: SmbManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    /** Start-Tab-Einstellung (Bilder/Alben). */
    val startTab: StateFlow<StartTab> = settings.uiPrefs
        .map { it.startTab }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StartTab.GALLERY)

    fun setStartTab(tab: StartTab) = viewModelScope.launch { settings.setStartTab(tab) }

    /** "Server beim Start neu laden" (sonst aus Cache). */
    val reloadServerOnStart: StateFlow<Boolean> = settings.uiPrefs
        .map { it.reloadServerOnStart }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setReloadServerOnStart(enabled: Boolean) = viewModelScope.launch { settings.setReloadServerOnStart(enabled) }

    init {
        viewModelScope.launch {
            val config = settings.serverConfig.first()
            _state.update {
                it.copy(
                    host = config.host,
                    username = config.username,
                    folders = config.folders,
                    password = settings.secure.password,
                    loaded = true,
                )
            }
        }
    }

    fun onHostChange(v: String) = _state.update { it.copy(host = v, saved = false) }
    fun onUserChange(v: String) = _state.update { it.copy(username = v, saved = false) }
    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, saved = false) }

    fun save() {
        val s = _state.value
        viewModelScope.launch {
            settings.setServer(s.host, s.username, s.password)
            settings.setFolders(s.folders)
            _state.update { it.copy(saved = true) }
        }
    }

    fun testConnection() {
        val s = _state.value
        if (s.host.isBlank() || s.username.isBlank()) {
            _state.update { it.copy(testSuccess = false, testMessage = "Host und Benutzer angeben.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(testing = true, testMessage = null, testSuccess = null) }
            val result = withContext(Dispatchers.IO) {
                smb.testAuth(SmbCredentials(s.host.trim(), s.username.trim(), s.password))
            }
            // laufende Zugangsdaten wieder auf gespeicherten Stand bringen
            reapplySavedCredentials()
            _state.update {
                it.copy(
                    testing = false,
                    testSuccess = result.isSuccess,
                    testMessage = result.fold(
                        onSuccess = { "Verbindung ok. Angemeldet als '${s.username.trim()}'." },
                        onFailure = { e -> "Fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}" },
                    ),
                )
            }
        }
    }

    private suspend fun reapplySavedCredentials() {
        val config = settings.serverConfig.first()
        val pw = settings.secure.password
        if (config.isConfigured && pw.isNotEmpty()) {
            smb.updateCredentials(SmbCredentials(config.host, config.username, pw))
        } else {
            smb.updateCredentials(null)
        }
    }

    // --- Ordner-Verwaltung ---

    fun addFolder(folder: ServerFolder) {
        _state.update { st ->
            if (st.folders.any { it.share == folder.share && it.rootPath == folder.rootPath }) st
            else st.copy(folders = st.folders + folder, saved = false)
        }
    }

    fun removeFolder(folder: ServerFolder) {
        _state.update { it.copy(folders = it.folders.filterNot { f -> f == folder }, saved = false) }
    }

    fun toggleExclude(folder: ServerFolder, excludeRel: String) {
        _state.update { st ->
            st.copy(
                folders = st.folders.map { f ->
                    if (f == folder) {
                        val newExcludes = if (excludeRel in f.excludes) f.excludes - excludeRel
                        else f.excludes + excludeRel
                        f.copy(excludes = newExcludes)
                    } else f
                },
                saved = false,
            )
        }
    }

    // --- SMB-Browser ---

    fun openBrowser() {
        // Test-/aktuelle Zugangsdaten für den Browser setzen
        val s = _state.value
        viewModelScope.launch {
            smb.updateCredentials(SmbCredentials(s.host.trim(), s.username.trim(), s.password))
            _browse.update { BrowseState(active = true, share = "", path = "") }
            loadShares()
        }
    }

    fun closeBrowser() {
        _browse.update { BrowseState(active = false) }
    }

    private fun loadShares() {
        viewModelScope.launch {
            _browse.update { it.copy(loadingShares = true, error = null) }
            val result = withContext(Dispatchers.IO) { runCatching { smb.listShares() } }
            _browse.update {
                it.copy(
                    loadingShares = false,
                    shares = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName },
                )
            }
        }
    }

    /** Freigabe (Platte) öffnen und deren Wurzel listen. */
    fun enterShare(share: String) {
        _browse.update { it.copy(share = share, path = "", entries = emptyList()) }
        listCurrent()
    }

    /** Manuelle Eingabe einer Freigabe (Fallback, falls Auflisten scheitert). */
    fun setBrowseShare(share: String) {
        _browse.update { it.copy(share = share, path = "") }
        if (share.isNotBlank()) listCurrent()
    }

    fun enter(dirName: String) {
        _browse.update {
            val newPath = if (it.path.isEmpty()) dirName else "${it.path}/$dirName"
            it.copy(path = newPath)
        }
        listCurrent()
    }

    fun navigateUp() {
        val b = _browse.value
        when {
            // Aus einer Freigabe zurück auf die Freigaben-Ebene.
            b.path.isEmpty() && b.share.isNotBlank() -> {
                _browse.update { it.copy(share = "", path = "", entries = emptyList()) }
                if (b.shares.isEmpty()) loadShares()
            }
            b.path.isNotEmpty() -> {
                val newPath = b.path.substringBeforeLast('/', "")
                _browse.update { it.copy(path = newPath) }
                listCurrent()
            }
        }
    }

    private fun listCurrent() {
        val b = _browse.value
        if (b.share.isBlank()) {
            _browse.update { it.copy(entries = emptyList(), error = "Freigabe angeben.") }
            return
        }
        viewModelScope.launch {
            _browse.update { it.copy(loading = true, error = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching { smb.list(b.share, b.path).filter { it.isDirectory }.sortedBy { it.name.lowercase() } }
            }
            _browse.update {
                it.copy(
                    loading = false,
                    entries = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName },
                )
            }
        }
    }

    /** Listet die direkten Unterordner eines Roots (für die Exclude-Auswahl). */
    suspend fun listSubfolders(folder: ServerFolder): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            smb.list(folder.share, folder.rootPath)
                .filter { it.isDirectory }
                .map { if (folder.rootPath.isEmpty()) it.name else "${folder.rootPath}/${it.name}" }
                .sorted()
        }.getOrDefault(emptyList())
    }
}

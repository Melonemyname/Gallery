package com.melone.gallery.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.melone.gallery.data.model.Grouping
import com.melone.gallery.data.model.SortDirection
import com.melone.gallery.data.model.SortField
import com.melone.gallery.data.model.SortOption
import com.melone.gallery.data.model.SourceFilter
import com.melone.gallery.data.model.StartTab
import com.melone.gallery.data.model.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "gallery_settings")

/** Persistente UI-Einstellungen der Galerie. */
data class UiPrefs(
    val viewMode: ViewMode = ViewMode.GRID,
    val grouping: Grouping = Grouping.DAY,
    val sort: SortOption = SortOption(),
    val sourceFilter: SourceFilter = SourceFilter.ALL,
    val gridColumns: Int = 4,
    /** Timeline: true = gemischt, false = nach Quelle getrennt. */
    val timelineMixed: Boolean = true,
    /** Start-Tab beim App-Start. */
    val startTab: StartTab = StartTab.GALLERY,
    /** Server-Medien bei jedem App-Start neu laden (sonst aus Cache). */
    val reloadServerOnStart: Boolean = false,
    /** Ansicht/Sortierung der Alben-Ansicht – getrennt von der Bilder-Timeline. */
    val albumViewMode: ViewMode = ViewMode.GRID,
    val albumSort: SortOption = SortOption(),
)

class SettingsRepository(private val context: Context) {

    val secure = SecureCredentials(context)

    val serverConfig: Flow<ServerConfig> = context.dataStore.data.map { p ->
        ServerConfig(
            host = p[KEY_HOST] ?: "",
            username = p[KEY_USER] ?: "",
            folders = decodeFolders(p[KEY_FOLDERS]),
        )
    }

    val uiPrefs: Flow<UiPrefs> = context.dataStore.data.map { p ->
        UiPrefs(
            viewMode = runCatching { ViewMode.valueOf(p[KEY_VIEW_MODE] ?: "") }.getOrDefault(ViewMode.GRID),
            grouping = runCatching { Grouping.valueOf(p[KEY_GROUPING] ?: "") }.getOrDefault(Grouping.DAY),
            sort = SortOption(
                field = runCatching { SortField.valueOf(p[KEY_SORT_FIELD] ?: "") }.getOrDefault(SortField.DATE_TAKEN),
                direction = runCatching { SortDirection.valueOf(p[KEY_SORT_DIR] ?: "") }.getOrDefault(SortDirection.DESC),
            ),
            sourceFilter = runCatching { SourceFilter.valueOf(p[KEY_SOURCE_FILTER] ?: "") }.getOrDefault(SourceFilter.ALL),
            gridColumns = (p[KEY_GRID_COLUMNS] ?: 4).coerceIn(2, 8),
            timelineMixed = (p[KEY_TIMELINE_MIXED] ?: 1) == 1,
            startTab = runCatching { StartTab.valueOf(p[KEY_START_TAB] ?: "") }.getOrDefault(StartTab.GALLERY),
            reloadServerOnStart = (p[KEY_RELOAD_SERVER] ?: 0) == 1,
            albumViewMode = runCatching { ViewMode.valueOf(p[KEY_ALBUM_VIEW_MODE] ?: "") }.getOrDefault(ViewMode.GRID),
            albumSort = SortOption(
                field = runCatching { SortField.valueOf(p[KEY_ALBUM_SORT_FIELD] ?: "") }.getOrDefault(SortField.DATE_TAKEN),
                direction = runCatching { SortDirection.valueOf(p[KEY_ALBUM_SORT_DIR] ?: "") }.getOrDefault(SortDirection.DESC),
            ),
        )
    }

    suspend fun setServer(host: String, username: String, password: String) {
        context.dataStore.edit { p ->
            p[KEY_HOST] = host.trim()
            p[KEY_USER] = username.trim()
        }
        secure.password = password
    }

    suspend fun setFolders(folders: List<ServerFolder>) {
        context.dataStore.edit { p -> p[KEY_FOLDERS] = encodeFolders(folders) }
    }

    suspend fun setViewMode(mode: ViewMode) =
        context.dataStore.edit { it[KEY_VIEW_MODE] = mode.name }

    suspend fun setGrouping(grouping: Grouping) =
        context.dataStore.edit { it[KEY_GROUPING] = grouping.name }

    suspend fun setSort(sort: SortOption) = context.dataStore.edit {
        it[KEY_SORT_FIELD] = sort.field.name
        it[KEY_SORT_DIR] = sort.direction.name
    }

    suspend fun setSourceFilter(filter: SourceFilter) =
        context.dataStore.edit { it[KEY_SOURCE_FILTER] = filter.name }

    suspend fun setGridColumns(columns: Int) =
        context.dataStore.edit { it[KEY_GRID_COLUMNS] = columns.coerceIn(2, 8) }

    suspend fun setTimelineMixed(mixed: Boolean) =
        context.dataStore.edit { it[KEY_TIMELINE_MIXED] = if (mixed) 1 else 0 }

    suspend fun setStartTab(tab: StartTab) =
        context.dataStore.edit { it[KEY_START_TAB] = tab.name }

    suspend fun setReloadServerOnStart(enabled: Boolean) =
        context.dataStore.edit { it[KEY_RELOAD_SERVER] = if (enabled) 1 else 0 }

    suspend fun setAlbumViewMode(mode: ViewMode) =
        context.dataStore.edit { it[KEY_ALBUM_VIEW_MODE] = mode.name }

    suspend fun setAlbumSort(sort: SortOption) = context.dataStore.edit {
        it[KEY_ALBUM_SORT_FIELD] = sort.field.name
        it[KEY_ALBUM_SORT_DIR] = sort.direction.name
    }

    // --- JSON (de)serialisierung der Ordnerliste via org.json ---

    private fun encodeFolders(folders: List<ServerFolder>): String {
        val arr = JSONArray()
        for (f in folders) {
            val o = JSONObject()
            o.put("share", f.share)
            o.put("rootPath", f.rootPath)
            o.put("recursive", f.recursive)
            o.put("excludes", JSONArray(f.excludes))
            arr.put(o)
        }
        return arr.toString()
    }

    private fun decodeFolders(json: String?): List<ServerFolder> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val exArr = o.optJSONArray("excludes") ?: JSONArray()
                ServerFolder(
                    share = o.getString("share"),
                    rootPath = o.optString("rootPath", ""),
                    recursive = o.optBoolean("recursive", true),
                    excludes = (0 until exArr.length()).map { exArr.getString(it) },
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        val KEY_HOST = stringPreferencesKey("host")
        val KEY_USER = stringPreferencesKey("user")
        val KEY_FOLDERS = stringPreferencesKey("folders_json")
        val KEY_VIEW_MODE = stringPreferencesKey("view_mode")
        val KEY_GROUPING = stringPreferencesKey("grouping")
        val KEY_SORT_FIELD = stringPreferencesKey("sort_field")
        val KEY_SORT_DIR = stringPreferencesKey("sort_dir")
        val KEY_SOURCE_FILTER = stringPreferencesKey("source_filter")
        val KEY_GRID_COLUMNS = intPreferencesKey("grid_columns")
        val KEY_TIMELINE_MIXED = intPreferencesKey("timeline_mixed")
        val KEY_START_TAB = stringPreferencesKey("start_tab")
        val KEY_RELOAD_SERVER = intPreferencesKey("reload_server_on_start")
        val KEY_ALBUM_VIEW_MODE = stringPreferencesKey("album_view_mode")
        val KEY_ALBUM_SORT_FIELD = stringPreferencesKey("album_sort_field")
        val KEY_ALBUM_SORT_DIR = stringPreferencesKey("album_sort_dir")
    }
}

package com.melone.gallery.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melone.gallery.data.model.MediaItem
import com.melone.gallery.domain.MediaDetails
import com.melone.gallery.domain.MetadataReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ViewerViewModel(
    private val reader: MetadataReader,
) : ViewModel() {

    private val _details = MutableStateFlow<MediaDetails?>(null)
    val details: StateFlow<MediaDetails?> = _details.asStateFlow()

    private var loadedId: String? = null

    fun loadDetails(item: MediaItem) {
        if (loadedId == item.id && _details.value != null) return
        loadedId = item.id
        _details.value = null
        viewModelScope.launch {
            val d = reader.read(item)
            if (loadedId == item.id) _details.value = d
        }
    }

    fun clear() {
        loadedId = null
        _details.value = null
    }
}

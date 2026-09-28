package com.melone.gallery.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.isActive

/**
 * Auswahl durch Wischen („drag select", wie in Google Fotos).
 *
 * Langes Halten auf einer Kachel startet die Auswahl. Ohne loszulassen fährt man über
 * weitere Kacheln, und alles zwischen Startkachel und Finger wird mit ausgewählt. Zieht man
 * wieder zurück, schrumpft die Auswahl entsprechend. Am oberen und unteren Rand scrollt die
 * Liste von selbst weiter, damit man auch über den sichtbaren Bereich hinaus auswählen kann.
 *
 * Eine bereits bestehende Auswahl bleibt erhalten: Der Wisch legt nur seinen Bereich obendrauf.
 *
 * WICHTIG: Die Kacheln dürfen kein eigenes `onLongClick` haben, sonst verschluckt es das lange
 * Halten und diese Geste kommt nie zum Zug (siehe [SelectableThumb]).
 *
 * @param orderedIds Die IDs der auswählbaren Elemente in Anzeigereihenfolge. Alles, was hier
 *   nicht vorkommt (Datums-Überschriften, Ordnerkacheln), wird beim Wischen übersprungen.
 */
@Composable
fun Modifier.dragSelect(
    gridState: LazyGridState,
    orderedIds: List<String>,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
): Modifier = dragSelectInternal(
    orderedIds = orderedIds,
    selected = selected,
    onSelectedChange = onSelectedChange,
    scrollBy = { gridState.scrollBy(it) },
    indexAt = { pos, ids -> gridState.indexAt(pos, ids) },
)

/** Wie oben, nur für Listen- und Detailansicht (LazyColumn). */
@Composable
fun Modifier.dragSelect(
    listState: LazyListState,
    orderedIds: List<String>,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
): Modifier = dragSelectInternal(
    orderedIds = orderedIds,
    selected = selected,
    onSelectedChange = onSelectedChange,
    scrollBy = { listState.scrollBy(it) },
    indexAt = { pos, ids -> listState.indexAt(pos, ids) },
)

/** Merkt sich den Zustand einer laufenden Wischauswahl. Bewusst KEIN Compose-State: */
/* die Werte ändern sich pro Bild, ein Neuzeichnen des Rasters wäre reine Verschwendung. */
private class DragSelectState {
    /** Index der Kachel, auf der der Wisch begonnen hat. -1 = gerade kein Wisch. */
    var anchor = -1

    /** Auswahl, die vor dem Wisch schon bestand. */
    var base: Set<String> = emptySet()
    var pointer: Offset = Offset.Unspecified
    var viewportHeight = 0
}

@Composable
private fun Modifier.dragSelectInternal(
    orderedIds: List<String>,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    scrollBy: suspend (Float) -> Unit,
    indexAt: (Offset, List<String>) -> Int,
): Modifier {
    val ids by rememberUpdatedState(orderedIds)
    val currentSelection by rememberUpdatedState(selected)
    val onChange by rememberUpdatedState(onSelectedChange)
    val hitTest by rememberUpdatedState(indexAt)
    val scroll by rememberUpdatedState(scrollBy)
    val haptics = LocalHapticFeedback.current

    val drag = remember { DragSelectState() }
    var dragging by remember { mutableStateOf(false) }

    /** Wählt alles zwischen Startkachel und aktueller Kachel aus. */
    fun applyRange(index: Int) {
        if (drag.anchor < 0 || index < 0) return
        val range = if (index >= drag.anchor) drag.anchor..index else index..drag.anchor
        onChange(drag.base + range.mapNotNull { ids.getOrNull(it) })
    }

    // Randbereiche: solange der Finger dort steht, weiterscrollen und dabei die Auswahl
    // nachziehen. Ohne das Nachziehen bliebe sie stehen, weil der Finger sich nicht bewegt.
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            val height = drag.viewportHeight
            val pos = drag.pointer
            if (height <= 0 || pos == Offset.Unspecified) continue
            val edge = height * 0.12f
            val delta = when {
                pos.y < edge -> -(edge - pos.y) / 6f
                pos.y > height - edge -> (pos.y - (height - edge)) / 6f
                else -> 0f
            }
            if (delta != 0f) {
                scroll(delta)
                applyRange(hitTest(pos, ids))
            }
        }
    }

    return this.pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val index = hitTest(offset, ids)
                if (index >= 0) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    drag.anchor = index
                    drag.base = currentSelection
                    drag.pointer = offset
                    drag.viewportHeight = size.height
                    dragging = true
                    onChange(drag.base + ids[index])
                }
            },
            onDrag = { change, _ ->
                if (drag.anchor < 0) return@detectDragGesturesAfterLongPress
                change.consume()
                drag.pointer = change.position
                applyRange(hitTest(change.position, ids))
            },
            onDragEnd = { drag.anchor = -1; dragging = false },
            onDragCancel = { drag.anchor = -1; dragging = false },
        )
    }
}

/**
 * Welche Kachel liegt unter dem Finger? Liefert deren Position in [ids], sonst -1.
 *
 * Die Positionen in `layoutInfo` beziehen sich auf denselben Ursprung wie die Zeigerposition
 * (linke obere Ecke des Rasters), ein direkter Vergleich reicht also.
 */
private fun LazyGridState.indexAt(position: Offset, ids: List<String>): Int {
    val info = layoutInfo.visibleItemsInfo.firstOrNull { item ->
        position.x >= item.offset.x && position.x <= item.offset.x + item.size.width &&
            position.y >= item.offset.y && position.y <= item.offset.y + item.size.height
    } ?: return -1
    val key = info.key as? String ?: return -1
    return ids.indexOf(key)
}

/** Wie oben, in der Liste zählt nur die senkrechte Position. */
private fun LazyListState.indexAt(position: Offset, ids: List<String>): Int {
    val info = layoutInfo.visibleItemsInfo.firstOrNull { item ->
        position.y >= item.offset && position.y <= item.offset + item.size
    } ?: return -1
    val key = info.key as? String ?: return -1
    return ids.indexOf(key)
}

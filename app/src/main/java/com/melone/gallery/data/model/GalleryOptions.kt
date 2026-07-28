package com.melone.gallery.data.model

/** Kachel / Liste / Details. */
enum class ViewMode { GRID, LIST, DETAILS }

/** Zeitliche Gruppierungs-Granularität in der Timeline. */
enum class Grouping { DAY, MONTH }

/** Sortierfeld. */
enum class SortField { DATE_TAKEN, DATE_MODIFIED, NAME }

enum class SortDirection { DESC, ASC }

/** Quelle-Filter in der Timeline. */
enum class SourceFilter { ALL, LOCAL, SERVER }

/** Tab, mit dem die App startet. */
enum class StartTab { GALLERY, ALBUMS }

data class SortOption(
    val field: SortField = SortField.DATE_TAKEN,
    val direction: SortDirection = SortDirection.DESC,
)

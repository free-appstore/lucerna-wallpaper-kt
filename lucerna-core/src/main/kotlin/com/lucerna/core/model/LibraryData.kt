package com.lucerna.core.model

import kotlinx.serialization.Serializable

/** library.json 的根结构。 */
@Serializable
data class LibraryData(
    val items: List<WallpaperItem> = emptyList(),
)

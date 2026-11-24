package com.lucerna.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/** 图库条目的媒体类型。 */
@Serializable
enum class WallpaperType {
    @SerialName("image")
    IMAGE,

    @SerialName("video")
    VIDEO;

    val label: String
        get() = when (this) {
            IMAGE -> "图片"
            VIDEO -> "视频"
        }
}

/**
 * 导入到 Lucerna 图库的一条壁纸。
 *
 * [path] 指向图库目录里的副本，[thumbnail] 是导入时生成的缩略图，
 * 视频没有缩略图时界面会显示占位卡片。
 */
@Serializable
data class WallpaperItem(
    val id: String,
    val name: String,
    val type: WallpaperType,
    val path: String,
    val addedAt: Long = 0L,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnail: String? = null,
    val durationSeconds: Int? = null,
) {
    val file: File get() = File(path)

    val exists: Boolean get() = file.isFile

    /** 卡片副标题，例如「图片 · 3840×2160」或「视频 · 0:24」。 */
    fun subtitle(): String = buildString {
        append(type.label)
        if (width != null && height != null) append(" · ${width}×${height}")
        if (durationSeconds != null && durationSeconds > 0) {
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            append(" · $minutes:${seconds.toString().padStart(2, '0')}")
        }
    }
}

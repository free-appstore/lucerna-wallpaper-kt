package com.lucerna.core.media

import com.lucerna.core.model.WallpaperType
import java.io.File

/** 支持的图片与视频扩展名，统一小写、不带点。 */
object MediaFormats {

    val IMAGE_EXTENSIONS: Set<String> = setOf(
        "jpg", "jpeg", "png", "webp", "bmp", "gif", "tif", "tiff", "heic", "heif", "avif",
    )

    val VIDEO_EXTENSIONS: Set<String> = setOf(
        "mp4", "mov", "m4v", "mkv", "webm", "avi", "wmv", "flv", "ts", "m2ts", "mpg", "mpeg", "3gp",
    )

    fun extensionOf(file: File): String = file.name.substringAfterLast('.', "").lowercase()

    fun typeOf(file: File): WallpaperType? {
        val ext = extensionOf(file)
        return when {
            ext in IMAGE_EXTENSIONS -> WallpaperType.IMAGE
            ext in VIDEO_EXTENSIONS -> WallpaperType.VIDEO
            else -> null
        }
    }

    fun isSupported(file: File): Boolean = typeOf(file) != null
}

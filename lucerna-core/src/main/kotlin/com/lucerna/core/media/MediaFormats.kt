package com.lucerna.core.media

import com.lucerna.core.model.WallpaperType
import java.io.File

/** 支持的图片与视频扩展名，统一小写、不带点。 */
object MediaFormats {

/**
 * 只列 Coil3（Skia）确实能解码的图片格式。
 *
 * 系统「预览」能打开 HEIC / HEIF / AVIF / TIFF，但 Skia 解不了，
 * 列出来只会得到破图或空白，所以这里不支持它们。
 */
val IMAGE_EXTENSIONS: Set<String> = setOf(
    "jpg", "jpeg", "jfif", "png", "webp", "bmp", "gif",
)

/** 视频由 MediaMP 的 mpv / FFmpeg 解码，这些容器都能读。 */
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

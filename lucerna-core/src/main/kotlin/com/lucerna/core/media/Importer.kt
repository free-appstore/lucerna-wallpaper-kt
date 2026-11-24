package com.lucerna.core.media

import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperType
import com.lucerna.core.paths.AppPaths
import com.lucerna.core.store.LibraryStore
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 把外部文件复制进 Lucerna 图库，并生成图片缩略图。
 *
 * 复制而不是记录原始路径，避免用户移动原文件后壁纸失效；
 * 导入过程可被调用方放到后台线程，`onProgress` 用于回报当前文件名。
 */
class Importer(
    private val paths: AppPaths,
    private val library: LibraryStore,
) {

    fun importFiles(files: List<File>, onProgress: (File) -> Unit = {}): List<WallpaperItem> {
        paths.ensure()
        val added = ArrayList<WallpaperItem>()
        files.forEach { source ->
            onProgress(source)
            runCatching { importOne(source) }
                .getOrNull()
                ?.let { added += it }
        }
        if (added.isNotEmpty()) library.add(added)
        return added
    }

    private fun importOne(source: File): WallpaperItem? {
        if (!source.isFile) return null
        val type = MediaFormats.typeOf(source) ?: return null
        val id = UUID.randomUUID().toString()
        val extension = MediaFormats.extensionOf(source)
        val copyName = if (extension.isBlank()) id else "$id.$extension"
        val target = paths.mediaDir.resolve(copyName).toFile()
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)

        val dimensions = if (type == WallpaperType.IMAGE) readDimensions(target) else null
        val thumbnail = if (type == WallpaperType.IMAGE) makeThumbnail(target, id, dimensions) else null

        return WallpaperItem(
            id = id,
            name = source.name,
            type = type,
            path = target.absolutePath,
            addedAt = System.currentTimeMillis(),
            width = dimensions?.first,
            height = dimensions?.second,
            thumbnail = thumbnail?.absolutePath,
        )
    }

    /** 只读图片头拿尺寸，避免为大图分配整块像素。 */
    private fun readDimensions(file: File): Pair<Int, Int>? {
        var stream: ImageInputStream? = null
        return try {
            stream = ImageIO.createImageInputStream(file)
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return null
            reader.input = stream
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            reader.dispose()
            width to height
        } catch (_: Exception) {
            null
        } finally {
            runCatching { stream?.close() }
        }
    }

    private fun makeThumbnail(file: File, id: String, dimensions: Pair<Int, Int>?): File? {
        val image = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
        val (width, height) = dimensions ?: (image.width to image.height)
        if (width <= 0 || height <= 0) return null
        val scale = minOf(1f, THUMB_MAX_WIDTH.toFloat() / width, THUMB_MAX_HEIGHT.toFloat() / height)
        val targetWidth = max(1, (width * scale).roundToInt())
        val targetHeight = max(1, (height * scale).roundToInt())
        val thumb = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val g = thumb.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(image, 0, 0, targetWidth, targetHeight, null)
        } finally {
            g.dispose()
        }
        val output = paths.thumbnailDir.resolve("$id.png").toFile()
        return runCatching {
            ImageIO.write(thumb, "png", output)
            output
        }.getOrElse { null }
    }

    companion object {
        private const val THUMB_MAX_WIDTH = 640
        private const val THUMB_MAX_HEIGHT = 400
    }
}

/** 删除图库条目对应的媒体与缩略图，失败只忽略。 */
fun deleteLibraryFiles(paths: AppPaths, item: WallpaperItem) {
    runCatching { Files.deleteIfExists(File(item.path).toPath()) }
    item.thumbnail?.let { runCatching { Files.deleteIfExists(File(it).toPath()) } }
}

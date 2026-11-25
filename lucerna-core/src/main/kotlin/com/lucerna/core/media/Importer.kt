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
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 把外部文件复制进 Lucerna 图库，并生成图片缩略图。
 *
 * 复制而不是记录原始路径，避免用户移动原文件后壁纸失效；
 * 导入过程可被调用方放到后台线程，`onProgress` 用于回报当前文件名。
 *
 * [prewarmVideoPosters] 打开时，导入完成的视频会在后台先抽好封面（见 [VideoPoster]），
 * 这样之后点「设为壁纸」不必再等 qlmanage / ffmpeg。
 */
class Importer(
    private val paths: AppPaths,
    private val library: LibraryStore,
    private val prewarmVideoPosters: Boolean = false,
) {

    private val posterExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "lucerna-video-poster").apply { isDaemon = true }
        }
    }

    fun importFiles(files: List<File>, onProgress: (File) -> Unit = {}): List<WallpaperItem> {
        paths.ensure()
        val added = ArrayList<WallpaperItem>()
        files.forEach { source ->
            onProgress(source)
            runCatching { importOne(source) }
                .getOrNull()
                ?.let { added += it }
        }
        if (added.isNotEmpty()) {
            library.add(added)
            if (prewarmVideoPosters) prewarmPosters(added)
        }
        return added
    }

    /** 视频封面在后台抽，不拖慢导入本身。 */
    private fun prewarmPosters(items: List<WallpaperItem>) {
        items.forEach { item ->
            if (item.type != WallpaperType.VIDEO) return@forEach
            posterExecutor.execute { runCatching { VideoPoster.ensure(paths, item) } }
        }
    }

    private fun importOne(source: File): WallpaperItem? {
        if (!source.isFile) return null
        val type = MediaFormats.typeOf(source) ?: return null
        val id = UUID.randomUUID().toString()
        val extension = MediaFormats.extensionOf(source)
        val copyName = if (extension.isBlank()) id else "$id.$extension"
        val target = paths.mediaDir.resolve(copyName).toFile()
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)

        // 图片只解码一次：同一个 reader 里先读尺寸，再按降采样解码出缩略图
        var dimensions: Pair<Int, Int>? = null
        var thumbnail: File? = null
        if (type == WallpaperType.IMAGE) {
            readWithImageReader(target) { reader ->
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width > 0 && height > 0) {
                    dimensions = width to height
                    thumbnail = makeThumbnail(reader, id, width, height)
                }
            }
        }

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

    /** 打开图片的 reader 执行 [block]，结束时总会释放 reader 与输入流。 */
    private inline fun readWithImageReader(file: File, block: (ImageReader) -> Unit) {
        var stream: ImageInputStream? = null
        var reader: ImageReader? = null
        try {
            stream = ImageIO.createImageInputStream(file)
            reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull()
            if (reader != null) {
                reader.input = stream
                block(reader)
            }
        } catch (_: Exception) {
            // 读不了这张图就当没有缩略图，导入本身仍然成功
        } finally {
            runCatching { reader?.dispose() }
            runCatching { stream?.close() }
        }
    }

    /**
     * 生成缩略图。
     *
     * 先用 [ImageReader] 的按整数倍抽样解码，再缩放到目标尺寸：
     * 一亿像素的原图也只解出一小块，不会整张放进内存。
     */
    private fun makeThumbnail(reader: ImageReader, id: String, width: Int, height: Int): File? {
        val scale = minOf(1f, THUMB_MAX_WIDTH.toFloat() / width, THUMB_MAX_HEIGHT.toFloat() / height)
        val targetWidth = max(1, (width * scale).roundToInt())
        val targetHeight = max(1, (height * scale).roundToInt())
        val subsample = if (scale < 1f) max(1, (1f / scale).toInt()) else 1
        val param = reader.defaultReadParam
        param.setSourceSubsampling(subsample, subsample, 0, 0)
        // 少数插件不支持抽样解码，退回整张读取
        val image = runCatching { reader.read(0, param) }.getOrElse { reader.read(0) }
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
        }.getOrNull()
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

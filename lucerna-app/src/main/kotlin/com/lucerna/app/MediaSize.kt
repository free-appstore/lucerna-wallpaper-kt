package com.lucerna.app

import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperType
import java.io.File
import javax.imageio.ImageIO

/** 视频尺寸未知时先用 16:9 占位，等播放器回报后再调整预览窗口。 */
internal val FallbackMediaSize = 16 to 9

/**
 * 预览窗口的初始媒体尺寸。
 *
 * 图库里的图片导入时已经记下宽高；导入页里还没导入的文件，这里只读图片文件头，不整张解码。
 * 视频要等播放器加载后才知道尺寸，先返回 null，由预览窗口的 onVideoSize 回调再调整。
 */
internal fun WallpaperItem.mediaSizeForPreview(): Pair<Int, Int>? {
    val knownWidth = width
    val knownHeight = height
    if (knownWidth != null && knownHeight != null && knownWidth > 0 && knownHeight > 0) {
        return knownWidth to knownHeight
    }
    return if (type == WallpaperType.IMAGE) imageFileSize(file) else null
}

/** 只读图片文件头里的像素尺寸；读不出来返回 null。 */
internal fun imageFileSize(file: File): Pair<Int, Int>? = runCatching {
    ImageIO.createImageInputStream(file)?.use { stream ->
        val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return@use null
        try {
            reader.input = stream
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width > 0 && height > 0) width to height else null
        } finally {
            reader.dispose()
        }
    }
}.getOrNull()

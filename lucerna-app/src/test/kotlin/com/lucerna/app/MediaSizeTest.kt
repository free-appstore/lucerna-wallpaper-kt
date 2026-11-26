package com.lucerna.app

import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperType
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 预览窗口打开前能拿到的媒体尺寸：图库记录优先，其次只读图片文件头。 */
class MediaSizeTest {

    @Test
    fun `图库已经记录的尺寸直接使用`() {
        val item = item(type = WallpaperType.IMAGE, width = 3840, height = 2160)

        assertEquals(3840 to 2160, item.mediaSizeForPreview())
    }

    @Test
    fun `图片缺少尺寸时读取文件头`() {
        val file = Files.createTempFile("lucerna-preview-size", ".png").toFile()
        try {
            ImageIO.write(BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB), "png", file)

            val item = item(type = WallpaperType.IMAGE).copy(path = file.absolutePath)

            assertEquals(320 to 200, item.mediaSizeForPreview())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `视频尺寸要等播放器回报`() {
        val item = item(type = WallpaperType.VIDEO)

        assertNull(item.mediaSizeForPreview())
    }

    private fun item(
        type: WallpaperType,
        width: Int? = null,
        height: Int? = null,
    ) = WallpaperItem(
        id = "test",
        name = "test",
        type = type,
        path = "/nonexistent/test",
        width = width,
        height = height,
    )
}

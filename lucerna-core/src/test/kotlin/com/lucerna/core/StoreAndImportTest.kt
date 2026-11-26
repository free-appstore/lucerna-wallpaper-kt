package com.lucerna.core

import com.lucerna.core.files.FileBrowser
import com.lucerna.core.files.ImportFilter
import com.lucerna.core.media.Importer
import com.lucerna.core.model.LibraryData
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperType
import com.lucerna.core.paths.AppPaths
import com.lucerna.core.store.LibraryStore
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StoreAndImportTest {

    private fun tempPaths(): AppPaths {
        val root = Files.createTempDirectory("lucerna-test").toFile()
        return AppPaths.at(root).also { it.ensure() }
    }

    @Test
    fun `图库增删`() {
        val store = MemoryLibraryStore()
        val item = WallpaperItem(
            id = "a",
            name = "a.png",
            type = WallpaperType.IMAGE,
            path = "/tmp/a.png",
            addedAt = 1,
        )
        store.add(listOf(item))
        assertEquals(1, store.load().items.size)
        store.remove("a")
        assertEquals(0, store.load().items.size)
    }

    @Test
    fun `导入图片会复制文件并生成缩略图`() {
        val paths = tempPaths()
        val sourceDir = Files.createTempDirectory("lucerna-src").toFile()
        val source = File(sourceDir, "firefly.png")
        ImageIO.write(BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run {
                color = Color(255, 196, 107)
                fillRect(0, 0, 1200, 800)
                dispose()
            }
        }, "png", source)

        val library = MemoryLibraryStore()
        val importer = Importer(paths, library)
        val added = importer.importFiles(listOf(source))
        assertEquals(1, added.size)
        val item = added.first()
        assertTrue(item.exists)
        assertTrue(item.path.startsWith(paths.mediaDir.toFile().absolutePath))
        assertEquals(1200, item.width)
        assertEquals(800, item.height)
        // 缩略图按 640×400 的上限等比缩小
        val thumbnail = File(assertNotNull(item.thumbnail))
        assertTrue(thumbnail.isFile)
        val thumb = ImageIO.read(thumbnail)
        assertEquals(600, thumb.width)
        assertEquals(400, thumb.height)
        assertEquals(1, library.load().items.size)
    }

    @Test
    fun `文件浏览器先文件夹后文件并遵守上限`() {
        val dir = File(tempPaths().root.toFile(), "browse").apply { mkdirs() }
        File(dir, "zeta.png").writeBytes(byteArrayOf(1))
        File(dir, "Alpha.PNG").writeBytes(byteArrayOf(1))
        File(dir, "clip.mp4").writeBytes(byteArrayOf(1))
        File(dir, "notes.txt").writeText("x")
        File(dir, ".hidden.png").writeBytes(byteArrayOf(1))
        // Skia 解不了的格式不该出现在导入列表里
        File(dir, "shot.heic").writeBytes(byteArrayOf(1))
        File(dir, "scan.tiff").writeBytes(byteArrayOf(1))
        File(dir, "wide.avif").writeBytes(byteArrayOf(1))
        File(dir, "sub").mkdirs()

        val entries = FileBrowser.list(dir, ImportFilter.ALL)
        assertEquals("sub", entries.first().name, "文件夹排在最前")
        assertEquals(
            listOf("Alpha.PNG", "clip.mp4", "zeta.png"),
            entries.drop(1).map { it.name },
            "文件按名称排序，隐藏文件与不支持的格式被过滤",
        )

        assertTrue(FileBrowser.list(dir, ImportFilter.IMAGE).none { it.type == WallpaperType.VIDEO })
        assertEquals(2, FileBrowser.list(dir, ImportFilter.ALL, limit = 2).size)
        assertTrue(FileBrowser.list(dir, ImportFilter.ALL, limit = 2).first().isDirectory)
    }

    @Test
    fun `导入视频只复制文件`() {
        val paths = tempPaths()
        val sourceDir = Files.createTempDirectory("lucerna-src-video").toFile()
        val source = File(sourceDir, "clip.mp4")
        source.writeBytes(byteArrayOf(0, 1, 2, 3))
        val library = MemoryLibraryStore()
        val added = Importer(paths, library).importFiles(listOf(source))
        assertEquals(1, added.size)
        assertEquals(WallpaperType.VIDEO, added.first().type)
        assertEquals(null, added.first().thumbnail)
    }

    @Test
    fun `不支持的文件会被忽略`() {
        val paths = tempPaths()
        val source = File(Files.createTempDirectory("lucerna-bad").toFile(), "notes.txt")
        source.writeText("hello")
        val library = MemoryLibraryStore()
        val added = Importer(paths, library).importFiles(listOf(source))
        assertTrue(added.isEmpty())
    }
}

/** 只用来验证导入流程的图库；真正的持久化在 lucerna-db 的测试里覆盖。 */
private class MemoryLibraryStore : LibraryStore {

    private var data = LibraryData()

    override fun load(): LibraryData = data

    override fun save(data: LibraryData) {
        this.data = data
    }

    override fun add(items: List<WallpaperItem>): LibraryData {
        data = LibraryData((items + data.items).distinctBy { it.id })
        return data
    }

    override fun remove(id: String): LibraryData {
        data = LibraryData(data.items.filterNot { it.id == id })
        return data
    }
}

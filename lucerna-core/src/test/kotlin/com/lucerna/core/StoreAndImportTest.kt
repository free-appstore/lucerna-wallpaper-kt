package com.lucerna.core

import com.lucerna.core.media.Importer
import com.lucerna.core.model.ColorKind
import com.lucerna.core.model.Settings
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperType
import com.lucerna.core.paths.AppPaths
import com.lucerna.core.store.LibraryStore
import com.lucerna.core.store.SettingsStore
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
    fun `设置读写往返`() {
        val paths = tempPaths()
        val store = SettingsStore(paths)
        val settings = Settings(
            volume = 0.35f,
            audioEnabled = true,
            launchAtLogin = true,
            colorFill = Settings().colorFill.copy(kind = ColorKind.STEPS, steps = 7),
        )
        store.save(settings)
        val loaded = store.load()
        assertEquals(0.35f, loaded.volume)
        assertTrue(loaded.audioEnabled)
        assertTrue(loaded.launchAtLogin)
        assertEquals(7, loaded.colorFill.steps)
        assertEquals(ColorKind.STEPS, loaded.colorFill.kind)
    }

    @Test
    fun `图库增删`() {
        val paths = tempPaths()
        val store = LibraryStore(paths)
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

        val library = LibraryStore(paths)
        val importer = Importer(paths, library)
        val added = importer.importFiles(listOf(source))
        assertEquals(1, added.size)
        val item = added.first()
        assertTrue(item.exists)
        assertTrue(item.path.startsWith(paths.mediaDir.toFile().absolutePath))
        assertNotNull(item.thumbnail)
        assertTrue(java.io.File(item.thumbnail!!).isFile)
        assertEquals(1200, item.width)
        assertEquals(800, item.height)
        assertEquals(1, library.load().items.size)
    }

    @Test
    fun `导入视频只复制文件`() {
        val paths = tempPaths()
        val sourceDir = Files.createTempDirectory("lucerna-src-video").toFile()
        val source = File(sourceDir, "clip.mp4")
        source.writeBytes(byteArrayOf(0, 1, 2, 3))
        val library = LibraryStore(paths)
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
        val library = LibraryStore(paths)
        val added = Importer(paths, library).importFiles(listOf(source))
        assertTrue(added.isEmpty())
    }
}

package com.lucerna.db

import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.ColorKind
import com.lucerna.core.model.LibraryData
import com.lucerna.core.model.Rgb
import com.lucerna.core.model.Settings
import com.lucerna.core.model.TrayIconStyle
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperType
import com.lucerna.core.paths.AppPaths
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 数据库持久化：设置、色彩、图库元数据，以及旧版 JSON 的首次迁移。 */
class LucernaDbTest {

    private fun tempPaths(): AppPaths =
        AppPaths.at(Files.createTempDirectory("lucerna-db").toFile()).also { it.ensure() }

    private fun item(id: String = "a", addedAt: Long = 1) = WallpaperItem(
        id = id,
        name = "$id.png",
        type = WallpaperType.IMAGE,
        path = "/tmp/$id.png",
        addedAt = addedAt,
        width = 1920,
        height = 1080,
    )

    @Test
    fun `设置与色彩写入数据库并读回`() {
        val db = LucernaDb.inMemory()
        try {
            val solid = ColorFill(kind = ColorKind.SOLID, colors = listOf(Rgb(0.2f, 0.4f, 0.8f)))
            val steps = ColorFill(kind = ColorKind.STEPS, steps = 7)
            val settings = Settings(
                volume = 0.35f,
                audioEnabled = true,
                videoSpeed = 2f,
                launchAtLogin = true,
                colorFill = steps,
                savedColors = listOf(solid, steps),
                trayIconStyle = TrayIconStyle.MONO,
            )

            db.settingsStore.save(settings)
            val loaded = db.settingsStore.load()

            assertEquals(0.35f, loaded.volume)
            assertTrue(loaded.audioEnabled)
            assertEquals(2f, loaded.videoSpeed)
            assertTrue(loaded.launchAtLogin)
            assertEquals(ColorKind.STEPS, loaded.colorFill.kind)
            assertEquals(7, loaded.colorFill.steps)
            assertEquals(listOf(solid, steps), loaded.savedColors)
            assertEquals(TrayIconStyle.MONO, loaded.trayIconStyle)
        } finally {
            db.close()
        }
    }

    @Test
    fun `图库增删`() {
        val db = LucernaDb.inMemory()
        try {
            db.libraryStore.add(listOf(item("a")))
            assertEquals(1, db.libraryStore.load().items.size)

            db.libraryStore.add(listOf(item("b", addedAt = 2)))
            assertEquals(listOf("b", "a"), db.libraryStore.load().items.map { it.id })

            db.libraryStore.remove("a")
            assertEquals(listOf("b"), db.libraryStore.load().items.map { it.id })
        } finally {
            db.close()
        }
    }

    @Test
    fun `重新打开数据库后数据仍在`() {
        val paths = tempPaths()
        LucernaDb.open(paths).use { db ->
            db.settingsStore.save(Settings(volume = 0.5f))
            db.libraryStore.add(listOf(item()))
        }

        LucernaDb.open(paths).use { db ->
            assertEquals(0.5f, db.settingsStore.load().volume)
            assertEquals(1, db.libraryStore.load().items.size)
        }
    }

    @Test
    fun `首次打开会导入旧版JSON`() {
        val paths = tempPaths()
        val fill = ColorFill(kind = ColorKind.STEPS, steps = 9)
        val legacySettings = Settings(volume = 0.2f, colorFill = fill, savedColors = listOf(fill))
        val json = Json { encodeDefaults = true }
        Files.writeString(paths.settingsFile, json.encodeToString(Settings.serializer(), legacySettings))
        Files.writeString(
            paths.libraryFile,
            json.encodeToString(LibraryData.serializer(), LibraryData(listOf(item()))),
        )

        LucernaDb.open(paths).use { db ->
            val settings = db.settingsStore.load()
            assertEquals(0.2f, settings.volume)
            assertEquals(fill, settings.colorFill)
            assertEquals(listOf(fill), settings.savedColors)
            assertEquals(1, db.libraryStore.load().items.size)
        }
    }
}

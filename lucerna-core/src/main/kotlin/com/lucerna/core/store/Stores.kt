package com.lucerna.core.store

import com.lucerna.core.model.LibraryData
import com.lucerna.core.model.Settings
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.paths.AppPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 读取 / 写入 settings.json。 */
class SettingsStore(private val paths: AppPaths) {

    fun load(): Settings {
        val bytes = runCatching { Files.readAllBytes(paths.settingsFile) }.getOrNull() ?: return Settings()
        return runCatching { lucernaJson.decodeFromString<Settings>(bytes.decodeToString()) }
            .map { it.normalized() }
            .getOrElse { Settings() }
    }

    fun save(settings: Settings) {
        paths.ensure()
        writeAtomically(paths.settingsFile, lucernaJson.encodeToString(Settings.serializer(), settings.normalized()).toByteArray())
    }
}

/** 读取 / 写入 library.json，图库条目按加入时间倒序保存。 */
class LibraryStore(private val paths: AppPaths) {

    fun load(): LibraryData {
        val bytes = runCatching { Files.readAllBytes(paths.libraryFile) }.getOrNull() ?: return LibraryData()
        return runCatching { lucernaJson.decodeFromString<LibraryData>(bytes.decodeToString()) }
            .getOrElse { LibraryData() }
    }

    fun save(data: LibraryData) {
        paths.ensure()
        val sorted = data.items.sortedByDescending { it.addedAt }
        writeAtomically(paths.libraryFile, lucernaJson.encodeToString(LibraryData.serializer(), LibraryData(sorted)).toByteArray())
    }

    fun add(items: List<WallpaperItem>): LibraryData {
        if (items.isEmpty()) return load()
        val data = load()
        val merged = (items + data.items).distinctBy { it.id }
        save(LibraryData(merged))
        return LibraryData(merged)
    }

    fun remove(id: String): LibraryData {
        val data = load()
        val next = data.items.filterNot { it.id == id }
        save(LibraryData(next))
        return LibraryData(next)
    }
}

/** 先写临时文件再原子替换，避免半截 JSON。 */
internal fun writeAtomically(target: Path, bytes: ByteArray) {
    Files.createDirectories(target.parent)
    val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
    Files.write(temp, bytes)
    runCatching {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.onFailure {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

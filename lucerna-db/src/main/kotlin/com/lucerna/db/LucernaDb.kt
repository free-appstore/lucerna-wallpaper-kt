package com.lucerna.db

import androidx.room3.Room
import androidx.room3.migration.Migration
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.LibraryData
import com.lucerna.core.model.Settings
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.paths.AppPaths
import com.lucerna.core.store.LibraryStore
import com.lucerna.core.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import androidx.sqlite.SQLiteConnection
import java.nio.file.Files
import java.nio.file.Path

/**
 * 应用唯一的数据库入口：settings / saved_colors / wallpapers 三张表。
 *
 * 对外暴露的是 core 里的同步 Store 接口；内部把 Room 的 suspend 查询包在
 * `runBlocking(Dispatchers.IO)` 里。查询都很小，行为和以前同步读写 JSON 一致，
 * 界面状态与控制器都不用改成协程。
 *
 * 图片、视频本体、缩略图、色彩渲染的 PNG 仍然在磁盘上，数据库只保存元数据与色彩定义。
 */
class LucernaDb private constructor(
    private val database: LucernaDatabase,
    private val paths: AppPaths?,
) : AutoCloseable {

    val settingsStore: SettingsStore = RoomSettingsStore(database)
    val libraryStore: LibraryStore = RoomLibraryStore(database)

    init {
        // 第一次切到数据库时，把旧的 settings.json / library.json 导进来，避免用户数据丢失
        paths?.let { migrateLegacyJson(it) }
    }

    override fun close() {
        database.close()
    }

    companion object {

        /** 打开应用数据目录下的 `lucerna.db`。 */
        fun open(paths: AppPaths): LucernaDb {
            Files.createDirectories(paths.root)
            val database = Room.databaseBuilder<LucernaDatabase>(paths.databaseFile.toAbsolutePath().toString())
                .setDriver(BundledSQLiteDriver())
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
            return LucernaDb(database, paths)
        }

        /** 测试用：不落盘的数据库。 */
        fun inMemory(): LucernaDb {
            val database = Room.inMemoryDatabaseBuilder<LucernaDatabase>()
                .setDriver(BundledSQLiteDriver())
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
            return LucernaDb(database, null)
        }
    }

    private fun migrateLegacyJson(paths: AppPaths) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                migrateSettings(paths)
                migrateLibrary(paths)
            }
        }.onFailure {
            println("[Lucerna] legacy json migration skipped: ${it.message}")
        }
    }

    private suspend fun migrateSettings(paths: AppPaths) {
        if (database.settingsDao().load() != null) return
        val legacy = readJson(paths.settingsFile, Settings.serializer()) ?: return
        val normalized = legacy.normalized()
        database.withWriteTransaction {
            database.settingsDao().save(normalized.toEntity())
            database.settingsDao().clearSavedColors()
            database.settingsDao().insertSavedColors(
                normalized.savedColors.mapIndexed { index, fill ->
                    SavedColorEntity(position = index, fill = fill.toJson())
                },
            )
        }
    }

    private suspend fun migrateLibrary(paths: AppPaths) {
        if (database.libraryDao().load().isNotEmpty()) return
        val legacy = readJson(paths.libraryFile, LibraryData.serializer()) ?: return
        val sorted = legacy.items.sortedByDescending { it.addedAt }
        database.withWriteTransaction {
            database.libraryDao().clear()
            database.libraryDao().upsert(sorted.map { it.toEntity() })
        }
    }

    private fun <T> readJson(file: Path, serializer: KSerializer<T>): T? = runCatching {
        if (!Files.isRegularFile(file)) return null
        dbJson.decodeFromString(serializer, Files.readString(file))
    }.getOrNull()
}

/** 同步 SettingsStore：Room 查询在 IO 线程执行，调用方保持原来的同步语义。 */
internal class RoomSettingsStore(
    private val database: LucernaDatabase,
) : SettingsStore {

    override fun load(): Settings = runCatching {
        runBlocking(Dispatchers.IO) {
            val dao = database.settingsDao()
            val entity = dao.load()
            val colors = dao.savedColors().mapNotNull { it.fill.toColorFillOrNull() }
            entity?.toModel(colors) ?: Settings().normalized()
        }
    }.getOrElse {
        println("[Lucerna] settings load failed: ${it.message}")
        Settings()
    }

    override fun save(settings: Settings) {
        val normalized = settings.normalized()
        val colors: List<ColorFill> = normalized.savedColors
        runBlocking(Dispatchers.IO) {
            database.withWriteTransaction {
                database.settingsDao().save(normalized.toEntity())
                database.settingsDao().clearSavedColors()
                database.settingsDao().insertSavedColors(
                    colors.mapIndexed { index, fill ->
                        SavedColorEntity(position = index, fill = fill.toJson())
                    },
                )
            }
        }
    }
}

/** 同步 LibraryStore：写入整表时先清空再插入，保持和旧 JSON 覆盖写一致。 */
internal class RoomLibraryStore(
    private val database: LucernaDatabase,
) : LibraryStore {

    override fun load(): LibraryData = runCatching {
        runBlocking(Dispatchers.IO) {
            LibraryData(database.libraryDao().load().map { it.toModel() })
        }
    }.getOrElse {
        println("[Lucerna] library load failed: ${it.message}")
        LibraryData()
    }

    override fun save(data: LibraryData) {
        val sorted = data.items.sortedByDescending { it.addedAt }
        runBlocking(Dispatchers.IO) {
            database.withWriteTransaction {
                database.libraryDao().clear()
                database.libraryDao().upsert(sorted.map { it.toEntity() })
            }
        }
    }

    override fun add(items: List<WallpaperItem>): LibraryData {
        if (items.isEmpty()) return load()
        val merged = (items + load().items).distinctBy { it.id }
        save(LibraryData(merged))
        return LibraryData(merged)
    }

    override fun remove(id: String): LibraryData {
        runBlocking(Dispatchers.IO) { database.libraryDao().delete(id) }
        return load()
    }
}

/** v2：设置表增加视频壁纸播放速度。 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE settings ADD COLUMN videoSpeed REAL NOT NULL DEFAULT 1.0")
    }
}

/** v3：之前短暂移除过播放速度列；保留迁移以兼容中途版本。 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE settings DROP COLUMN videoSpeed")
    }
}

/** v4：恢复视频壁纸播放速度。 */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE settings ADD COLUMN videoSpeed REAL NOT NULL DEFAULT 1.0")
    }
}

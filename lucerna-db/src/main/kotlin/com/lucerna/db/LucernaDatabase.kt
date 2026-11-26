package com.lucerna.db

import androidx.room3.Database
import androidx.room3.RoomDatabase

/** 应用数据库：设置、色彩与图库元数据。 */
@Database(
    entities = [
        SettingsEntity::class,
        SavedColorEntity::class,
        WallpaperEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
internal abstract class LucernaDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun libraryDao(): LibraryDao
}

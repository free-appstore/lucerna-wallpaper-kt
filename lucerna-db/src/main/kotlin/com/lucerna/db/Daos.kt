package com.lucerna.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert

/** settings / saved_colors 两张表的读写。 */
@Dao
internal interface SettingsDao {

    @Query("SELECT * FROM settings WHERE id = 0")
    suspend fun load(): SettingsEntity?

    @Upsert
    suspend fun save(entity: SettingsEntity)

    @Query("SELECT * FROM saved_colors ORDER BY position ASC")
    suspend fun savedColors(): List<SavedColorEntity>

    @Query("DELETE FROM saved_colors")
    suspend fun clearSavedColors()

    @Insert
    suspend fun insertSavedColors(colors: List<SavedColorEntity>)
}

/** wallpapers 表；图库条目按加入时间倒序读取。 */
@Dao
internal interface LibraryDao {

    @Query("SELECT * FROM wallpapers ORDER BY addedAt DESC")
    suspend fun load(): List<WallpaperEntity>

    @Query("DELETE FROM wallpapers")
    suspend fun clear()

    @Upsert
    suspend fun upsert(items: List<WallpaperEntity>)

    @Query("DELETE FROM wallpapers WHERE id = :id")
    suspend fun delete(id: String)
}

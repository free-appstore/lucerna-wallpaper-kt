package com.lucerna.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** 单行设置表；id 固定为 [SINGLETON_ID]。色彩列表在 [SavedColorEntity] 表里。 */
@Entity(tableName = "settings")
internal data class SettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val version: Int = 1,
    val currentWallpaperId: String? = null,
    val currentSource: String = "LIBRARY",
    /** ColorFill 的 JSON；色彩本身直接存在数据库里，不再单独写文件。 */
    val colorFill: String = "",
    val volume: Float = 0.8f,
    val audioEnabled: Boolean = false,
    /** 视频壁纸播放速度；0 = 暂停。 */
    @ColumnInfo(defaultValue = "1.0")
    val videoSpeed: Float = 1f,
    val disabled: Boolean = false,
    val launchAtLogin: Boolean = false,
    val screensaverWallpaper: Boolean = true,
    val allSpacesShared: Boolean = false,
    val trayIconStyle: String = "COLOR",
    val importDir: String? = null,
)

/** 「我的 - 色彩」列表；[position] 越小越靠前。 */
@Entity(tableName = "saved_colors")
internal data class SavedColorEntity(
    @PrimaryKey val position: Int,
    val fill: String,
)

/**
 * 图库元数据。
 *
 * 图片、视频本体与缩略图仍然是磁盘文件（[path] / [thumbnail] 指向它们），
 * 数据库只保存条目信息与顺序。
 */
@Entity(tableName = "wallpapers")
internal data class WallpaperEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val path: String,
    val addedAt: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnail: String? = null,
    val durationSeconds: Int? = null,
)

internal const val SINGLETON_ID = 0

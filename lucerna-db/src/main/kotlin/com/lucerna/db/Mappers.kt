package com.lucerna.db

import com.lucerna.core.model.ColorFill
import com.lucerna.core.model.Settings
import com.lucerna.core.model.TrayIconStyle
import com.lucerna.core.model.WallpaperItem
import com.lucerna.core.model.WallpaperSource
import com.lucerna.core.model.WallpaperType
import kotlinx.serialization.json.Json

/** 数据库里存 ColorFill 用的 JSON：字段少、扩展时忽略未知字段即可。 */
internal val dbJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

internal fun ColorFill.toJson(): String = dbJson.encodeToString(ColorFill.serializer(), this)

internal fun String.toColorFillOrNull(): ColorFill? =
    runCatching { dbJson.decodeFromString(ColorFill.serializer(), this) }.getOrNull()

internal fun Settings.toEntity(): SettingsEntity = SettingsEntity(
    version = version,
    currentWallpaperId = currentWallpaperId,
    currentSource = currentSource.name,
    colorFill = colorFill.toJson(),
    volume = volume,
    audioEnabled = audioEnabled,
    videoSpeed = videoSpeed,
    disabled = disabled,
    launchAtLogin = launchAtLogin,
    screensaverWallpaper = screensaverWallpaper,
    allSpacesShared = allSpacesShared,
    trayIconStyle = trayIconStyle.name,
    importDir = importDir,
)

internal fun SettingsEntity.toModel(savedColors: List<ColorFill>): Settings = Settings(
    version = version,
    currentWallpaperId = currentWallpaperId,
    currentSource = enumValueOrDefault(currentSource, WallpaperSource.LIBRARY),
    colorFill = colorFill.toColorFillOrNull() ?: ColorFill.default(),
    savedColors = savedColors,
    volume = volume,
    audioEnabled = audioEnabled,
    videoSpeed = videoSpeed,
    disabled = disabled,
    launchAtLogin = launchAtLogin,
    screensaverWallpaper = screensaverWallpaper,
    allSpacesShared = allSpacesShared,
    trayIconStyle = enumValueOrDefault(trayIconStyle, TrayIconStyle.COLOR),
    importDir = importDir,
).normalized()

internal fun WallpaperItem.toEntity(): WallpaperEntity = WallpaperEntity(
    id = id,
    name = name,
    type = type.name,
    path = path,
    addedAt = addedAt,
    width = width,
    height = height,
    thumbnail = thumbnail,
    durationSeconds = durationSeconds,
)

internal fun WallpaperEntity.toModel(): WallpaperItem = WallpaperItem(
    id = id,
    name = name,
    type = enumValueOrDefault(type, WallpaperType.IMAGE),
    path = path,
    addedAt = addedAt,
    width = width,
    height = height,
    thumbnail = thumbnail,
    durationSeconds = durationSeconds,
)

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(default)

package com.lucerna.core.paths

import com.lucerna.core.platform.DesktopOs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Lucerna 的数据目录布局：
 *
 * ```
 * <root>/settings.json
 * <root>/library.json
 * <root>/media/        导入后的原文件副本
 * <root>/thumbnails/   图片缩略图
 * <root>/renders/      色彩壁纸渲染出的 PNG
 * ```
 */
class AppPaths(val root: Path) {

    val settingsFile: Path = root.resolve("settings.json")
    val libraryFile: Path = root.resolve("library.json")
    val mediaDir: Path = root.resolve("media")
    val thumbnailDir: Path = root.resolve("thumbnails")
    val renderDir: Path = root.resolve("renders")

    fun ensure() {
        Files.createDirectories(root)
        Files.createDirectories(mediaDir)
        Files.createDirectories(thumbnailDir)
        Files.createDirectories(renderDir)
    }

    companion object {
        /** 按平台约定取默认数据目录；LUCERNA_DATA_DIR 可覆盖，便于冒烟测试。 */
        fun system(): AppPaths {
            val override = System.getenv("LUCERNA_DATA_DIR")
            if (!override.isNullOrBlank()) return AppPaths(Paths.get(override))
            val home = Paths.get(System.getProperty("user.home"))
            val root = when (DesktopOs.current) {
                DesktopOs.MACOS -> home.resolve("Library/Application Support/Lucerna")
                DesktopOs.WINDOWS -> {
                    val appData = System.getenv("APPDATA")
                    if (appData.isNullOrBlank()) home.resolve("AppData/Roaming/Lucerna")
                    else Paths.get(appData, "Lucerna")
                }
                DesktopOs.LINUX -> {
                    val xdg = System.getenv("XDG_DATA_HOME")
                    if (xdg.isNullOrBlank()) home.resolve(".local/share/lucerna")
                    else Paths.get(xdg, "lucerna")
                }
                DesktopOs.UNKNOWN -> home.resolve(".lucerna")
            }
            return AppPaths(root)
        }

        fun at(root: java.io.File): AppPaths = AppPaths(root.toPath())
    }
}

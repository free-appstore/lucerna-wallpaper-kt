package com.lucerna.core.files

import com.lucerna.core.media.MediaFormats
import com.lucerna.core.model.WallpaperType
import java.io.File

/** 导入页里的筛选条件。 */
enum class ImportFilter(val label: String) {
    ALL("全部"),
    IMAGE("图片"),
    VIDEO("视频"),
}

/** 导入页文件列表的一行。 */
data class BrowserEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val type: WallpaperType?,
) {
    val file: File get() = File(path)
}

/** 纯文件系统操作，方便在后台线程调用与单元测试。 */
object FileBrowser {

    private const val ENTRY_LIMIT = 3000

    /** 列出目录内容：文件夹在前，文件按名称排序。 */
    fun list(dir: File, filter: ImportFilter, limit: Int = ENTRY_LIMIT): List<BrowserEntry> {
        val children = dir.listFiles() ?: return emptyList()
        val directories = ArrayList<BrowserEntry>()
        val files = ArrayList<BrowserEntry>()
        children.asSequence()
            .filterNot { it.isHidden }
            .sortedBy { it.name.lowercase() }
            .forEach { child ->
                if (child.isDirectory) {
                    directories += BrowserEntry(child.name, child.absolutePath, true, 0L, null)
                } else {
                    val type = MediaFormats.typeOf(child) ?: return@forEach
                    val matches = when (filter) {
                        ImportFilter.ALL -> true
                        ImportFilter.IMAGE -> type == WallpaperType.IMAGE
                        ImportFilter.VIDEO -> type == WallpaperType.VIDEO
                    }
                    if (matches) {
                        files += BrowserEntry(child.name, child.absolutePath, false, child.length(), type)
                    }
                }
            }
        return (directories + files).take(limit)
    }

    /** 常用位置，只返回真实存在的目录。 */
    fun roots(): List<Pair<String, File>> {
        val home = File(System.getProperty("user.home"))
        val candidates = listOf(
            "主目录" to home,
            "图片" to File(home, "Pictures"),
            "影片" to File(home, "Movies"),
            "下载" to File(home, "Downloads"),
            "桌面" to File(home, "Desktop"),
        )
        val roots = candidates.filter { it.second.isDirectory }.toMutableList()
        if (roots.none { it.second == home }) roots.add(0, "主目录" to home)
        return roots
    }

    /** 上一级目录，已到根目录时返回自身。 */
    fun parentOf(dir: File): File = dir.parentFile ?: dir

    /** 人类可读的文件大小。 */
    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (value >= 100) "${value.toInt()} ${units[index]}"
        else String.format(java.util.Locale.US, "%.1f %s", value, units[index])
    }
}

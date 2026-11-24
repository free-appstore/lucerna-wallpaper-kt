package com.lucerna.core.platform

/** 把一张图片设为系统桌面壁纸。 */
interface WallpaperBackend {
    val name: String

    /** 成功返回 true。路径必须是绝对路径。 */
    fun applyImage(path: String): Boolean
}

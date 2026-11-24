package com.lucerna.core.platform

/** 当前桌面操作系统。 */
enum class DesktopOs {
    MACOS,
    WINDOWS,
    LINUX,
    UNKNOWN;

    companion object {
        val current: DesktopOs by lazy {
            val name = System.getProperty("os.name").orEmpty().lowercase()
            when {
                name.contains("mac") -> MACOS
                name.contains("win") -> WINDOWS
                name.contains("linux") -> LINUX
                else -> UNKNOWN
            }
        }
    }
}

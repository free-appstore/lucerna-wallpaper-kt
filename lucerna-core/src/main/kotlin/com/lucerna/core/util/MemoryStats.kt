package com.lucerna.core.util

/** JVM 内存占用，用于面板标题下的副标题。 */
object MemoryStats {
    fun usedMegabytes(): Long {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        return used / (1024 * 1024)
    }
}

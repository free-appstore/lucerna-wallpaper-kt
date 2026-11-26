package com.lucerna.app

import com.lucerna.core.media.Importer
import com.lucerna.core.media.MediaFormats
import com.lucerna.core.paths.AppPaths
import com.lucerna.db.LucernaDb
import java.io.File

/** `--seed-dir` 冒烟辅助：把目录里的图片/视频导入当前数据目录后退出。 */
object SeedDemo {

    fun run(directory: String): Int {
        val paths = AppPaths.system().also { it.ensure() }
        val dir = File(directory)
        if (!dir.isDirectory) {
            println("[Lucerna] seed dir not found: $directory")
            return 0
        }
        val files = dir.listFiles()
            ?.filter { it.isFile && MediaFormats.isSupported(it) }
            ?.sortedBy { it.name }
            .orEmpty()
        val added = LucernaDb.open(paths).use { database ->
            Importer(paths, database.libraryStore).importFiles(files)
        }
        println("[Lucerna] seeded ${added.size} item(s) into ${paths.root}")
        return added.size
    }
}

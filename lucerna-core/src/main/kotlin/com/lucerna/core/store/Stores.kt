package com.lucerna.core.store

import com.lucerna.core.model.LibraryData
import com.lucerna.core.model.Settings
import com.lucerna.core.model.WallpaperItem

/**
 * 设置存储。
 *
 * 具体实现（Room）在 `lucerna-db` 模块里；core 只定义接口，避免反向依赖。
 * 现有实现是同步的，调用方在界面线程上读写小数据，和以前直接读写 JSON 一致。
 */
interface SettingsStore {

    fun load(): Settings

    fun save(settings: Settings)
}

/** 图库元数据存储；图片/视频文件本身仍由 [com.lucerna.core.paths.AppPaths] 管理。 */
interface LibraryStore {

    fun load(): LibraryData

    fun save(data: LibraryData)

    fun add(items: List<WallpaperItem>): LibraryData

    fun remove(id: String): LibraryData
}

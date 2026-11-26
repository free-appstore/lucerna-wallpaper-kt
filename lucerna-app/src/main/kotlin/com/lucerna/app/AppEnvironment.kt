package com.lucerna.app

import com.lucerna.app.platform.PlatformWallpapers
import com.lucerna.core.media.Importer
import com.lucerna.core.paths.AppPaths
import com.lucerna.core.platform.WallpaperBackend
import com.lucerna.db.LucernaDb
import com.lucerna.ui.state.LucernaState
import com.lucerna.ui.state.PreviewRequest

/** 应用运行期依赖：路径、存储与平台壁纸。 */
class AppEnvironment {

    val paths: AppPaths = AppPaths.system().also { it.ensure() }
    /** 所有持久化数据（设置 / 色彩 / 图库元数据）都在这个数据库里。 */
    val database = LucernaDb.open(paths)
    val settingsStore = database.settingsStore
    val libraryStore = database.libraryStore
    val backend: WallpaperBackend = PlatformWallpapers.create()
    /** 桌面端导入后预先生成视频封面：之后「设为壁纸」不用再等抽帧。 */
    val importer = Importer(paths, libraryStore, prewarmVideoPosters = true)

    var tray: TrayController? = null
    var quitAction: (() -> Unit)? = null

    val controller = AppController(
        paths = paths,
        settingsStore = settingsStore,
        libraryStore = libraryStore,
        backend = backend,
        onQuit = { quitAction?.invoke() },
        onTrayIconStyle = { style -> tray?.setStyle(style) },
    )

    init {
        runCatching { controller.restoreOnLaunch() }
    }

    fun createState(onPreview: (PreviewRequest) -> Unit): LucernaState =
        LucernaState(
            paths = paths,
            settingsStore = settingsStore,
            libraryStore = libraryStore,
            actions = controller,
            importer = importer,
            onPreviewRequest = onPreview,
        )

    fun shutdown() {
        controller.shutdown()
        tray?.remove()
        database.close()
    }
}

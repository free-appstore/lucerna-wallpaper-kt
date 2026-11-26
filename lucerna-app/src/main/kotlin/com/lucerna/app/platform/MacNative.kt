package com.lucerna.app.platform

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.Component
import java.awt.Window
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 通过 JNA 直接调用 AppKit，避免依赖 AppleScript 的自动化授权弹窗。
 *
 * 只用到 objc_msgSend 的对象参数与 NSInteger 参数，不处理结构体返回值。
 */
internal object MacNative {

    /** 「显示菜单栏背景」读一次缓存两秒。 */
    private const val MENU_BAR_SETTING_TTL_MS = 2000L

    private val libObjc: NativeLibrary by lazy { NativeLibrary.getInstance("/usr/lib/libobjc.A.dylib") }
    private val msgSend by lazy { libObjc.getFunction("objc_msgSend") }
    private val getClassFn by lazy { libObjc.getFunction("objc_getClass") }
    private val selFn by lazy { libObjc.getFunction("sel_registerName") }
    private val objectClassNameFn by lazy { libObjc.getFunction("object_getClassName") }

    private fun cls(name: String): Pointer = getClassFn.invokePointer(arrayOf(name))

    private fun sel(name: String): Pointer = selFn.invokePointer(arrayOf(name))

    private fun send(receiver: Pointer, selector: String, vararg args: Any?): Pointer {
        val all = arrayOfNulls<Any?>(2 + args.size)
        all[0] = receiver
        all[1] = sel(selector)
        args.forEachIndexed { index, arg -> all[index + 2] = arg }
        return msgSend.invokePointer(all)
    }

    private fun sendLong(receiver: Pointer, selector: String): Long =
        msgSend.invokeLong(arrayOf(receiver, sel(selector)))

    /** 调用返回 void 的 selector（JNA 的 invokePointer 在 void 上会返回 null）。 */
    private fun sendVoid(receiver: Pointer, selector: String, vararg args: Any?) {
        val all = arrayOfNulls<Any?>(2 + args.size)
        all[0] = receiver
        all[1] = sel(selector)
        args.forEachIndexed { index, arg -> all[index + 2] = arg }
        msgSend.invokeVoid(all)
    }

    private fun nsString(value: String): Pointer = send(cls("NSString"), "stringWithUTF8String:", value)

    private fun nsUrl(path: String): Pointer = send(cls("NSURL"), "fileURLWithPath:", nsString(path))

    /** 把图片设到所有屏幕。 */
    fun applyDesktopImage(path: String): Boolean {
        val count = screenCount()
        if (count <= 0) return false
        for (index in 0 until count) {
            if (!applyDesktopImageToScreen(index, path)) return false
        }
        return true
    }

    private fun sendScreen(
        workspace: Pointer,
        url: Pointer,
        screens: Pointer,
        options: Pointer,
        index: Long,
    ): Pointer = send(
        workspace,
        "setDesktopImageURL:forScreen:options:error:",
        url,
        send(screens, "objectAtIndex:", index),
        options,
        null,
    )

    /** 把图片设到指定屏幕（冒烟测试逐屏恢复原壁纸用）。 */
    fun applyDesktopImageToScreen(index: Int, path: String): Boolean = runCatching {
        val workspace = send(cls("NSWorkspace"), "sharedWorkspace")
        val screens = send(cls("NSScreen"), "screens")
        if (index >= sendLong(screens, "count")) return false
        val options = send(cls("NSMutableDictionary"), "dictionary")
        Pointer.nativeValue(
            sendScreen(workspace, nsUrl(path), screens, options, index.toLong()),
        ) != 0L
    }.getOrElse { false }

    /** 当前屏幕数量。 */
    fun screenCount(): Int = runCatching {
        val screens = send(cls("NSScreen"), "screens")
        sendLong(screens, "count").toInt()
    }.getOrElse { 0 }

    /** 读取第 index 块屏幕当前的桌面图片路径，用于冒烟测试回读。 */
    fun desktopImagePath(index: Int): String? = runCatching {
        val workspace = send(cls("NSWorkspace"), "sharedWorkspace")
        val screens = send(cls("NSScreen"), "screens")
        val count = sendLong(screens, "count")
        if (index >= count) return null
        val screen = send(screens, "objectAtIndex:", index)
        val url = send(workspace, "desktopImageURLForScreen:", screen)
        if (Pointer.nativeValue(url) == 0L) return null
        val pathString = send(url, "path")
        if (Pointer.nativeValue(pathString) == 0L) return null
        // NSString 是对象，还要再取一次 UTF8String 才能拿到 C 字符串
        val utf8 = send(pathString, "UTF8String")
        if (Pointer.nativeValue(utf8) == 0L) return null
        utf8.getString(0)
    }.getOrNull()

    // --- 桌面壁纸层（把窗口压到系统壁纸之上、桌面图标之下） ---

    private val coreGraphics by lazy {
        NativeLibrary.getInstance("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
    }
    private val windowLevelByKey by lazy { coreGraphics.getFunction("CGWindowLevelForKey") }

    /** kCGDesktopWindowLevelKey = 2，kCGDesktopIconWindowLevelKey = 18。 */
    private const val DESKTOP_LEVEL_KEY = 2

    /** 桌面图标所在的窗口层级（比它低就在图标下面）。 */
    const val DESKTOP_ICON_LEVEL_KEY = 18

    private fun windowLevel(key: Int): Int = windowLevelByKey.invokeInt(arrayOf<Any?>(key))

    /** 桌面图标层级；壁纸层的层级必须比它小，才会被压在图标下面。 */
    fun desktopIconLevel(): Int = try {
        windowLevel(DESKTOP_ICON_LEVEL_KEY)
    } catch (error: Throwable) {
        Int.MAX_VALUE
    }

    /** 壁纸层的层级：系统壁纸之上（+1）、桌面图标之下。 */
    fun desktopLayerLevel(): Int = try {
        windowLevel(DESKTOP_LEVEL_KEY) + 1
    } catch (error: Throwable) {
        Int.MAX_VALUE
    }

    /**
     * 把 AWT 窗口调成「桌面壁纸层」：
     * 层级在系统壁纸之上、桌面图标之下；不接收鼠标事件（点击穿透给系统）；
     * 出现在所有 Space 里、切桌面不动、不进窗口切换器；无阴影、不抢焦点、失去激活也不隐藏。
     *
     * AppKit 只允许在主线程（"AppKit Thread"）上改窗口，所以这里会把工作丢到那个线程再等结果。
     * 返回 NSWindow 指针，失败返回 null（调用方会退回系统壁纸）。
     */
    fun tuneAsDesktopLayer(window: Window, level: Int = desktopLayerLevel()): Long? {
        val pointer = runOnAppKitThread { applyDesktopLayerAttributes(window, level) }
        if (pointer == null) println("[Lucerna] tune: window attributes not applied")
        return pointer
    }

    /**
     * 把 AWT 托盘图标的 `NSImage` 标记成 template。
     *
     * macOS 的 template 图标只使用 alpha，系统会按菜单栏前景色自动渲染成白色或黑色，
     * 菜单栏跟随壁纸/深浅色变化时也会自动切换。AWT 没有公开这个开关，只能从
     * `NSApp.windows` 里找到 `NSStatusBarWindow`，再给 `AWTTrayIconView` 的图片设置 template。
     *
     * 返回 false 表示没找到状态栏视图（换 JDK / 系统时调用方回退到普通图标）。
     */
    fun setTrayIconTemplate(template: Boolean): Boolean = runCatching {
        val app = send(cls("NSApplication"), "sharedApplication")
        val windows = send(app, "windows")
        if (Pointer.nativeValue(windows) == 0L) return@runCatching false
        val count = sendLong(windows, "count")
        var applied = false
        for (i in 0 until count) {
            val window = msgSend.invokePointer(arrayOf(windows, sel("objectAtIndex:"), i))
            if (Pointer.nativeValue(window) == 0L) continue
            val windowClass = objectClassNameFn.invokePointer(arrayOf(window))?.getString(0).orEmpty()
            if (!windowClass.contains("StatusBarWindow")) continue
            val content = send(window, "contentView")
            applied = applyTemplateToView(content, template, 0) || applied
        }
        applied
    }.getOrDefault(false)

    private fun applyTemplateToView(view: Pointer, template: Boolean, depth: Int): Boolean {
        if (Pointer.nativeValue(view) == 0L || depth > 4) return false
        var applied = false
        val viewClass = objectClassNameFn.invokePointer(arrayOf(view))?.getString(0).orEmpty()
        if (viewClass.contains("TrayIconView")) {
            val image = send(view, "image")
            if (Pointer.nativeValue(image) != 0L) {
                msgSend.invokeVoid(
                    arrayOf(image, sel("setTemplate:"), if (template) 1.toByte() else 0.toByte()),
                )
                applied = true
            }
        }
        val subviews = send(view, "subviews")
        if (Pointer.nativeValue(subviews) != 0L) {
            val count = sendLong(subviews, "count")
            for (i in 0 until count) {
                val sub = msgSend.invokePointer(arrayOf(subviews, sel("objectAtIndex:"), i))
                applied = applyTemplateToView(sub, template, depth + 1) || applied
            }
        }
        return applied
    }

    /**
     * 「系统设置 → 菜单栏 → 显示菜单栏背景」是否打开（`SLSMenuBarUseBlurredAppearance`）。
     *
     * 关闭时菜单栏是透明的，直接透出壁纸层的新图，不需要让开那一条给系统采样。
     * 读一次后缓存两秒，连续点壁纸时不用每次都起一个进程。
     */
    fun menuBarBackgroundEnabled(): Boolean {
        val now = System.currentTimeMillis()
        if (now - menuBarBackgroundCheckedAt < MENU_BAR_SETTING_TTL_MS) return cachedMenuBarBackground
        val enabled = runCatching {
            val process = ProcessBuilder("/usr/bin/defaults", "read", "-g", "SLSMenuBarUseBlurredAppearance")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor() == 0 && text in setOf("1", "true", "TRUE", "YES", "yes")
        }.getOrElse { false }
        cachedMenuBarBackground = enabled
        menuBarBackgroundCheckedAt = now
        return enabled
    }

    @Volatile
    private var cachedMenuBarBackground = false

    @Volatile
    private var menuBarBackgroundCheckedAt = 0L

    /** 在 AppKit 主线程上执行 [work] 并拿到返回值；不在主线程时投递过去等结果。 */
    private fun <T : Any> runOnAppKitThread(work: () -> T?): T? {
        if (isAppKitMainThread()) return work()

        val result = AtomicReference<T?>()
        val latch = CountDownLatch(1)
        val posted = postToAppKitThread {
            try {
                result.set(work())
            } finally {
                latch.countDown()
            }
        }
        if (!posted) {
            println("[Lucerna] cannot dispatch to AppKit thread")
            return null
        }
        if (!latch.await(3, TimeUnit.SECONDS)) {
            println("[Lucerna] AppKit thread did not respond")
            return null
        }
        return result.get()
    }

    /** 投递一个任务到 AppKit 主线程（不等待结果）。 */
    private fun postToAppKitThread(block: Runnable): Boolean = runCatching {
        val toolkit = Class.forName("sun.lwawt.macosx.LWCToolkit")
        val method = toolkit.getDeclaredMethod(
            "performOnMainThreadAfterDelay",
            Runnable::class.java,
            Long::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(null, block, 0L)
        true
    }.getOrElse { false }

    /** 真正改 NSWindow 的属性；只能在 AppKit 主线程上调用。 */
    private fun applyDesktopLayerAttributes(window: Window, level: Int): Long? = runCatching {
        val nsWindow = nsWindowPointer(window) ?: run {
            println("[Lucerna] tune: cannot find NSWindow for ${window.bounds}")
            return null
        }
        sendVoid(nsWindow, "setLevel:", level.toLong())
        // canJoinAllSpaces(1) | stationary(16) | ignoresCycle(64)
        // fullScreenAuxiliary(256)：让桌面层也进入全屏 Space 的过渡快照。
        // 否则从全屏 app 打开调度中心 / 切回桌面时，动画用的是不含壁纸层的系统快照，
        // 动画结束才看到视频壁纸。窗口层级仍远低于全屏 app，不会盖住全屏窗口。
        sendVoid(nsWindow, "setCollectionBehavior:", 1L or 16L or 64L or 256L)
        sendVoid(nsWindow, "setIgnoresMouseEvents:", 1L)
        sendVoid(nsWindow, "setHasShadow:", 0L)
        sendVoid(nsWindow, "setHidesOnDeactivate:", 0L)
        sendVoid(nsWindow, "setMovable:", 0L)
        sendVoid(nsWindow, "setOpaque:", 1L)
        // 只往下压，不往前提：壁纸层永远待在同层级的最下面，绝不挡住别人
        sendVoid(nsWindow, "orderBack:")
        Pointer.nativeValue(nsWindow)
    }.onFailure { println("[Lucerna] tune failed: $it") }.getOrNull()

    /** 当前线程是不是 AppKit 主线程（只有它能改 NSWindow）。 */
    private fun isAppKitMainThread(): Boolean = runCatching {
        val thread = send(cls("NSThread"), "currentThread")
        Pointer.nativeValue(thread) != 0L &&
            msgSend.invokeInt(arrayOf<Any?>(thread, sel("isMainThread"))) != 0
    }.getOrElse { Thread.currentThread().name.startsWith("AppKit") }

    /**
     * 从 AWT 窗口的 CPlatformWindow 里取出 NSWindow 指针。
     *
     * 需要三个 --add-opens：java.awt（peer 字段）、sun.lwawt（getPlatformWindow）、
     * sun.lwawt.macosx（ptr 字段、performOnMainThreadAfterDelay）。
     */
    private fun nsWindowPointer(window: Window): Pointer? = runCatching {
        val peerField = Component::class.java.getDeclaredField("peer")
        if (!peerField.trySetAccessible()) return null
        val peer = peerField.get(window) ?: return null
        val platformWindow = peer.javaClass.getMethod("getPlatformWindow").invoke(peer) ?: return null
        val field = platformWindow.javaClass.superclass.getDeclaredField("ptr")
        if (!field.trySetAccessible()) return null
        val value = field.getLong(platformWindow)
        if (value == 0L) null else Pointer(value)
    }.getOrNull()

    /** 给 AVPlayer 之类的原生扩展用：在 AppKit 主线程执行。 */
    internal fun <T : Any> onAppKitThread(work: () -> T?): T? = runOnAppKitThread(work)

    /** 当前 AWT 窗口的 NSView contentView。必须在 AppKit 主线程调用。 */
    internal fun contentViewPointer(window: Window): Pointer? = runOnAppKitThread {
        nsWindowPointer(window)?.let { nsWindow ->
            val view = send(nsWindow, "contentView")
            if (Pointer.nativeValue(view) == 0L) null else view
        }
    }

    /** 应用窗口激活，让弹出的面板能立刻接收键盘输入。 */
    fun activateApp() {
        runCatching {
            val app = send(cls("NSApplication"), "sharedApplication")
            send(app, "activateIgnoringOtherApps:", 1L)
        }
    }
}

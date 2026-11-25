package com.lucerna.app.platform

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.Component
import java.awt.Window
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 通过 JNA 直接调用 AppKit，避免依赖 AppleScript 的自动化授权弹窗。
 *
 * 只用到 objc_msgSend 的对象参数与 NSInteger 参数，不处理结构体返回值。
 */
internal object MacNative {

    private val libObjc: NativeLibrary by lazy { NativeLibrary.getInstance("/usr/lib/libobjc.A.dylib") }
    private val msgSend by lazy { libObjc.getFunction("objc_msgSend") }
    private val getClassFn by lazy { libObjc.getFunction("objc_getClass") }
    private val selFn by lazy { libObjc.getFunction("sel_registerName") }

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
        val results = applyDesktopImagePerScreen(path)
        return results.isNotEmpty() && results.all { it }
    }

    /** 逐块屏幕设置桌面图片，返回每块屏幕是否成功（冒烟测试用来逐屏校验）。 */
    fun applyDesktopImagePerScreen(path: String): List<Boolean> =
        (0 until screenCount()).map { applyDesktopImageToScreen(it, path) }

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
        val work = { applyDesktopLayerAttributes(window, level) }
        if (isAppKitMainThread()) return work()

        val result = AtomicLong(0L)
        val latch = CountDownLatch(1)
        val posted = runCatching {
            val toolkit = Class.forName("sun.lwawt.macosx.LWCToolkit")
            val method = toolkit.getDeclaredMethod(
                "performOnMainThreadAfterDelay",
                Runnable::class.java,
                Long::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(
                null,
                Runnable {
                    try {
                        result.set(work() ?: 0L)
                    } finally {
                        latch.countDown()
                    }
                },
                0L,
            )
            true
        }.getOrElse { false }
        if (!posted) {
            println("[Lucerna] tune: cannot dispatch to AppKit thread")
            return null
        }
        if (!latch.await(3, TimeUnit.SECONDS)) {
            println("[Lucerna] tune: AppKit thread did not respond")
            return null
        }
        val pointer = result.get()
        if (pointer == 0L) println("[Lucerna] tune: window attributes not applied")
        return pointer.takeIf { it != 0L }
    }

    /** 真正改 NSWindow 的属性；只能在 AppKit 主线程上调用。 */
    private fun applyDesktopLayerAttributes(window: Window, level: Int): Long? = runCatching {
        val nsWindow = nsWindowPointer(window) ?: run {
            println("[Lucerna] tune: cannot find NSWindow for ${window.bounds}")
            return null
        }
        sendVoid(nsWindow, "setLevel:", level.toLong())
        // canJoinAllSpaces(1) | stationary(16) | ignoresCycle(64)
        // 不加 fullScreenAuxiliary：加了之后 macOS 会把这一层放进全屏 Space，
        // 有可能被提到全屏 app 之上（壁纸盖住全屏的 IDE / 浏览器）。
        // 全屏 Space 里本来也看不到桌面，过渡动画漏出来的那几帧由「垫底的同款系统壁纸」兜住。
        sendVoid(nsWindow, "setCollectionBehavior:", 1L or 16L or 64L)
        sendVoid(nsWindow, "setIgnoresMouseEvents:", 1L)
        sendVoid(nsWindow, "setHasShadow:", 0L)
        sendVoid(nsWindow, "setHidesOnDeactivate:", 0L)
        sendVoid(nsWindow, "setMovable:", 0L)
        sendVoid(nsWindow, "setOpaque:", 1L)
        // 只往下压，不往前提：壁纸层永远待在同层级的最下面，绝不挡住别人
        sendVoid(nsWindow, "orderBack:")
        Pointer.nativeValue(nsWindow)
    }.onFailure { println("[Lucerna] tune failed: ${it}") }.getOrNull()

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

    /** 应用窗口激活，让弹出的面板能立刻接收键盘输入。 */
    fun activateApp() {
        runCatching {
            val app = send(cls("NSApplication"), "sharedApplication")
            send(app, "activateIgnoringOtherApps:", 1L)
        }
    }
}

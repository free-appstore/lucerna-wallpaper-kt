package com.lucerna.app.platform

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

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

    /** 应用窗口激活，让弹出的面板能立刻接收键盘输入。 */
    fun activateApp() {
        runCatching {
            val app = send(cls("NSApplication"), "sharedApplication")
            send(app, "activateIgnoringOtherApps:", 1L)
        }
    }
}

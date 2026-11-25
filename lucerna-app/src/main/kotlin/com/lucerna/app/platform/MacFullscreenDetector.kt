package com.lucerna.app.platform

import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import com.lucerna.core.platform.DesktopOs
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import kotlin.math.roundToInt

/**
 * 判断哪些屏幕正被「全屏 app」覆盖。
 *
 * macOS 的壁纸公开 API 只作用于当前 Space：被全屏 app 占着的屏幕，退出全屏后
 * 会回到它自己的 Space，所以要把每块屏幕都检查一遍，只要有一块处于全屏，
 * 调用方就改为同步壁纸库里的所有 Space（见 [MacWallpaperStore]）。
 *
 * 判定方式：CGWindowList 里普通窗口的 bounds 覆盖整块屏幕（含菜单栏区域）。
 * 本进程自己的窗口按 PID 排除，系统 UI（菜单栏、Dock、通知中心…）按可执行文件
 * 路径排除。私有接口不可用时返回空集合，调用方退回只改当前 Space 的安全方式。
 */
internal object MacFullscreenDetector {

    /** kCGWindowListOptionOnScreenOnly | kCGWindowListOptionExcludeDesktopElements */
    private const val WINDOW_LIST_OPTIONS = 17

    /** kCFPropertyListBinaryFormat_v1_0 */
    private const val BINARY_PLIST_FORMAT = 200

    /** 全屏窗口与屏幕边界之间允许的误差（点）。 */
    private const val TOLERANCE = 2

    /** 只认普通/浮动层级（全屏 app 是 0 层）；菜单栏 24、Dock 20 等更高的层级不算。 */
    private const val MAX_WINDOW_LAYER = 3

    /** 系统 UI 的可执行文件都装在这些目录下。 */
    private val SYSTEM_PATHS = listOf("/System/", "/usr/libexec/", "/usr/sbin/", "/sbin/")

    private val ownPid: Long = ProcessHandle.current().pid()

    private val coreGraphics by lazy {
        NativeLibrary.getInstance("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
    }
    private val coreFoundation by lazy {
        NativeLibrary.getInstance("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
    }

    private val copyWindowInfo by lazy { coreGraphics.getFunction("CGWindowListCopyWindowInfo") }
    private val createPlistData by lazy { coreFoundation.getFunction("CFPropertyListCreateData") }
    private val dataLength by lazy { coreFoundation.getFunction("CFDataGetLength") }
    private val dataBytes by lazy { coreFoundation.getFunction("CFDataGetBytePtr") }
    private val release by lazy { coreFoundation.getFunction("CFRelease") }

    /**
     * 被全屏 app 覆盖的屏幕（AWT 坐标，与 CG 窗口坐标一致）。
     *
     * 返回非空就说明有屏幕的 Space 无法通过 NSWorkspace 更新。
     */
    fun fullscreenScreens(): List<Rectangle> {
        if (DesktopOs.current != DesktopOs.MACOS) return emptyList()
        val screens = runCatching { screenBounds() }.getOrElse { emptyList() }
        if (screens.isEmpty()) return emptyList()
        val windows = runCatching { coveringWindows() }.getOrElse { emptyList() }
        if (windows.isEmpty()) return emptyList()
        return screens.filter { screen -> windows.any { covers(it, screen) } }
    }

    /** 所有屏幕的边界，按 AWT 的屏幕顺序排列，用于日志与冒烟测试。 */
    fun screenBounds(): List<Rectangle> = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .screenDevices
        .map { it.defaultConfiguration.bounds }

    /** 鼠标所在屏幕是否被全屏 app 覆盖（`--detect-fullscreen` 诊断用）。 */
    fun isPointerScreenFullscreen(): Boolean {
        val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return false
        return fullscreenScreens().any { it.contains(pointer) }
    }

    /** 覆盖整块屏幕的第三方窗口；已排除本进程与系统 UI。 */
    private fun coveringWindows(): List<Rectangle> {
        val windows = windowList()
        return windows.mapNotNull { window ->
            val pid = (window.objectForKey("kCGWindowOwnerPID") as? NSNumber)?.longValue()
                ?: return@mapNotNull null
            if (pid == ownPid) return@mapNotNull null
            val layer = (window.objectForKey("kCGWindowLayer") as? NSNumber)?.intValue()
                ?: return@mapNotNull null
            if (layer > MAX_WINDOW_LAYER) return@mapNotNull null
            val owner = (window.objectForKey("kCGWindowOwnerName") as? NSString)?.content.orEmpty()
            if (owner.startsWith("com.apple") || isSystemProcess(pid)) return@mapNotNull null
            val bounds = window.objectForKey("kCGWindowBounds") as? NSDictionary
                ?: return@mapNotNull null
            bounds.toRectangle() ?: return@mapNotNull null
        }
    }

    /** 用二进制 plist 中转：CFDictionary 的键值直接交给 dd-plist 解析，省掉一整套 C 调用。 */
    private fun windowList(listOptions: Int = WINDOW_LIST_OPTIONS): List<NSDictionary> {
        val list = copyWindowInfo.invokePointer(arrayOf<Any?>(listOptions, 0))
            ?: return emptyList()
        if (Pointer.nativeValue(list) == 0L) return emptyList()
        try {
            val data = createPlistData.invokePointer(
                arrayOf<Any?>(Pointer.NULL, list, BINARY_PLIST_FORMAT, 0L, Pointer.NULL),
            )
            if (data == null || Pointer.nativeValue(data) == 0L) return emptyList()
            try {
                val length = dataLength.invokeLong(arrayOf<Any?>(data)).toInt()
                if (length <= 0) return emptyList()
                val bytes = dataBytes.invokePointer(arrayOf<Any?>(data)) ?: return emptyList()
                val array = PropertyListParser.parse(bytes.getByteArray(0, length)) as? NSArray
                    ?: return emptyList()
                return (0 until array.count()).mapNotNull { array.objectAtIndex(it) as? NSDictionary }
            } finally {
                release.invokeVoid(arrayOf<Any?>(data))
            }
        } finally {
            release.invokeVoid(arrayOf<Any?>(list))
        }
    }

    /**
     * 本进程所有窗口的 (bounds, 层级)，冒烟测试用来确认桌面壁纸层的窗口真的压在
     * 系统壁纸之上、桌面图标之下。
     */
    fun ownWindows(): List<Pair<Rectangle, Int>> = runCatching {
        windowList(listOptions = 0).mapNotNull { window ->
            val pid = (window.objectForKey("kCGWindowOwnerPID") as? NSNumber)?.longValue()
                ?: return@mapNotNull null
            if (pid != ownPid) return@mapNotNull null
            val layer = (window.objectForKey("kCGWindowLayer") as? NSNumber)?.intValue()
                ?: return@mapNotNull null
            val bounds = (window.objectForKey("kCGWindowBounds") as? NSDictionary)?.toRectangle()
                ?: return@mapNotNull null
            bounds to layer
        }
    }.getOrElse { emptyList() }

    /** 系统进程（菜单栏、Dock、窗口服务…）的窗口会盖住整屏，但不是「全屏 app」。 */
    private fun isSystemProcess(pid: Long): Boolean = runCatching {
        val command = ProcessHandle.of(pid)
            .map { it.info().command().orElse("") }
            .orElse("")
        command.isNotEmpty() && SYSTEM_PATHS.any { command.startsWith(it) }
    }.getOrElse { false }

    private fun NSDictionary.toRectangle(): Rectangle? {
        val x = number("X") ?: return null
        val y = number("Y") ?: return null
        val width = number("Width") ?: return null
        val height = number("Height") ?: return null
        return Rectangle(x.roundToInt(), y.roundToInt(), width.roundToInt(), height.roundToInt())
    }

    private fun NSDictionary.number(key: String): Double? =
        (objectForKey(key) as? NSNumber)?.doubleValue()

    private fun covers(window: Rectangle, screen: Rectangle): Boolean =
        window.x <= screen.x + TOLERANCE &&
            window.y <= screen.y + TOLERANCE &&
            window.x + window.width >= screen.x + screen.width - TOLERANCE &&
            window.y + window.height >= screen.y + screen.height - TOLERANCE
}

package com.lucerna.ui.preview

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import javax.swing.JComponent

/**
 * macOS 触控板双指捏合缩放。
 *
 * Compose Desktop 没有捏合手势的公开 API，但 JDK 的 AWT 仍然保留着 Apple 的
 * `com.apple.eawt.event`（MagnificationListener）。这里用反射把它挂到窗口的
 * rootPane 上，需要 JVM 参数：
 * `--add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED`（见 app 模块的 build.gradle.kts）。
 *
 * 非 macOS、或 JDK 不带这套 API 时返回 null，调用方直接忽略即可。
 *
 * @param onMagnify 每次捏合的相对增量（例如 0.02 表示放大 2%）
 */
internal fun installPinchZoom(
    component: JComponent,
    onMagnify: (Float) -> Unit,
): AutoCloseable? {
    if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return null
    return runCatching {
        val utilities = Class.forName("com.apple.eawt.event.GestureUtilities")
        val gestureListener = Class.forName("com.apple.eawt.event.GestureListener")
        val magnificationListener = Class.forName("com.apple.eawt.event.MagnificationListener")
        val listener = Proxy.newProxyInstance(
            magnificationListener.classLoader,
            arrayOf(magnificationListener),
            MagnifyHandler(onMagnify),
        )
        utilities.getMethod("addGestureListenerTo", JComponent::class.java, gestureListener)
            .invoke(null, component, listener)
        val remove = utilities.getMethod("removeGestureListenerFrom", JComponent::class.java, gestureListener)
        AutoCloseable {
            runCatching { remove.invoke(null, component, listener) }
        }
    }.onFailure {
        println("[Lucerna] pinch zoom unavailable: ${it.message}")
    }.getOrNull()
}

/** 只关心 `magnify(MagnificationEvent)`，其余手势回调忽略。 */
private class MagnifyHandler(
    private val onMagnify: (Float) -> Unit,
) : InvocationHandler {

    override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
        if (method.name == "magnify") {
            val event = args?.firstOrNull()
            val magnification = event?.let { readMagnification(it) }
            if (magnification != null) onMagnify(magnification)
        }
        return null
    }

    private fun readMagnification(event: Any): Float? = runCatching {
        val getter = event.javaClass.getMethod("getMagnification")
        getter.isAccessible = true
        (getter.invoke(event) as? Double)?.toFloat()
    }.getOrNull()
}

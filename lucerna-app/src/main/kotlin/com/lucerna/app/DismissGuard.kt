package com.lucerna.app

/** 面板因点击外部关闭后，短时间内忽略托盘点击，避免「关了又开」。 */
class DismissGuard {
    private var hiddenAt = 0L

    fun markHidden() {
        hiddenAt = System.currentTimeMillis()
    }

    fun consumeRecent(windowMs: Long = 320L): Boolean {
        val recent = System.currentTimeMillis() - hiddenAt <= windowMs
        if (recent) hiddenAt = 0L
        return recent
    }
}

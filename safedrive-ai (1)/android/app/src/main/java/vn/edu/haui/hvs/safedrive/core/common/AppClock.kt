package vn.edu.haui.hvs.safedrive.core.common

/** Single time source for the app so deadlines/timestamps are testable without [Thread.sleep]. */
interface AppClock {
    fun nowMs(): Long
}

class SystemAppClock : AppClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

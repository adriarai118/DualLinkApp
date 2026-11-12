package com.duallink.downloader

/** Shared state between DownloadService (writer) and MainActivity (reader). */
object DlState {
    @Volatile var running = false
    @Volatile var message = "آماده"
    @Volatile var warning: String? = null
    @Volatile var engine: DualLinkEngine? = null
    @Volatile var savedTo: String? = null

    fun reset() {
        running = false
        message = "آماده"
        warning = null
        engine = null
        savedTo = null
    }
}

fun fmtBytes(b: Long): String {
    if (b < 0) return "?"
    val kb = 1024.0
    return when {
        b < kb -> "$b B"
        b < kb * kb -> String.format("%.1f KB", b / kb)
        b < kb * kb * kb -> String.format("%.1f MB", b / (kb * kb))
        else -> String.format("%.2f GB", b / (kb * kb * kb))
    }
}

fun fmtSpeed(bps: Double): String = fmtBytes(bps.toLong()) + "/s"

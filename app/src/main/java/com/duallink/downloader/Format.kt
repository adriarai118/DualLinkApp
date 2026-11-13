package com.duallink.downloader

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

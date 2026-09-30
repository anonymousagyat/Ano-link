package com.tailconnect.app

import android.graphics.Bitmap

data class PcProcessItem(
    val pid: Int,
    val name: String,
    val exe: String,
    var cpu: Float,
    var mem: Float,
    val type: String,
    val icon: String? = null,
    var iconBitmap: Bitmap? = null,
    val isHeader: Boolean = false,
    val headerTitle: String = "",
    val isAppSection: Boolean = true
) {
    companion object {
        fun createHeader(title: String, isAppSection: Boolean): PcProcessItem {
            return PcProcessItem(
                pid = -1,
                name = title,
                exe = "",
                cpu = 0f,
                mem = 0f,
                type = "header",
                isHeader = true,
                headerTitle = title,
                isAppSection = isAppSection
            )
        }
    }
}

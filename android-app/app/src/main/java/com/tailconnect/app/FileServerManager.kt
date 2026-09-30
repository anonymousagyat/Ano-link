package com.tailconnect.app

import android.os.Build
import android.os.Environment
import java.io.File

data class FileItemDto(
    val name: String,
    val path: String,
    val is_dir: Boolean,
    val size: Long,
    val modified: Long
)

data class DirectoryListResult(
    val path: String,
    val files: List<FileItemDto>,
    val allFilesAccess: Boolean
)

object FileServerManager {

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun listDirectory(requestedPath: String?): DirectoryListResult {
        val targetPath = if (requestedPath.isNullOrBlank()) {
            "/storage/emulated/0"
        } else {
            requestedPath
        }

        val allFilesAccess = hasAllFilesAccess()

        val dir = File(targetPath)
        if (!dir.exists() || !dir.isDirectory) {
            return DirectoryListResult(targetPath, emptyList(), allFilesAccess)
        }

        val files = dir.listFiles() ?: return DirectoryListResult(targetPath, emptyList(), allFilesAccess)

        val list = files.map { file ->
            FileItemDto(
                name = file.name,
                path = file.absolutePath,
                is_dir = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                modified = file.lastModified()
            )
        }.sortedWith(compareBy({ !it.is_dir }, { it.name.lowercase() }))

        return DirectoryListResult(targetPath, list, allFilesAccess)
    }
}

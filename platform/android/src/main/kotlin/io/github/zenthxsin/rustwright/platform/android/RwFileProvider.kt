package io.github.zenthxsin.rustwright.platform.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * 只读分享应用私有文件的最小 ContentProvider（不引入 androidx.core 的 FileProvider）。
 * Uri 形如 content://<applicationId>.files/<相对 filesDir 或 cacheDir 的路径>。
 */
class RwFileProvider : ContentProvider() {

    override fun onCreate() = true

    private fun resolve(uri: Uri): File {
        val ctx = context!!
        val segments = uri.pathSegments
        require(segments.size >= 2) { "非法 uri：$uri" }
        val root = when (segments[0]) {
            "files" -> ctx.filesDir
            "cache" -> ctx.cacheDir
            else -> throw IllegalArgumentException("非法根：${segments[0]}")
        }
        val file = File(root, segments.drop(1).joinToString("/")).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath())) { "越界路径：$uri" }
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolve(uri)
        // 只有相机输出目录可写（拍照时系统相机往这里写），其余一律只读
        val camera = File(context!!.cacheDir, "camera").canonicalPath
        val writable = mode.contains('w') && file.toPath().startsWith(File(camera).toPath())
        return ParcelFileDescriptor.open(file, if (writable) ParcelFileDescriptor.parseMode(mode) else ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = resolve(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(file.name, file.length())) }
    }

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "zip" -> "application/zip"
        "xml" -> "application/xml"
        "ini", "txt", "md" -> "text/plain"
        else -> "application/octet-stream"
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        fun uriFor(ctx: Context, file: File): Uri {
            val path = file.canonicalPath
            val (root, base) = when {
                File(path).toPath().startsWith(ctx.filesDir.canonicalFile.toPath()) -> "files" to ctx.filesDir.canonicalPath
                File(path).toPath().startsWith(ctx.cacheDir.canonicalFile.toPath()) -> "cache" to ctx.cacheDir.canonicalPath
                else -> throw IllegalArgumentException("只能分享应用私有目录内的文件：$path")
            }
            return Uri.Builder().scheme("content").authority("${ctx.packageName}.files")
                .appendPath(root).appendEncodedPath(path.removePrefix(base).trimStart('/')).build()
        }
    }
}

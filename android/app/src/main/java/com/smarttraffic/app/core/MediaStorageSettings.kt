package com.smarttraffic.app.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** User-selectable storage policy for camera captures and live MJPEG recordings. */
object MediaStorageSettings {
    private const val PREFS = "smart_traffic_media_storage"
    private const val SAVE_PHOTOS = "save_photos"
    private const val RECORD_LIVE = "record_live"
    private const val FOLDER_URI = "folder_uri"

    var savePhotos: Boolean = true
        private set
    var recordLive: Boolean = false
        private set
    var folderUri: String? = null
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        savePhotos = prefs.getBoolean(SAVE_PHOTOS, true)
        recordLive = prefs.getBoolean(RECORD_LIVE, false)
        folderUri = prefs.getString(FOLDER_URI, null)
    }

    fun setSavePhotos(context: Context, enabled: Boolean) {
        savePhotos = enabled
        persist(context)
    }

    fun setRecordLive(context: Context, enabled: Boolean) {
        recordLive = enabled
        persist(context)
    }

    fun setFolder(context: Context, uri: Uri?) {
        folderUri = uri?.toString()
        persist(context)
    }

    fun takePersistableFolderPermission(context: Context, uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        setFolder(context, uri)
    }

    suspend fun savePhoto(context: Context, bitmap: Bitmap): String? {
        val name = "SMART_TRAFFIC_${timestamp()}.jpg"
        return runCatching {
            val uri = folderUri?.let { saveToFolder(context, Uri.parse(it), name, "image/jpeg", bitmap) }
                ?: saveToMediaStore(context, name, "image/jpeg", bitmap)
            uri.toString()
        }.getOrNull()
    }

    fun createLiveRecording(context: Context): RecordingTarget? {
        val name = "SMART_TRAFFIC_${timestamp()}.mjpeg"
        return runCatching {
            val uri = folderUri?.let {
                val folder = DocumentFile.fromTreeUri(context, Uri.parse(it))
                    ?: error("Selected storage folder is unavailable")
                folder.createFile("video/x-motion-jpeg", name)?.uri
                    ?: error("Could not create recording file")
            } ?: createMediaStoreFile(context, name, "video/x-motion-jpeg")
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: error("Could not open recording output")
            RecordingTarget(uri, output, folderUri == null)
        }.getOrNull()
    }

    private fun saveToFolder(context: Context, tree: Uri, name: String, mime: String, bitmap: Bitmap): Uri {
        val folder = DocumentFile.fromTreeUri(context, tree) ?: error("Selected storage folder unavailable")
        val file = folder.createFile(mime, name) ?: error("Could not create image file")
        context.contentResolver.openOutputStream(file.uri, "w").use { stream ->
            requireNotNull(stream) { "Could not open image output" }
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "JPEG compression failed" }
        }
        return file.uri
    }

    private fun saveToMediaStore(context: Context, name: String, mime: String, bitmap: Bitmap): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val root = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: error("Pictures storage unavailable")
            val file = java.io.File(root, name)
            file.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "JPEG compression failed" }
            }
            return Uri.fromFile(file)
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SmartTraffic")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create image MediaStore entry")
        try {
            resolver.openOutputStream(uri, "w").use { stream ->
                requireNotNull(stream) { "Could not open image output" }
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "JPEG compression failed" }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private fun createMediaStoreFile(context: Context, name: String, mime: String): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: error("Movies storage unavailable")
            return Uri.fromFile(java.io.File(root, name))
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SmartTraffic")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create video MediaStore entry")
    }

    private fun persist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(SAVE_PHOTOS, savePhotos)
            .putBoolean(RECORD_LIVE, recordLive)
            .putString(FOLDER_URI, folderUri)
            .apply()
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    data class RecordingTarget(
        val uri: Uri,
        val output: java.io.OutputStream,
        val mediaStorePending: Boolean,
    ) {
        fun finish(context: Context) {
            output.flush()
            output.close()
            if (mediaStorePending && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
        }
    }
}

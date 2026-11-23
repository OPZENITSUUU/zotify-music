package com.music.bitchord.data.spotify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Stores user playlist covers outside the cache so they survive restarts and APK updates. */
object PlaylistArtworkStore {
    private const val DIRECTORY = "playlist-artwork"
    private const val MAX_BYTES = 8L * 1024L * 1024L
    private lateinit var root: File

    fun init(context: Context) {
        root = File(context.filesDir, DIRECTORY).apply { mkdirs() }
    }

    fun fileFor(playlistId: String): File {
        check(::root.isInitialized) { "PlaylistArtworkStore.init must be called first" }
        return File(root, "${stableKey(playlistId)}.jpg")
    }

    fun get(playlistId: String): File? = fileFor(playlistId).takeIf { it.isFile && it.length() in 1..MAX_BYTES }

    fun remove(playlistId: String): Boolean = fileFor(playlistId).delete()

    @Throws(IOException::class)
    fun save(context: Context, playlistId: String, uri: Uri): File {
        val destination = fileFor(playlistId)
        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to read selected image" }
            BitmapFactory.decodeStream(input) ?: error("Selected file is not a valid image")
        }
        val size = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - size) / 2
        val top = (bitmap.height - size) / 2
        val square = Bitmap.createBitmap(bitmap, left, top, size, size)
        val temporary = File(root, ".${destination.name}.tmp")
        try {
            temporary.outputStream().use { output ->
                check(square.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "Unable to encode image" }
            }
            check(temporary.length() in 1..MAX_BYTES) { "Image is too large" }
            if (!temporary.renameTo(destination)) error("Unable to save image")
            return destination
        } finally {
            if (square !== bitmap) square.recycle()
            bitmap.recycle()
            temporary.delete()
        }
    }

    fun stableKey(playlistId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(playlistId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

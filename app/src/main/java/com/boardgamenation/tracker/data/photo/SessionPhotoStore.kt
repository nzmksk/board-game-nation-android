package com.boardgamenation.tracker.data.photo

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.boardgamenation.tracker.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * App-private storage for the photo attached to a play.
 *
 * The photo picker hands back a uri the app may read for as long as the task that asked
 * for it lives, and no longer: it is a one-off grant on somebody else's file, and it
 * cannot be made persistable. Storing that uri on the play is therefore storing a
 * reference that stops resolving -- the picture is there while the screen is still open
 * and gone the next time the play is looked at.
 *
 * So the bytes are copied in at attach time and the play remembers the copy, the same
 * arrangement the collection's covers use: a local file, readable forever, with no
 * permission attached to it.
 */
@Singleton
class SessionPhotoStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher
) {

    private val directory: File get() = File(context.filesDir, PHOTO_DIR)

    /** Copies what [source] points at into app-private storage, returning its path. */
    suspend fun store(source: Uri): String? = withContext(io) {
        val target = File(directory.apply { mkdirs() }, "${UUID.randomUUID()}.${extensionOf(source)}")
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            target.absolutePath
        } catch (_: Exception) {
            // A picker can hand back a uri that is already unreadable -- a revoked grant,
            // a cloud item that will not download. Nothing is lost but the attachment.
            target.delete()
            null
        }
    }

    /**
     * Deletes a stored photo.
     *
     * Anything outside this store is left alone: a play imported from a csv written
     * before photos were copied in still carries somebody else's uri, and this is not
     * the code that gets to delete other people's files.
     */
    suspend fun delete(path: String?): Unit = withContext(io) {
        if (path != null && isStored(path)) File(path).delete()
    }

    /** True for a path this store wrote, which is the only kind it may delete. */
    fun isStored(path: String): Boolean = runCatching {
        File(path).canonicalFile.parentFile == directory.canonicalFile
    }.getOrDefault(false)

    /** The type is a courtesy: the file is decoded from its bytes, not from its name. */
    private fun extensionOf(source: Uri): String {
        val mimeType = runCatching { context.contentResolver.getType(source) }.getOrNull()
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: DEFAULT_EXTENSION
    }

    private companion object {
        const val PHOTO_DIR = "session_photos"
        const val DEFAULT_EXTENSION = "jpg"
    }
}

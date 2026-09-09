package com.boardgamenation.tracker.data.photo

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.boardgamenation.tracker.BuildConfig
import com.boardgamenation.tracker.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Somewhere for a camera app to put the picture it takes.
 *
 * A camera hands back no bytes. It is given a destination, told to fill it, and reports
 * only whether it did -- so unlike the picker, which needs reading, this needs a file
 * another app is allowed to write. App-private storage is not that, which is why the
 * capture lands in the cache behind the same `FileProvider` the shared cards use and
 * [SessionPhotoStore] copies it inwards from there.
 *
 * Its own cache directory, again for the sake of the grant: what the paths file names is
 * reachable, and naming the whole cache would put the BGG image cache one guessed
 * filename away from any camera app the user happens to have installed.
 */
@Singleton
class SessionPhotoCaptures @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher
) {

    /** Whether there is a camera to point at. Without one there is nothing to offer. */
    val isSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /** An empty file for a camera to fill, as a uri it may be granted write access to. */
    suspend fun newCapture(): Uri? = withContext(io) {
        val file = newCaptureFile() ?: return@withContext null
        runCatching { FileProvider.getUriForFile(context, AUTHORITY, file) }.getOrNull()
    }

    /**
     * The empty file itself, which is the half of a capture that can be looked at without
     * a device: handing a uri out is the provider's job and the provider needs one.
     *
     * The file is a throwaway once copied, and it is swept up at the start of the next
     * capture rather than at the end of this one. A camera that is cancelled, or killed,
     * or that simply never returns leaves its empty file behind with nobody around to
     * notice; the next capture is the one moment that is guaranteed to come.
     */
    internal fun newCaptureFile(): File? {
        val directory = File(context.cacheDir, DIRECTORY)
        directory.mkdirs()
        directory.listFiles()?.forEach { it.delete() }

        // The extension is what the copy reads the type off, since a file this app wrote
        // itself carries no other claim about what is in it. Cameras answering
        // ACTION_IMAGE_CAPTURE write jpeg.
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        return file.takeIf { runCatching { it.createNewFile() }.getOrDefault(false) }
    }

    private companion object {
        val AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileprovider"
        const val DIRECTORY = "camera"
    }
}

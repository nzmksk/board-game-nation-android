package com.boardgamenation.tracker.data.photo

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What matters here is that a camera app is handed an empty file of its own to fill, and
 * that the cache does not slowly fill up with the captures nobody kept.
 *
 * Only the file is exercised. Wrapping it in a uri is `FileProvider`'s job and
 * `FileProvider` insists on a real installed app to find its roots in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionPhotoCapturesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val captures = SessionPhotoCaptures(context, UnconfinedTestDispatcher())

    private val directory = File(context.cacheDir, "camera")

    @Test
    fun `a capture is an empty file of its own, off in the cache`() {
        val file = requireNotNull(captures.newCaptureFile())

        assertTrue(file.exists())
        assertEquals(0L, file.length())
        assertEquals(directory.canonicalFile, file.canonicalFile.parentFile)
    }

    @Test
    fun `a capture is named as the jpeg a camera writes, so the copy can read its type`() {
        val file = requireNotNull(captures.newCaptureFile())

        assertEquals("image/jpeg", MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension))
    }

    @Test
    fun `a capture nobody kept is swept up by the next one`() {
        val abandoned = requireNotNull(captures.newCaptureFile())

        val kept = requireNotNull(captures.newCaptureFile())

        assertTrue(abandoned != kept)
        assertEquals(listOf(kept), directory.listFiles().orEmpty().toList())
    }
}

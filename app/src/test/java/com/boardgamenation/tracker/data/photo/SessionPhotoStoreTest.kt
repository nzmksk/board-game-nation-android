package com.boardgamenation.tracker.data.photo

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What matters here is that the bytes stop depending on the picker's grant: after a
 * store there is a file the app owns, and it is still readable with nothing but a path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionPhotoStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SessionPhotoStore(context, UnconfinedTestDispatcher())

    private val photo = Uri.parse("content://com.example.picker/photos/1")
    private val bytes = byteArrayOf(1, 2, 3, 4, 5)

    private fun registerPhoto() {
        shadowOf(context.contentResolver).registerInputStream(photo, ByteArrayInputStream(bytes))
    }

    @Test
    fun `storing copies the picked bytes into app-private storage`() = runTest {
        registerPhoto()

        val stored = File(requireNotNull(store.store(photo)))

        assertTrue(stored.exists())
        assertArrayEquals(bytes, stored.readBytes())
        assertTrue(stored.canonicalPath.startsWith(context.filesDir.canonicalPath))
    }

    @Test
    fun `two photos never land on the same file`() = runTest {
        registerPhoto()
        val first = store.store(photo)
        registerPhoto()
        val second = store.store(photo)

        assertTrue(first != second)
    }

    @Test
    fun `a uri that cannot be read attaches nothing and leaves no stub behind`() = runTest {
        val directory = File(context.filesDir, "session_photos")

        assertNull(store.store(Uri.parse("content://com.example.picker/photos/gone")))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `deleting removes a stored photo`() = runTest {
        registerPhoto()
        val path = requireNotNull(store.store(photo))

        store.delete(path)

        assertFalse(File(path).exists())
    }

    @Test
    fun `deleting leaves a path this store did not write alone`() = runTest {
        val outsider = File(context.filesDir, "not-ours.jpg").apply { writeBytes(bytes) }

        assertFalse(store.isStored(outsider.absolutePath))
        store.delete(outsider.absolutePath)

        assertTrue(outsider.exists())
    }
}

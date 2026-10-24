package io.github.mangi.eta.agent.media

import android.content.Context
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolDisplayImageStoreTest {
    private lateinit var context: Context
    private lateinit var store: ToolDisplayImageStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.cacheDir, "tool-images").deleteRecursively()
        store = ToolDisplayImageStore(context)
    }

    @Test
    fun dataUrlIsWrittenToCacheAndReturnedAsPath() {
        val paths = store.materialize(listOf(image("image/jpeg", byteArrayOf(1, 2, 3))))

        val file = File(paths.single())
        assertTrue(file.isFile)
        assertEquals(listOf<Byte>(1, 2, 3), file.readBytes().toList())
        assertTrue(file.parentFile?.name == "tool-images")
    }

    @Test
    fun limitCapsStoredImages() {
        val paths = store.materialize(List(6) { image("image/png", byteArrayOf(1)) }, limit = 4)

        assertEquals(4, paths.size)
    }

    @Test
    fun nonDataUrlAndEmptyPayloadAreSkipped() {
        val paths = store.materialize(
            listOf(
                AgentModelClient.ModelImage(reference = "https://example.com/a.jpg", mimeType = "image/jpeg", bytes = 0),
                AgentModelClient.ModelImage(reference = "data:image/jpeg;base64,", mimeType = "image/jpeg", bytes = 0),
                image("image/jpeg", byteArrayOf(9)),
            ),
        )

        assertEquals(1, paths.size)
        assertEquals(listOf<Byte>(9), File(paths.single()).readBytes().toList())
    }

    @Test
    fun pruneDeletesExpiredFilesAndKeepsFreshOnes() {
        val directory = File(context.cacheDir, "tool-images").apply { mkdirs() }
        val expired = File(directory, "expired.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(0L) }
        val fresh = File(directory, "fresh.jpg").apply { writeBytes(byteArrayOf(1)) }

        store.prune(directory, System.currentTimeMillis(), Long.MAX_VALUE)

        assertTrue(!expired.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun pruneEnforcesByteBudgetFromOldest() {
        val directory = File(context.cacheDir, "tool-images").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val old = File(directory, "old.jpg").apply {
            writeBytes(ByteArray(10))
            setLastModified(now - 3_000L)
        }
        val middle = File(directory, "middle.jpg").apply {
            writeBytes(ByteArray(10))
            setLastModified(now - 2_000L)
        }
        val fresh = File(directory, "fresh.jpg").apply {
            writeBytes(ByteArray(10))
            setLastModified(now - 1_000L)
        }

        store.prune(directory, now, maxBytes = 20)

        assertTrue(!old.exists())
        assertTrue(middle.exists())
        assertTrue(fresh.exists())
    }

    private fun image(mimeType: String, bytes: ByteArray): AgentModelClient.ModelImage =
        AgentModelClient.ModelImage(
            reference = "data:$mimeType;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}",
            mimeType = mimeType,
            bytes = bytes.size,
        )
}

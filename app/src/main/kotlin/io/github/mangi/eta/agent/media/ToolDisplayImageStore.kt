package io.github.mangi.eta.agent.media

import android.content.Context
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把工具返回的 data URL 截图落到应用缓存，供聊天界面展示。
 *
 * 模型侧仍读取原 data URL；这里只保存展示副本。文件丢失时 UI 静默跳过。
 */
internal class ToolDisplayImageStore(private val context: Context) {
    fun materialize(
        images: List<AgentModelClient.ModelImage>,
        limit: Int = MAX_DISPLAY_IMAGES,
    ): List<String> {
        if (images.isEmpty()) return emptyList()
        val directory = directory()
        if (!directory.isDirectory && !directory.mkdirs()) return emptyList()
        pruneOnce(directory)
        return images.asSequence()
            .take(limit.coerceAtLeast(1))
            .mapNotNull { image -> runCatching { write(image, directory) }.getOrNull() }
            .toList()
    }

    private fun directory(): File = File(context.cacheDir, DIRECTORY)

    private fun write(image: AgentModelClient.ModelImage, directory: File): String? {
        val reference = image.reference
        if (!reference.startsWith(DATA_IMAGE_PREFIX, ignoreCase = true)) return null
        val marker = reference.indexOf(BASE64_MARKER, ignoreCase = true)
        if (marker < 0) return null
        val encoded = reference.substring(marker + BASE64_MARKER.length)
        if (encoded.isBlank()) return null
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_AGENT_IMAGE_BYTES) return null
        val file = File(directory, "${UUID.randomUUID()}.${extension(image.mimeType)}")
        val temporary = File(directory, "${file.name}.tmp")
        return runCatching {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) {
                temporary.delete()
                return@runCatching null
            }
            file.absolutePath
        }.getOrElse {
            temporary.delete()
            null
        }
    }

    private fun extension(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private fun pruneOnce(directory: File) {
        if (!PRUNE_STARTED.compareAndSet(false, true)) return
        Thread {
            runCatching { prune(directory, System.currentTimeMillis(), MAX_CACHE_BYTES) }
        }.apply {
            isDaemon = true
            name = "tool-image-prune"
            start()
        }
    }

    internal fun prune(directory: File, nowMillis: Long, maxBytes: Long) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        val expiredBefore = nowMillis - MAX_AGE_MILLIS
        files.filter { it.lastModified() < expiredBefore }.forEach(File::delete)
        val remaining = directory.listFiles()?.filter { it.isFile }
            ?.sortedBy(File::lastModified)
            ?.toMutableList()
            ?: return
        var total = remaining.sumOf(File::length)
        while (total > maxBytes && remaining.isNotEmpty()) {
            val oldest = remaining.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    private companion object {
        const val DIRECTORY = "tool-images"
        const val MAX_DISPLAY_IMAGES = 4
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1_000
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        const val DATA_IMAGE_PREFIX = "data:image/"
        const val BASE64_MARKER = "base64,"
        val PRUNE_STARTED = AtomicBoolean(false)
    }
}

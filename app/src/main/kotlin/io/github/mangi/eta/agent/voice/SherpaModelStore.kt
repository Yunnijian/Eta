package io.github.mangi.eta.agent.voice

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 本地识别模型管理：从 hf-mirror 按需下载流式 zipformer 中文模型四件套到应用私有目录，
 * 下载按文件顺序进行并提供整体进度；已完整时不重复下载，损坏文件重新拉取。
 */
internal object SherpaModelStore {
    private const val BASE_URL = "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/main"
    private const val EXPECTED_BYTES = 36_313_401L

    private val files = listOf(
        SherpaAsrEngine.MODEL_ENCODER to 21_621_684L,
        SherpaAsrEngine.MODEL_DECODER to 7_509_745L,
        SherpaAsrEngine.MODEL_JOINER to 7_109_975L,
        SherpaAsrEngine.MODEL_TOKENS to 48_697L,
    )

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val progress: Float) : DownloadState
        data object Ready : DownloadState
        data class Failed(val reason: String) : DownloadState
    }

    private val state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState = state.asStateFlow()
    private var inFlight = false

    fun modelDir(context: Context): File = File(context.filesDir, SherpaAsrEngine.MODEL_DIR)

    fun isDownloaded(context: Context): Boolean {
        val dir = modelDir(context)
        return files.all { (name, size) ->
            val file = File(dir, name)
            file.isFile && file.length() >= size
        }
    }

    fun totalBytes(context: Context): Long =
        files.sumOf { (name, _) -> File(modelDir(context), name).takeIf(File::isFile)?.length() ?: 0L }

    suspend fun download(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) =
        withContext(ioDispatcher) {
            if (inFlight) return@withContext
            if (isDownloaded(context)) {
                state.value = DownloadState.Ready
                return@withContext
            }
            inFlight = true
            val client = OkHttpClient()
            try {
                val dir = modelDir(context).apply { mkdirs() }
                val totalExpected = files.sumOf { it.second }
                var received = files.sumOf { (name, _) ->
                    File(dir, name).takeIf(File::isFile)?.length() ?: 0L
                }
                files.forEach { (name, expected) ->
                    val target = File(dir, name)
                    if (target.isFile && target.length() >= expected) return@forEach
                    val partial = File(dir, "$name.part")
                    state.value = DownloadState.Downloading(received.toFloat() / totalExpected)
                    val request = Request.Builder().url("$BASE_URL/$name").build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw SpeechFailure(SpeechErrorCode.NETWORK, "模型下载失败（HTTP ${response.code}），请检查网络后重试")
                        }
                        val body = response.body ?: throw SpeechFailure(SpeechErrorCode.NETWORK, "模型下载响应为空")
                        partial.outputStream().use { output ->
                            val input = body.byteStream()
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                received += read
                                state.value = DownloadState.Downloading(received.toFloat() / totalExpected)
                            }
                        }
                    }
                    if (partial.length() < expected) {
                        partial.delete()
                        throw SpeechFailure(SpeechErrorCode.NETWORK, "模型下载中断，请重试")
                    }
                    if (!partial.renameTo(target)) {
                        partial.delete()
                        throw SpeechFailure(SpeechErrorCode.AUDIO, "模型写入失败，请重试")
                    }
                }
                if (!isDownloaded(context)) {
                    throw SpeechFailure(SpeechErrorCode.NETWORK, "模型下载不完整，请重试")
                }
                state.value = DownloadState.Ready
                SherpaAsrEngine.release()
            } catch (error: Exception) {
                state.value = DownloadState.Failed(
                    (error as? SpeechFailure)?.userMessage ?: "模型下载失败，请检查网络后重试",
                )
            } finally {
                inFlight = false
            }
        }

    fun delete(context: Context) {
        SherpaAsrEngine.release()
        modelDir(context).deleteRecursively()
        state.value = DownloadState.Idle
    }

    const val expectedTotalBytes: Long = EXPECTED_BYTES
}

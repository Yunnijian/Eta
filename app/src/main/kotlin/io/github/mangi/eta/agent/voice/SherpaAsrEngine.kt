package io.github.mangi.eta.agent.voice

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * sherpa-onnx 流式识别引擎：模型从应用目录加载（assetManager 传 null 走文件路径），
 * 进程内单例复用；每次识别创建独立 OnlineStream。
 * 采样与 [SpeechRecorder] 一致：16kHz 单声道 16bit，喂流前转为 [-1,1] 浮点。
 */
internal object SherpaAsrEngine {
    private var recognizer: OnlineRecognizer? = null
    private var loadedFrom: File? = null

    /** 返回流式识别器；模型目录内容变化时重建。模型损坏抛 [SpeechFailure]。 */
    @Synchronized
    fun open(modelDir: File): OnlineRecognizer {
        val current = loadedFrom
        if (recognizer != null && current?.absolutePath == modelDir.absolutePath) {
            return recognizer!!
        }
        release()
        val encoder = File(modelDir, MODEL_ENCODER)
        val decoder = File(modelDir, MODEL_DECODER)
        val joiner = File(modelDir, MODEL_JOINER)
        val tokens = File(modelDir, MODEL_TOKENS)
        if (!encoder.isFile || !decoder.isFile || !joiner.isFile || !tokens.isFile) {
            throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "本地识别模型不完整，请在语音识别设置中重新下载")
        }
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = 2,
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(
                    mustContainNonSilence = true,
                    minTrailingSilence = 2.4f,
                    minUtteranceLength = 0f,
                ),
                rule2 = EndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = 1.2f,
                    minUtteranceLength = 0f,
                ),
                rule3 = EndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = 0f,
                    minUtteranceLength = 20f,
                ),
            ),
            enableEndpoint = true,
        )
        return try {
            OnlineRecognizer(assetManager = null, config = config).also {
                recognizer = it
                loadedFrom = modelDir
            }
        } catch (error: Throwable) {
            throw SpeechFailure(SpeechErrorCode.AUDIO, "本地识别模型加载失败，请删除后重新下载")
        }
    }

    fun stream(recognizer: OnlineRecognizer): OnlineStream = recognizer.createStream()

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
        loadedFrom = null
    }

    /** 16bit LE PCM 节到 [-1,1] 浮点采样；奇数字节按小端补零处理。 */
    fun toSamples(pcm: ByteArray): FloatArray {
        val samples = FloatArray(pcm.size / 2)
        var index = 0
        while (index < samples.size) {
            val offset = index * 2
            val value = ((pcm[offset].toInt() and 0xFF) or (pcm[offset + 1].toInt() shl 8))
            samples[index] = value.toShort() / 32768f
            index++
        }
        return samples
    }

    const val SAMPLE_RATE = 16_000
    const val MODEL_DIR = "sherpa-asr-zh-streaming"
    const val MODEL_ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
    const val MODEL_DECODER = "decoder-epoch-99-avg-1.onnx"
    const val MODEL_JOINER = "joiner-epoch-99-avg-1.onnx"
    const val MODEL_TOKENS = "tokens.txt"
}

package io.github.mangi.eta.ui.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.util.LruCache
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

internal fun MarkdownCode.isMermaid(): Boolean =
    language?.trim()?.equals("mermaid", ignoreCase = true) == true

/**
 * 把 ```mermaid 代码块渲染成静态图片。
 *
 * WebView 只作为不可见的栅格化工具：页面把 SVG 转成 PNG 后立即拆掉，界面上只剩
 * Image。之前让 WebView 直接参与可见布局时，它的硬件合成与列表滚动/高刷刷新节奏
 * 不一致，图会持续闪烁；位图不参与 WebView 合成，滚动与进出视口都只是普通图片。
 *
 * 流式输出期间仍按源码显示；渲染中显示源码块，失败则一直保留源码块，不会吞内容。
 * 已生成过的图按「明暗 + 源码」缓存位图，列表回收后滑回来无需重建 WebView。
 */
@Composable
internal fun MarkdownMermaidBlock(block: MarkdownCode, scope: MarkdownRenderScope) {
    if (scope.reveal != null) {
        MarkdownCodeBlock(block, scope)
        return
    }
    val source = block.text.text
    val dark = scope.isDark
    val cached = remember(source, dark) { MermaidDiagramCache.get(source, dark) }
    var diagram by remember(source, dark) { mutableStateOf(cached) }
    var failed by remember(source, dark) { mutableStateOf(false) }
    val host = remember { MermaidHost() }
    host.source = source
    host.dark = dark
    host.onResult = { heightDp, bitmap ->
        if (bitmap != null && heightDp > 0) {
            val rendered = MermaidDiagram(bitmap, heightDp.coerceAtMost(MAX_MERMAID_HEIGHT_DP))
            MermaidDiagramCache.put(source, dark, rendered)
            diagram = rendered
        } else if (diagram == null) {
            failed = true
        }
    }
    LaunchedEffect(source, dark) { host.restart() }

    val rendered = diagram
    if (rendered != null) {
        Image(
            bitmap = rendered.bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .height(rendered.heightDp.dp),
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        // 渲染期间与失败时都显示源码块，保证内容始终可见。
        MarkdownCodeBlock(block, scope)
        if (!failed) {
            AndroidView(
                factory = { creationContext ->
                    createMermaidWebView(creationContext, host).also { it.visibility = View.INVISIBLE }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp),
                onRelease = { host.detach() },
            )
        }
    }
}

private const val BRIDGE_NAME = "EtaMermaidBridge"
private const val MAX_MERMAID_HEIGHT_DP = 6000
private const val MAX_RENDER_ATTEMPTS = 2

private class MermaidDiagram(val bitmap: Bitmap, val heightDp: Int)

/** 位图缓存按字节计量；超预算时只淘汰缓存，不影响当前正在显示的图。 */
private object MermaidDiagramCache {
    private const val MAX_BYTES = 32 * 1024 * 1024
    private val cache = object : LruCache<String, MermaidDiagram>(MAX_BYTES) {
        override fun sizeOf(key: String, value: MermaidDiagram): Int = value.bitmap.allocationByteCount
    }

    fun get(source: String, dark: Boolean): MermaidDiagram? = cache.get(key(source, dark))

    fun put(source: String, dark: Boolean, diagram: MermaidDiagram) {
        cache.put(key(source, dark), diagram)
    }

    private fun key(source: String, dark: Boolean): String =
        (if (dark) "dark:" else "light:") + source
}

/**
 * WebView 生命周期与渲染参数放在同一个持有者里：页面加载完成前 render 是空操作，
 * 加载完成或内容/主题变化时只重新执行一次脚本，不重载 mermaid.js。
 *
 * 每次渲染带递增序号：迟到的结果（例如上一次渲染的超时兜底）不会覆盖新结果，
 * 否则已显示的图会被旧的「失败」回退成源码。
 */
private class MermaidHost {
    var source: String = ""
    var dark: Boolean = false
    var onResult: ((Int, Bitmap?) -> Unit)? = null

    private var webView: WebView? = null
    private var ready = false
    private var requestId = 0
    private var attempts = 0

    fun attach(view: WebView) {
        webView = view
        ready = false
    }

    fun onPageFinished() {
        ready = true
        render()
    }

    /** 内容或主题变化后的首次渲染；重试预算从这里重新计数。 */
    fun restart() {
        attempts = 0
        render()
    }

    fun render() {
        val view = webView ?: return
        if (!ready) return
        val request = ++requestId
        attempts += 1
        val encoded = Base64.encodeToString(source.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        view.evaluateJavascript("renderDiagram('$encoded', $dark, $request)", null)
    }

    fun deliver(request: Int, heightDp: Int, bitmap: Bitmap?) {
        if (request != requestId) return
        // 首帧容器可能尚未布局：失败先自动重试一次，仍失败才交给界面降级源码。
        if (bitmap == null && attempts < MAX_RENDER_ATTEMPTS) {
            render()
            return
        }
        onResult?.invoke(heightDp, bitmap)
    }

    fun detach() {
        ready = false
        requestId += 1
        val view = webView
        webView = null
        view?.removeJavascriptInterface(BRIDGE_NAME)
        view?.stopLoading()
        view?.destroy()
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createMermaidWebView(context: Context, host: MermaidHost): WebView =
    WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        // 页面只加载本地资产：关闭 DOM 存储与网络，内容不会外发。
        settings.domStorageEnabled = false
        settings.allowFileAccess = true
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.blockNetworkImage = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                host.onPageFinished()
            }
        }
        addJavascriptInterface(MermaidBridge(this, host), BRIDGE_NAME)
        host.attach(this)
        loadUrl("file:///android_asset/mermaid/view.html")
    }

private class MermaidBridge(private val view: WebView, private val host: MermaidHost) {
    /** JS 桥在后台线程回调：位图解码留在这里，主线程只接收结果。 */
    @JavascriptInterface
    fun onResult(requestId: String, height: String, png: String) {
        val request = requestId.toIntOrNull() ?: return
        val heightDp = height.toIntOrNull() ?: 0
        val bitmap = if (heightDp > 0 && png.isNotEmpty()) decodeDiagram(png) else null
        view.post { host.deliver(request, heightDp, bitmap) }
    }
}

private fun decodeDiagram(dataUrl: String): Bitmap? = runCatching {
    val encoded = dataUrl.substringAfter(',', dataUrl)
    val bytes = Base64.decode(encoded, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

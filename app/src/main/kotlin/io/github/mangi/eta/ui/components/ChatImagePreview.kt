package io.github.mangi.eta.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton

private const val PREVIEW_MAX_PX = 2048

/** 同步解码；调用方必须已经切到 IO 线程。 */
internal fun decodeChatBitmap(source: String, maxPx: Int): ImageBitmap? {
    if (source.startsWith("data:image/", ignoreCase = true)) {
        return decodeDataUrlBitmap(source)
    }
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxPx || bounds.outHeight / sampleSize > maxPx) {
            sampleSize *= 2
        }
        BitmapFactory.decodeFile(source, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            ?.asImageBitmap()
    }.getOrNull()
}

@Composable
internal fun rememberChatBitmap(source: String, maxPx: Int): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, source, maxPx) {
        value = withContext(Dispatchers.IO) { decodeChatBitmap(source, maxPx) }
    }
    return bitmap
}

/** 全屏图片预览。Overlay 等没有 Activity 窗口的宿主直接不渲染，避免弹窗崩溃。 */
@Composable
internal fun ChatImagePreviewDialog(source: String, onDismiss: () -> Unit) {
    if (LocalContext.current.findHostActivity() == null) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
        ) {
            val bitmap = rememberChatBitmap(source, PREVIEW_MAX_PX)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.chat_image_preview),
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.chat_image_close),
                    tint = Color.White,
                )
            }
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

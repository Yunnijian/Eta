package io.github.mangi.eta.agent.virtualscreen

import org.json.JSONObject

internal const val VIRTUAL_SCREEN_MARKER = "ETA_VIRTUAL_SCREEN_RESULT:"

internal class VirtualScreenException(
    val code: String,
    val detail: String? = null,
) : Exception(detail ?: code)

internal fun JSONObject.requireOk(): JSONObject {
    if (!optBoolean("ok")) {
        throw VirtualScreenException(
            code = optString("code").ifBlank { "VIRTUAL_SCREEN_OPERATION_FAILED" },
            detail = optString("detail").ifBlank { optString("message").ifBlank { null } },
        )
    }
    return this
}

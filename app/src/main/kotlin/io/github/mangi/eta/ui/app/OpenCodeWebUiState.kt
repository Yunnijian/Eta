package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.R

internal enum class OpenCodeWebPhase { CHECKING, NOT_INSTALLED, READY, STARTING, RUNNING, FAILED }

internal data class OpenCodeWebUiState(
    val phase: OpenCodeWebPhase = OpenCodeWebPhase.CHECKING,
    val errorCode: String? = null,
) {
    val canStop: Boolean get() = phase == OpenCodeWebPhase.STARTING || phase == OpenCodeWebPhase.RUNNING

    fun actionLabel(context: Context): String = context.getString(when (phase) {
        OpenCodeWebPhase.CHECKING -> R.string.capability_opencode_checking
        OpenCodeWebPhase.NOT_INSTALLED -> R.string.capability_opencode_install
        OpenCodeWebPhase.READY -> R.string.action_launch_opencode_web
        OpenCodeWebPhase.STARTING -> R.string.capability_opencode_preparing
        OpenCodeWebPhase.RUNNING -> R.string.capability_opencode_open
        OpenCodeWebPhase.FAILED -> R.string.capability_opencode_retry
    })
}

internal fun OpenCodeWebLaunchResult.Failed.message(context: Context): String = context.getString(when (code) {
    "ROOT_REQUIRED" -> R.string.capability_opencode_root_required
    "BACKGROUND_START_NOT_ALLOWED" -> R.string.capability_background_failed
    "OPENCODE_EXITED", "PROCESS_EXITED" -> R.string.capability_opencode_exited
    "LINUX_ENVIRONMENT_NOT_READY", "PROFILE_NOT_INSTALLED" -> R.string.capability_opencode_not_installed
    "PROOT_UNAVAILABLE" -> R.string.capability_opencode_proot_unavailable
    "LOGS_UNAVAILABLE" -> R.string.capability_opencode_logs_unavailable
    "URL_TIMEOUT" -> R.string.linux_opencode_web_failed_url
    "PAIR_FAILED" -> R.string.linux_opencode_web_failed_pair
    "BROWSER_UNAVAILABLE" -> R.string.linux_opencode_web_failed_browser
    else -> R.string.linux_opencode_web_failed_start
})

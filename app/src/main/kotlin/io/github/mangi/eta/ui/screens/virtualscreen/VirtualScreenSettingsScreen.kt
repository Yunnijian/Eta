package io.github.mangi.eta.ui.screens.virtualscreen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.virtualscreen.mcp.VirtualScreenMcpRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.SwitchPref
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VirtualScreenSettingsScreen(
    context: Context,
    onBack: () -> Unit,
) {
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    var endpoint by remember { mutableStateOf(VirtualScreenMcpRuntime.activeEndpoint(appContext)) }
    var serviceEnabled by remember {
        mutableStateOf(VirtualScreenMcpRuntime.isServiceEnabled(appContext))
    }
    var lanEnabled by remember { mutableStateOf(VirtualScreenMcpRuntime.isLanEnabled(appContext)) }
    var lanAddress by remember { mutableStateOf(VirtualScreenMcpRuntime.lanAddress()) }
    var showToken by remember { mutableStateOf(false) }
    val configuredEndpoint = remember { VirtualScreenMcpRuntime.endpoint(appContext) }
    val visibleEndpoint = endpoint ?: configuredEndpoint
    val lanEndpoint = lanAddress?.let { "http://$it:${visibleEndpoint.port}" }

    MiuixScaffoldPage(
        title = stringResource(R.string.virtual_screen_settings_title),
        onBack = onBack,
    ) {
        item(key = "operation_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.virtual_screen_operation_group))
        }
        item(key = "operation") {
            EtaPreferenceGroup {
                SwitchPref(
                    context = context,
                    prefs = agentPrefs,
                    title = stringResource(R.string.settings_virtual_display_title),
                    key = Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED,
                    icon = Icons.Rounded.Layers,
                    iconTint = EtaPreferenceColors.Blue,
                )
            }
        }

        item(key = "mcp_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.virtual_screen_access_group))
        }
        item(key = "mcp") {
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = stringResource(R.string.virtual_screen_mcp_service),
                    summary = stringResource(R.string.virtual_screen_mcp_service_summary),
                    checked = serviceEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            if (enabled) {
                                val started = withContext(Dispatchers.IO) {
                                    VirtualScreenMcpRuntime.ensureStarted(appContext)
                                }
                                endpoint = started
                                serviceEnabled = started != null
                                lanAddress = VirtualScreenMcpRuntime.lanAddress()
                                if (started == null) {
                                    toast(context, R.string.virtual_screen_mcp_start_failed)
                                }
                            } else {
                                withContext(Dispatchers.IO) {
                                    VirtualScreenMcpRuntime.stop(appContext)
                                }
                                endpoint = null
                                serviceEnabled = false
                            }
                        }
                    },
                )
                EtaPreferenceDivider()
                EtaSwitchPreference(
                    title = stringResource(R.string.virtual_screen_lan_access),
                    summary = stringResource(R.string.virtual_screen_lan_access_summary),
                    checked = lanEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            endpoint = withContext(Dispatchers.IO) {
                                VirtualScreenMcpRuntime.setLanEnabled(appContext, enabled)
                            } ?: VirtualScreenMcpRuntime.activeEndpoint(appContext)
                            lanEnabled = enabled
                            lanAddress = VirtualScreenMcpRuntime.lanAddress()
                        }
                    },
                )
                if (lanEnabled) {
                    EtaPreferenceDivider()
                    EtaPreference(
                        title = stringResource(R.string.virtual_screen_lan_address),
                        summary = lanEndpoint
                            ?: stringResource(R.string.virtual_screen_lan_unavailable),
                        onLongClick = lanEndpoint?.let {
                            { copy(context, it); toast(context, R.string.virtual_screen_copied) }
                        },
                    )
                }
                EtaPreferenceDivider()
                EtaPreference(
                    title = stringResource(R.string.virtual_screen_endpoint),
                    summary = visibleEndpoint.url,
                    onLongClick = {
                        copy(context, visibleEndpoint.url)
                        toast(context, R.string.virtual_screen_copied)
                    },
                )
                EtaPreferenceDivider()
                EtaPreference(
                    title = stringResource(R.string.virtual_screen_token),
                    summary = if (showToken) visibleEndpoint.token else "••••••••••••",
                    onClick = { showToken = !showToken },
                    onLongClick = {
                        copy(context, visibleEndpoint.token)
                        toast(context, R.string.virtual_screen_copied)
                    },
                )
            }
        }
    }
}

private fun copy(context: Context, value: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("eta-virtual-screen", value))
}

private fun toast(context: Context, message: Int) {
    Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
}

package io.github.mangi.eta.ui.screens.virtualscreen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.virtualscreen.mcp.VirtualScreenMcpRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.SwitchPref
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
            SmallTitle(stringResource(R.string.virtual_screen_operation_group))
        }
        item(key = "operation") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SwitchPref(
                    context = context,
                    prefs = agentPrefs,
                    title = stringResource(R.string.settings_virtual_display_title),
                    key = Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED,
                    icon = Icons.Rounded.Layers,
                    iconTint = MiuixTheme.colorScheme.primary,
                )
            }
        }

        item(key = "mcp_title") {
            SmallTitle(stringResource(R.string.virtual_screen_access_group))
        }
        item(key = "mcp_card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SwitchPreference(
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
                SwitchPreference(
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
                    val address = lanEndpoint
                    BasicComponent(
                        title = stringResource(R.string.virtual_screen_lan_address),
                        summary = address ?: stringResource(R.string.virtual_screen_lan_unavailable),
                        endActions = {
                            if (address != null) {
                                CopyButton(onClick = { copy(context, address) })
                            }
                        },
                    )
                }
                BasicComponent(
                    title = stringResource(R.string.virtual_screen_endpoint),
                    summary = visibleEndpoint.url,
                    endActions = {
                        CopyButton(onClick = { copy(context, visibleEndpoint.url) })
                    },
                )
                BasicComponent(
                    title = stringResource(R.string.virtual_screen_token),
                    summary = if (showToken) visibleEndpoint.token else TOKEN_MASK,
                    endActions = {
                        TextButton(
                            text = stringResource(
                                if (showToken) R.string.page_hide_bb0e7e else R.string.page_show_71b677,
                            ),
                            minWidth = 0.dp,
                            minHeight = 32.dp,
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            onClick = { showToken = !showToken },
                        )
                        Spacer(Modifier.width(8.dp))
                        CopyButton(onClick = { copy(context, visibleEndpoint.token) })
                    },
                )
            }
        }
    }
}

@Composable
private fun CopyButton(onClick: () -> Unit) {
    TextButton(
        text = stringResource(R.string.ui_copy_4edd1d),
        minWidth = 0.dp,
        minHeight = 32.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        onClick = onClick,
    )
}

private fun copy(context: Context, value: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("eta-virtual-screen", value))
    toast(context, R.string.virtual_screen_copied)
}

private fun toast(context: Context, message: Int) {
    Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
}

private const val TOKEN_MASK = "••••••••••••"

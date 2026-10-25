package io.github.mangi.eta.ui.screens.virtualscreen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.padding
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
import io.github.mangi.eta.agent.virtualscreen.VirtualScreenSessionManager
import io.github.mangi.eta.agent.virtualscreen.mcp.VirtualScreenMcpRuntime
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownItem

@Composable
internal fun VirtualScreenSettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf(VirtualScreenMcpRuntime.activeEndpoint(appContext)) }
    var serviceEnabled by remember {
        mutableStateOf(VirtualScreenMcpRuntime.isServiceEnabled(appContext))
    }
    var lanEnabled by remember { mutableStateOf(VirtualScreenMcpRuntime.isLanEnabled(appContext)) }
    var lanAddress by remember { mutableStateOf(VirtualScreenMcpRuntime.lanAddress()) }
    var showToken by remember { mutableStateOf(false) }
    var timeoutMinutes by remember {
        mutableStateOf(VirtualScreenSessionManager.idleTimeoutMinutes(appContext))
    }
    val timeoutOptions = listOf(
        stringResource(R.string.virtual_screen_timeout_5) to 5,
        stringResource(R.string.virtual_screen_timeout_10) to 10,
        stringResource(R.string.virtual_screen_timeout_20) to 20,
        stringResource(R.string.virtual_screen_timeout_30) to 30,
        stringResource(R.string.virtual_screen_timeout_60) to 60,
        stringResource(R.string.virtual_screen_timeout_never) to 0,
    )
    var defaultScreenIndex by remember {
        mutableStateOf(
            if (VirtualScreenSessionManager.defaultDeviceScreen(appContext) ==
                VirtualScreenSessionManager.DefaultDeviceScreen.VIRTUAL
            ) 1 else 0,
        )
    }
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
                EtaDropdownPreference(
                    title = stringResource(R.string.virtual_screen_default_screen),
                    summary = stringResource(R.string.virtual_screen_default_screen_summary),
                    items = listOf(
                        DropdownItem(text = stringResource(R.string.virtual_screen_screen_main)),
                        DropdownItem(text = stringResource(R.string.virtual_screen_screen_virtual)),
                    ),
                    selectedIndex = defaultScreenIndex,
                    onSelectedIndexChange = { index ->
                        defaultScreenIndex = index
                        VirtualScreenSessionManager.setDefaultDeviceScreen(
                            appContext,
                            if (index == 1) {
                                VirtualScreenSessionManager.DefaultDeviceScreen.VIRTUAL
                            } else {
                                VirtualScreenSessionManager.DefaultDeviceScreen.MAIN
                            },
                        )
                    },
                )
                EtaPreferenceDivider()
                EtaDropdownPreference(
                    title = stringResource(R.string.virtual_screen_session_timeout),
                    summary = stringResource(R.string.virtual_screen_session_timeout_summary),
                    items = timeoutOptions.map { (label, _) -> DropdownItem(text = label) },
                    selectedIndex = timeoutOptions
                        .indexOfFirst { (_, minutes) -> minutes == timeoutMinutes }
                        .coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        val minutes = timeoutOptions[index].second
                        timeoutMinutes = minutes
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                VirtualScreenSessionManager.setIdleTimeoutMinutes(appContext, minutes)
                                VirtualScreenSessionManager.applyIdleTimeout(minutes)
                            }
                        }
                    },
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

        item(key = "tools") {
            EtaPreferenceGroup(
                modifier = Modifier
                    .padding(horizontal = EtaPreferenceDefaults.SidePadding)
                    .padding(top = 8.dp, bottom = EtaPreferenceDefaults.GroupSpacing),
            ) {
                EtaArrowPreference(
                    title = stringResource(R.string.virtual_screen_tools),
                    summary = stringResource(R.string.virtual_screen_tools_entry_summary),
                    onClick = { onNavigate(AppRoute.VirtualScreenTools) },
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

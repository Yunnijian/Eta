package io.github.mangi.eta.ui.screens.virtualscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.screens.tools.ToolCard

@Composable
internal fun VirtualScreenToolsScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val capabilities = rememberDeviceCapabilities()
    MiuixScaffoldPage(
        title = stringResource(R.string.virtual_screen_tools),
        onBack = onBack,
    ) {
        item(key = "tools") {
            VirtualScreenToolGrid(
                rootGranted = capabilities.root.isGranted,
                capabilities = capabilities.tools,
                onAction = { action ->
                    when (action) {
                        AgentToolsAction.OpenEnhancements -> onNavigate(AppRoute.SystemEnhance)
                        AgentToolsAction.OpenPermissions -> onNavigate(AppRoute.Permissions)
                        else -> Unit
                    }
                },
            )
        }
    }
}

@Composable
private fun VirtualScreenToolGrid(
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
) {
    val tools = listOf(
        ToolItemUi(
            id = "virtual_display",
            title = "virtual_display",
            summary = stringResource(R.string.tool_virtual_display_description),
        ),
        ToolItemUi(
            id = "virtual_observe",
            title = "virtual_observe",
            summary = stringResource(R.string.tool_virtual_observe_description),
        ),
        ToolItemUi(
            id = "virtual_tap",
            title = "virtual_tap",
            summary = stringResource(R.string.tool_virtual_tap_description),
        ),
        ToolItemUi(
            id = "virtual_swipe",
            title = "virtual_swipe",
            summary = stringResource(R.string.tool_virtual_swipe_description),
        ),
        ToolItemUi(
            id = "virtual_input",
            title = "virtual_input",
            summary = stringResource(R.string.tool_virtual_input_description),
        ),
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 12.dp),
    ) {
        val singleColumn = maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.3f
        if (singleColumn) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tools.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        rootGranted = rootGranted,
                        capabilities = capabilities,
                        onAction = onAction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tools.chunked(2).forEach { rowTools ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        rowTools.forEach { tool ->
                            ToolCard(
                                tool = tool,
                                rootGranted = rootGranted,
                                capabilities = capabilities,
                                onAction = onAction,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                        if (rowTools.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

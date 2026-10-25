package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    /** 默认操作屏幕设为虚拟屏时不下发的主屏 GUI 工具，避免模型绕过虚拟屏直接操作主屏。 */
    private val MAIN_SCREEN_GUI_TOOLS = setOf(
        "tap", "tap_area", "tap_element", "long_press", "long_press_element",
        "swipe", "scroll", "scroll_element", "type_text", "press_key",
        "wait_for_text", "wait_for_package", "observe_screen", "launch_app", "open_uri",
    )

    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        memoryWritable: Boolean = true,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
        localWebSearch: Boolean = true,
        virtualScreenOnly: Boolean = false,
    ): JSONArray {
        val built = capabilities.project(JSONArray().also { tools ->
            AgentContextAppToolCatalog.appendTo(tools)
            AgentGestureToolCatalog.appendTo(tools)
            AgentVirtualScreenToolCatalog.appendTo(tools)
            AgentTextSystemToolCatalog.appendTo(tools)
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            )
            if (browserTools) {
                AgentWebToolCatalog.appendTo(tools, includeSearch = localWebSearch)
                AgentBrowserToolCatalog.appendTo(tools)
            }
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = skillGitHubDiscovery,
                githubInstall = skillGitHubInstall,
            )
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools, writable = memoryWritable)
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
                AgentFileToolCatalog.appendTo(tools)
            }
        })
        if (!virtualScreenOnly) return built
        val hasVirtualTools = (0 until built.length()).any { index ->
            built.getJSONObject(index).getJSONObject("function").getString("name").startsWith("virtual_")
        }
        // Root 探测状态可能过期；即使当前判定为未授权，也保留虚拟屏工具，
        // 由执行期返回 ROOT_REQUIRED，而不是静默退回主屏操作。
        val withVirtualTools = if (hasVirtualTools) {
            built
        } else {
            JSONArray().also { merged ->
                for (index in 0 until built.length()) merged.put(built.getJSONObject(index))
                AgentVirtualScreenToolCatalog.appendTo(merged)
            }
        }
        return JSONArray().also { filtered ->
            for (index in 0 until withVirtualTools.length()) {
                val tool = withVirtualTools.getJSONObject(index)
                if (tool.getJSONObject("function").getString("name") !in MAIN_SCREEN_GUI_TOOLS) {
                    filtered.put(tool)
                }
            }
        }
    }
}

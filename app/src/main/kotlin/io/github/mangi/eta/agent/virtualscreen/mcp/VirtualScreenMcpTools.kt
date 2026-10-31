package io.github.mangi.eta.agent.virtualscreen.mcp

import io.github.mangi.eta.agent.model.AgentToolCatalog
import org.json.JSONArray

/**
 * MCP 对外暴露的虚拟屏工具面。
 *
 * 不定义任何工具 schema：定义只存在于现有工具目录（AgentToolCatalog），这里只是
 * 过滤出驱动虚拟屏/GUI 所需的工具子集；调用同样走 AgentLocalTools 的同一条执行链，
 * 与 App 内 Agent 使用的是同一套工具，不会出现第二套虚拟屏工具。
 */
internal object VirtualScreenMcpTools {
    val names: Set<String> = setOf(
        "get_current_context",
        "search_apps",
        "launch_app",
        "open_uri",
        "present_to_user",
        "observe_screen",
        "release_gui",
        "tap",
        "tap_area",
        "tap_element",
        "long_press",
        "long_press_element",
        "swipe",
        "scroll",
        "scroll_element",
        "type_text",
        "press_key",
        "wait_for_text",
        "wait_for_package",
    )

    /** 现有工具目录的 OpenAI 风格 schema，按 names 过滤后供 MCP tools/list 使用。 */
    fun openAiSchemas(): JSONArray {
        val all = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            memoryTools = false,
            localWebSearch = false,
        )
        return JSONArray().also { filtered ->
            for (index in 0 until all.length()) {
                val tool = all.getJSONObject(index)
                val name = tool.optJSONObject("function")?.optString("name") ?: continue
                if (name in names) filtered.put(tool)
            }
        }
    }
}

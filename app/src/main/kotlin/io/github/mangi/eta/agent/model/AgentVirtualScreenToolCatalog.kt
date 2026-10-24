package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 虚拟屏工具 schema；与主屏 observe_screen/tap 等完全分开。 */
internal object AgentVirtualScreenToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "virtual_display",
                    description = "创建/关闭/查询虚拟屏，或在虚拟屏里列出、启动应用。虚拟屏独立于手机主屏，坐标与主屏工具不通用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray(listOf("create", "close", "status", "list_apps", "launch_app")))
                                        .put("description", "create 创建虚拟屏；close 关闭；status 查询；list_apps 列出可启动应用；launch_app 启动应用。"),
                                )
                                .put("width", JSONObject().put("type", "integer").put("description", "虚拟屏宽，默认使用手机原生宽度。"))
                                .put("height", JSONObject().put("type", "integer").put("description", "虚拟屏高，默认使用手机原生高度。"))
                                .put("density", JSONObject().put("type", "integer").put("description", "虚拟屏 DPI，默认使用手机原生密度。"))
                                .put("rotation", JSONObject().put("type", "integer").put("description", "0–3；默认 0。"))
                                .put("allow_screen_off", JSONObject().put("type", "boolean").put("description", "锁屏/熄屏时是否保持虚拟屏；默认 false。"))
                                .put("idle_timeout_minutes", JSONObject().put("type", "integer").put("description", "无操作自动关闭分钟数；默认 10。"))
                                .put("query", JSONObject().put("type", "string").put("description", "list_apps 的标签或包名过滤。"))
                                .put("package", JSONObject().put("type", "string").put("description", "list_apps 的精确包名过滤。"))
                                .put("component", JSONObject().put("type", "string").put("description", "launch_app 的 package/Activity，必须先用 list_apps 查到完整组件；不要只传包名。"))
                                .put("uri", JSONObject().put("type", "string").put("description", "launch_app 可选的 VIEW uri。"))
                                .put("restart_approved", JSONObject().put("type", "boolean").put("description", "目标应用已在主屏运行时是否允许强制重启；默认 false。")),
                        )
                        .put("required", JSONArray().put("action")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "virtual_observe",
                    description = "观察虚拟屏：返回节点树和/或 JPEG 截图。坐标是虚拟显示像素，只能配合 virtual_tap/virtual_swipe 使用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray(listOf("tree", "screenshot", "both")))
                                        .put("description", "tree 只读节点；screenshot 只取图；both 同时。默认 tree。"),
                                )
                                .put("max_nodes", JSONObject().put("type", "integer").put("description", "节点上限，默认 120。"))
                                .put("max_image_width", JSONObject().put("type", "integer").put("description", "截图最大宽度；当前返回原生尺寸。")),
                        ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "virtual_tap",
                    description = "点击虚拟屏坐标（虚拟显示像素），不要用于手机主屏。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer")),
                        )
                        .put("required", JSONArray().put("x").put("y")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "virtual_swipe",
                    description = "在虚拟屏上按给定路径滑动（虚拟显示像素），不要用于手机主屏。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "points",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("description", "2–64 个 [x,y] 点。")
                                        .put(
                                            "items",
                                            JSONObject()
                                                .put("type", "array")
                                                .put("items", JSONObject().put("type", "integer")),
                                        ),
                                )
                                .put("duration_ms", JSONObject().put("type", "integer").put("description", "100–2000 毫秒，默认 300。")),
                        )
                        .put("required", JSONArray().put("points")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "virtual_input",
                    description = "向虚拟屏输入文本或导航按键；文本输入依赖目标编辑器，按键仅支持返回/方向/编辑等有限键值。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string").put("description", "要输入的文本，走无障碍写入当前虚拟屏输入框，支持中文。"))
                                .put("key", JSONObject().put("type", "integer").put("description", "Android keyCode，例如 4 返回键。"))
                                .put("replace", JSONObject().put("type", "boolean").put("description", "文本是否替换当前编辑框内容；默认 false。")),
                        ),
                ),
            )
    }
}

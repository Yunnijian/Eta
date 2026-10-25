package io.github.mangi.eta.agent.virtualscreen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.virtualscreen.backend.VirtualScreenConfig
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 虚拟屏工具族。与主屏工具完全分开：坐标始终是虚拟显示像素，不会落到手机主屏。
 */
internal class VirtualScreenTools(private val context: Context) {
    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult = when (name) {
        "virtual_display" -> display(args)
        "virtual_observe" -> observe(args)
        "virtual_tap" -> tap(args)
        "virtual_swipe" -> swipe(args)
        "virtual_input" -> input(args)
        else -> errorResult("UNKNOWN_TOOL", "未知虚拟屏工具")
    }

    private fun display(args: JSONObject): AgentModelClient.ToolResult {
        val action = args.optString("action").ifBlank { "status" }
        return try {
            when (action) {
                "create" -> {
                    val defaults = VirtualScreenSessionManager.defaultConfig(context)
                    val config = VirtualScreenConfig(
                        width = args.optInt("width", defaults.width),
                        height = args.optInt("height", defaults.height),
                        density = args.optInt("density", defaults.density),
                        rotation = args.optInt("rotation", 0),
                        allowScreenOff = args.optBoolean("allow_screen_off", false),
                        idleTimeoutMinutes = args.optInt(
                            "idle_timeout_minutes",
                            VirtualScreenSessionManager.idleTimeoutMinutes(context),
                        ),
                    )
                    val state = runBlocking(Dispatchers.IO) {
                        VirtualScreenSessionManager.create(context, config)
                    }
                    textResult(
                        JSONObject()
                            .put("ok", true)
                            .put("running", true)
                            .put("backend", "root")
                            .put("display_id", state.displayId)
                            .put("width", state.width)
                            .put("height", state.height)
                            .put("density", state.config.density)
                            .put("rotation", state.rotation)
                            .toString(),
                    )
                }
                "close" -> {
                    runBlocking(Dispatchers.IO) { VirtualScreenSessionManager.close() }
                    textResult(JSONObject().put("ok", true).put("running", false).toString())
                }
                "status" -> textResult(
                    runBlocking(Dispatchers.IO) { VirtualScreenSessionManager.status(context) }
                        .put("ok", true)
                        .toString(),
                )
                "list_apps" -> listApps(args)
                "launch_app" -> launch(args)
                else -> errorResult("UNKNOWN_ACTION", "virtual_display.action 只支持 create/close/status/list_apps/launch_app")
            }
        } catch (failure: VirtualScreenException) {
            errorResult(failure.code, failure.detail ?: failure.code)
        } catch (failure: IllegalArgumentException) {
            errorResult("INVALID_ARGUMENT", failure.message ?: "虚拟屏参数无效")
        } catch (failure: Throwable) {
            errorResult("VIRTUAL_SCREEN_FAILED", failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun observe(args: JSONObject): AgentModelClient.ToolResult {
        val mode = args.optString("mode").ifBlank { "tree" }
        val includeTree = mode == "tree" || mode == "both"
        val includeScreenshot = mode == "screenshot" || mode == "both"
        return try {
            val data = runBlocking(Dispatchers.IO) {
                VirtualScreenSessionManager.observe(context)
            }.requireOk()
            val displayId = data.getInt("displayId")
            val width = data.getInt("width")
            val height = data.getInt("height")
            val rotation = data.getInt("rotation")
            val observationId = UUID.randomUUID().toString()
            val result = JSONObject()
                .put("ok", true)
                .put("display_id", displayId)
                .put("width", width)
                .put("height", height)
                .put("rotation", rotation)
                .put("observation_id", observationId)
                .put(
                    "coordinate_contract",
                    JSONObject()
                        .put("coordinate_space", "screen")
                        .put("width", width)
                        .put("height", height)
                        .put("note", "坐标是虚拟显示像素；不要用于主屏 tap/swipe"),
                )
            if (includeTree) {
                val nodes = AgentAccessibilityService.current()
                    ?.captureVirtualScreenNodes(displayId, args.optInt("max_nodes", 120))
                    .orEmpty()
                if (nodes.isEmpty()) {
                    result.put("nodes_error", "虚拟屏节点暂不可用，请使用截图定位")
                } else {
                    result.put("ui_nodes", JSONArray(nodes.map { node -> node.toJson() }))
                }
            }
            val image = if (includeScreenshot) decodeScreenshot(data.optString("image")) else null
            if (image != null) {
                result.put(
                    "screenshot",
                    JSONObject()
                        .put("mime_type", image.mimeType)
                        .put("width", image.width ?: width)
                        .put("height", image.height ?: height)
                        .put("bytes", image.bytes),
                )
            }
            AgentModelClient.ToolResult(
                content = result.toString(),
                images = listOfNotNull(image),
            )
        } catch (failure: VirtualScreenException) {
            errorResult(failure.code, failure.detail ?: failure.code)
        } catch (failure: Throwable) {
            errorResult("VIRTUAL_OBSERVE_FAILED", failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun tap(args: JSONObject): AgentModelClient.ToolResult = try {
        val x = args.getInt("x")
        val y = args.getInt("y")
        textResult(
            runBlocking(Dispatchers.IO) { VirtualScreenSessionManager.tap(context, x, y) }
                .requireOk()
                .put("ok", true)
                .toString(),
        )
    } catch (failure: VirtualScreenException) {
        errorResult(failure.code, failure.detail ?: failure.code)
    } catch (failure: Throwable) {
        errorResult("VIRTUAL_TAP_FAILED", failure.message ?: failure.javaClass.simpleName)
    }

    private fun swipe(args: JSONObject): AgentModelClient.ToolResult = try {
        val values = args.getJSONArray("points")
        val points = List(values.length()) { index ->
            val point = values.getJSONArray(index)
            require(point.length() == 2) { "points 必须是 [x,y] 数组" }
            point.getInt(0) to point.getInt(1)
        }
        require(points.size in 2..64) { "points 需要 2–64 个点" }
        val duration = args.optInt("duration_ms", 300).coerceIn(100, 2_000)
        textResult(
            runBlocking(Dispatchers.IO) { VirtualScreenSessionManager.swipe(context, points, duration) }
                .requireOk()
                .put("ok", true)
                .toString(),
        )
    } catch (failure: VirtualScreenException) {
        errorResult(failure.code, failure.detail ?: failure.code)
    } catch (failure: Throwable) {
        errorResult("VIRTUAL_SWIPE_FAILED", failure.message ?: failure.javaClass.simpleName)
    }

    private fun input(args: JSONObject): AgentModelClient.ToolResult = try {
        val key = args.optInt("key", 0)
        val text = args.optString("text")
        when {
            key != 0 -> textResult(
                runBlocking(Dispatchers.IO) { VirtualScreenSessionManager.key(context, key) }
                    .requireOk()
                    .put("ok", true)
                    .toString(),
            )
            text.isNotEmpty() -> writeText(text, args.optBoolean("replace", false))
            else -> errorResult("INVALID_ARGUMENT", "virtual_input 需要 text 或 key")
        }
    } catch (failure: VirtualScreenException) {
        errorResult(failure.code, failure.detail ?: failure.code)
    } catch (failure: Throwable) {
        errorResult("VIRTUAL_INPUT_FAILED", failure.message ?: failure.javaClass.simpleName)
    }

    private fun launch(args: JSONObject): AgentModelClient.ToolResult = try {
        val component = resolveComponent(args.optString("component"))
        val result = runBlocking(Dispatchers.IO) {
            VirtualScreenSessionManager.launch(
                context = context,
                component = component,
                uri = args.optString("uri").takeIf { it.isNotBlank() },
                restartApproved = args.optBoolean("restart_approved", false),
            )
        }
        if (!result.optBoolean("ok")) {
            val code = result.optString("code").ifBlank { "VIRTUAL_APP_LAUNCH_FAILED" }
            val message = if (code == "APP_RESTART_CONFIRMATION_REQUIRED") {
                "应用已在手机主屏运行。确认可以重启后，用 restart_approved=true 重试；否则请用户手动切换。"
            } else {
                result.optString("detail").ifBlank { code }
            }
            errorResult(code, message)
        } else {
            textResult(result.put("ok", true).toString())
        }
    } catch (failure: VirtualScreenException) {
        errorResult(failure.code, failure.detail ?: failure.code)
    } catch (failure: IllegalArgumentException) {
        errorResult("INVALID_COMPONENT", failure.message ?: "组件无效")
    } catch (failure: Throwable) {
        errorResult("VIRTUAL_APP_LAUNCH_FAILED", failure.message ?: failure.javaClass.simpleName)
    }

    /** 文本走无障碍 ACTION_SET_TEXT；Root 引擎没有可用的文本注入实现。 */
    private fun writeText(value: String, replace: Boolean): AgentModelClient.ToolResult {
        val displayId = VirtualScreenSessionManager.currentDisplayId()
            ?: return errorResult("NO_VIRTUAL_SCREEN", "请先创建虚拟屏")
        val written = AgentAccessibilityService.current()
            ?.setVirtualScreenText(displayId, value, replace)
            ?: return errorResult("VIRTUAL_TEXT_NO_EDITOR", "未找到可编辑输入框：请先点击输入框，再重试 virtual_input")
        return textResult(
            JSONObject()
                .put("ok", true)
                .put("method", "accessibility")
                .put("text", written)
                .toString(),
        )
    }

    /** 模型可能只给包名；这里统一解析成引擎要求的 package/Activity。 */
    private fun resolveComponent(raw: String): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "launch_app 需要 component，请先调用 list_apps 获取" }
        if ('/' in value) {
            val component = ComponentName.unflattenFromString(value)
                ?: throw IllegalArgumentException("组件格式无效，请使用 list_apps 返回的 package/Activity")
            return component.flattenToString()
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(value)
            ?: throw IllegalArgumentException("找不到 $value 的启动 Activity，请先调用 list_apps 获取完整 component")
        return launchIntent.component?.flattenToString()
            ?: throw IllegalArgumentException("$value 没有可启动的 Activity")
    }

    private fun listApps(args: JSONObject): AgentModelClient.ToolResult {
        val query = args.optString("query").trim()
        val packageFilter = args.optString("package").trim()
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            manager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())
        val apps = resolved.asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .filter { packageFilter.isBlank() || it.activityInfo.packageName == packageFilter }
            .map {
                val label = runCatching {
                    it.loadLabel(manager).toString()
                }.getOrDefault(it.activityInfo.packageName)
                JSONObject()
                    .put("label", label)
                    .put("package", it.activityInfo.packageName)
                    .put(
                        "component",
                        "${it.activityInfo.packageName}/${it.activityInfo.name}",
                    )
            }
            .filter { query.isBlank() || it.optString("label").contains(query, true) || it.optString("package").contains(query, true) }
            .sortedBy { it.optString("label") }
            .take(100)
            .toList()
        return textResult(
            JSONObject()
                .put("ok", true)
                .put("applications", JSONArray(apps))
                .toString(),
        )
    }

    /** Runtime 只接受 data URL / http 引用，本地文件路径无法物化。 */
    private fun decodeScreenshot(base64: String?): AgentModelClient.ModelImage? {
        if (base64.isNullOrBlank()) return null
        val bytes = runCatching { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }.getOrNull()
            ?: return null
        if (bytes.isEmpty()) return null
        return runCatching { AgentImageCodec.fromScreenBytes(bytes, "virtual_screen", "image/jpeg") }.getOrNull()
    }

    private fun AgentAccessibilityService.UiNode.toJson(): JSONObject = JSONObject()
        .put("index", index)
        .put("text", text)
        .put("desc", desc)
        .put("class", className)
        .put("package", packageName)
        .put("view_id", viewId)
        .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
        .put("clickable", clickable)
        .put("long_clickable", longClickable)
        .put("scrollable", scrollable)
        .put("focused", focused)
        .put("editable", editable)
        .put("enabled", enabled)
        .put("checked", checked ?: JSONObject.NULL)
        .put("selected", selected)
        .put("hint", hint)

    private fun textResult(content: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content = content)

    private fun errorResult(code: String, message: String): AgentModelClient.ToolResult =
        textResult(JSONObject().put("ok", false).put("code", code).put("message", message).toString())

    companion object {
        val names = setOf(
            "virtual_display",
            "virtual_observe",
            "virtual_tap",
            "virtual_swipe",
            "virtual_input",
        )
    }
}

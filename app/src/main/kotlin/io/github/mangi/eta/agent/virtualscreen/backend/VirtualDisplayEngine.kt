package io.github.mangi.eta.agent.virtualscreen.backend

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Runs only as root/shell. No command accepts a caller-supplied target display. */
@SuppressLint("BlockedPrivateApi")
internal class VirtualDisplayEngine : AutoCloseable {
    private val context: Context = systemContext()
    private val manager = context.getSystemService(DisplayManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val frames = HandlerThread("virtual-screen-frames").apply { start() }
    private val handler = Handler(frames.looper)
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capture: VirtualScreenFrameCapture? = null
    private var input: RootInputInjector? = null
    private var rotation: VirtualScreenAppRotation? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeRenewed = 0L
    private var id = ""
    private val hostedTasks = mutableSetOf<Int>()
    /** 从手机主屏接管过来的任务；关闭虚拟屏前必须迁回主屏，避免被 DESTROY_CONTENT_ON_REMOVAL 销毁。 */
    private val adoptedTasks = mutableSetOf<Int>()
    private var primaryTaskId: Int? = null
    /** 接管前的手机主屏顶层任务；归还时恢复它，让用户回到原来的界面。 */
    private var restoreFocusTaskId: Int? = null
    private var allowOff = false
    @Volatile private var touched = SystemClock.elapsedRealtime()
    private var idleMinutes = 20
    private val idle = object : Runnable {
        override fun run() {
            if(display != null && allowOff && SystemClock.elapsedRealtime() - wakeRenewed >= 30_000) runCatching {
                wakeLock?.acquire(60_000); wakeRenewed = SystemClock.elapsedRealtime()
            }
            if (display != null && idleMinutes > 0 && SystemClock.elapsedRealtime() - touched > idleMinutes * 60_000L) Thread { releaseIfIdle() }.start()
            if (display != null) handler.postDelayed(this, 10_000)
        }
    }

    @Synchronized private fun releaseIfIdle() {
        // An input or a newly created display may arrive while this worker waits.
        // 接管的用户应用必须先归还主屏，归还失败就保留虚拟屏等待下一次重试。
        if(display != null && idleMinutes > 0 && SystemClock.elapsedRealtime()-touched > idleMinutes*60_000L) {
            if (returnAdoptedTasks()) release()
        }
    }

    @Synchronized fun create(request: JSONObject, output: Surface? = null): JSONObject {
        check(display == null) { "DISPLAY_ALREADY_ACTIVE" }
        val config = VirtualScreenConfig(request.getInt("width"), request.getInt("height"), request.getInt("density"),
            request.optInt("rotation"), request.optBoolean("allowScreenOff"), request.optInt("idleTimeoutMinutes", 20))
        try {
            var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH") or flag("VIRTUAL_DISPLAY_FLAG_TRUSTED") or
                flag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP") or flag("VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT") or
                flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL")
            if (Build.VERSION.SDK_INT >= 34) flags = flags or flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS") or flag("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED")
            if (config.allowScreenOff) flags = flags or flag("VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED")
            val surface = output ?: ImageReader.newInstance(config.width, config.height, PixelFormat.RGBA_8888, 3).also { reader = it }.surface
            // Legacy overload also works on Android 13; hidden flags are probed on each ROM.
            display = manager.createVirtualDisplay("eta-virtual-screen", config.width, config.height, config.density, surface, flags)
                ?: error("DISPLAY_CREATE_FAILED")
            handler.removeCallbacks(idle)
            handler.postDelayed(idle, 10_000)
            val current = display!!
            check(current.display.displayId > 0) { "INVALID_DISPLAY" }
            id = UUID.randomUUID().toString()
            reader?.let { capture = VirtualScreenFrameCapture(it, handler) { display?.display } }
            input = RootInputInjector(current.display.displayId)
            rotation = runCatching { VirtualScreenAppRotation(current.display, config.width > config.height) }.getOrNull()
            setRotation(config.rotation)
            if (config.allowScreenOff) {
                // Display-specific FULL_WAKE_LOCK is tested; never acquire a phone-display wake lock.
                val method = PowerManager::class.java.getMethod("newWakeLock", Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                wakeLock = (method.invoke(power, 0x1a, "VirtualScreen:display", current.display.displayId) as PowerManager.WakeLock).apply { setReferenceCounted(false); acquire(60_000) }
                wakeRenewed = SystemClock.elapsedRealtime()
            }
            allowOff = config.allowScreenOff
            idleMinutes = config.idleTimeoutMinutes
            touched = SystemClock.elapsedRealtime()
            return geometry().put("ok", true).put("handleId", id)
                .put("enginePid", Process.myPid())
                .put("independentFocus", Build.VERSION.SDK_INT >= 34).put("screenOff", config.allowScreenOff)
        } catch (failure: Throwable) { release(); throw failure }
    }

    @Synchronized fun execute(request: JSONObject): JSONObject {
        check(request.optString("handleId") == id && id.isNotBlank()) { "STALE_VIRTUAL_SCREEN" }
        val current = display ?: error("DISPLAY_GONE")
        check(manager.getDisplay(current.display.displayId) != null) { "DISPLAY_GONE" }
        val action = request.getString("action")
        if (action == "close") {
            // 用户不要主屏结果：把接管来的应用原样送回主屏，并恢复接管前的顶层任务。
            if (!returnAdoptedTasks()) return failed("RETURN_TO_MAIN_FAILED", "应用归还主屏失败，虚拟屏保持运行以便重试")
            release(); return ok().put("closed", true)
        }
        if (action == "handoff") {
            // 用户明确要求在主屏查看结果：把屏上的任务迁到主屏并留在前台。
            if (!showOnMain()) return failed("RETURN_TO_MAIN_FAILED", "应用迁移到主屏失败，虚拟屏保持运行以便重试")
            release(); return ok().put("closed", true).put("shownOnMain", true)
        }
        if (action == "cancel") { input?.cancelActive(); return ok() }
        if (action == "probe") return geometry().put("ok", true).put("interactive", power.isInteractive).put("locked", keyguard.isDeviceLocked)
        if (action == "set_idle_timeout") {
            idleMinutes = request.optInt("idleTimeoutMinutes", idleMinutes)
            require(idleMinutes in 0..240) { "INVALID_IDLE_TIMEOUT" }
            touched = SystemClock.elapsedRealtime()
            return ok()
        }
        touched = SystemClock.elapsedRealtime()
        check(allowOff || power.isInteractive && !keyguard.isDeviceLocked) { "SCREEN_OFF_UNAVAILABLE" }
        if (action != "launch") {
            val tasks = runningTasks()
            check(tasks.any { taskDisplay(it) == current.display.displayId } ||
                tasks.none { it.taskId in hostedTasks && taskDisplay(it) != current.display.displayId }) { "APP_MOVED_TO_PHONE" }
            if (action in setOf("observe", "validate")) {
                check(tasks.any { taskDisplay(it) == current.display.displayId && it.numActivities > 0 }) { "NO_RUNNING_APP" }
            }
        }
        if (action == "observe") {
            val frame = capture?.capture(2000) ?: error("DISPLAY_FRAME_PENDING")
            return geometry().put("ok", true).put("width", frame.width).put("height", frame.height)
                .put("rotation", frame.rotation).put("image", android.util.Base64.encodeToString(frame.jpeg, android.util.Base64.NO_WRAP))
                .put("fingerprint", frame.fingerprint.toString()).put("interactive",power.isInteractive)
        }
        rotation?.synchronize(runningTasks().firstOrNull { taskDisplay(it) == current.display.displayId })
        val geometry = geometry()
        if (request.has("expectedWidth")) check(request.getInt("expectedWidth") == geometry.getInt("width") &&
            request.getInt("expectedHeight") == geometry.getInt("height") && request.getInt("expectedRotation") == geometry.getInt("rotation")) { "STALE_OBSERVATION" }
        val width = geometry.getInt("width"); val height = geometry.getInt("height")
        fun coordinate(name: String, max: Int) = request.getInt(name).also { require(it in 0 until max) { "COORDINATE_OUT_OF_BOUNDS" } }
        if(action == "validate") return geometry.put("ok",true).put("interactive",power.isInteractive)
        val injector = input ?: error("INPUT_UNAVAILABLE")
        return when (action) {
            "tap" -> injector.execute("tap", coordinate("x", width).toString(), coordinate("y", height).toString())
            "long_press" -> {
                val x = coordinate("x", width).toString(); val y = coordinate("y", height).toString()
                val duration = request.optInt("durationMs", 600).also { require(it in 200..2000) { "INVALID_DURATION" } }
                injector.execute("swipe", x, y, x, y, duration.toString())
            }
            "swipe" -> {
                val values = request.getJSONArray("points")
                val points = List(values.length()) { n -> val p = values.getJSONArray(n); require(p.length() == 2); SwipePath.Point(p.getInt(0), p.getInt(1)) }
                SwipePath.validate(points, width, height)
                injector.executePath(points, request.optInt("durationMs", 300))
            }
            "scroll" -> {
                val x = coordinate("x", width); val y = coordinate("y", height)
                val delta = request.getInt("delta").also { require(it in -20..20 && it != 0) { "INVALID_SCROLL" } }
                val end = (y - delta * height / 8).coerceIn(0, height - 1)
                injector.execute("swipe", x.toString(), y.toString(), x.toString(), end.toString(), "300")
            }
            "key" -> {
                val key = request.getInt("keyCode").also { require(it in DisplayKeys.codes) { "KEY_UNSUPPORTED" } }
                injector.execute("keyevent", key.toString())
            }
            "text" -> error("VIRTUAL_TEXT_UNSUPPORTED")
            "launch" -> launch(request)
            else -> error("UNSUPPORTED_OPERATION")
        }
    }

    fun cancel() { input?.cancelActive() }

    private fun launch(request: JSONObject): JSONObject {
        val displayId = display!!.display.displayId
        val target = ComponentName.unflattenFromString(request.getString("component")) ?: error("INVALID_COMPONENT")
        val info = context.packageManager.getActivityInfo(target, 0)
        require(target.packageName != request.optString("ownerPackage")) { "APP_NOT_LAUNCHABLE" }
        require(info.enabled && info.exported) { "APP_NOT_LAUNCHABLE" }
        val resizeMode = ActivityInfo::class.java.getField("resizeMode").getInt(info)
        require(ActivityInfo::class.java.getDeclaredMethod("isResizeableMode", Int::class.javaPrimitiveType).invoke(null, resizeMode) == true) { "DISPLAY_APP_UNSUPPORTED" }
        fun matchesTarget(task: ActivityManager.RunningTaskInfo): Boolean =
            task.baseActivity?.packageName == target.packageName || task.topActivity?.packageName == target.packageName
        val elsewhere = runningTasks().firstOrNull { matchesTarget(it) && taskDisplay(it) != displayId }
        if (elsewhere != null) {
            // 已在运行的同一应用直接整任务接管，保留状态，避免 force-stop 重启。
            if (adopt(elsewhere, displayId)) {
                rotation?.apply(info.screenOrientation)
                return geometry().put("ok", true).put("component", target.flattenToString())
                    .put("adopted", true).put("task_id", elsewhere.taskId)
            }
            if (!request.optBoolean("restartApproved")) return JSONObject().put("ok", false).put("code", "APP_RESTART_CONFIRMATION_REQUIRED").put("packageName", target.packageName).put("component", target.flattenToString())
            check(info.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM == 0 && target.packageName != request.optString("ownerPackage")) { "APP_RESTART_UNSUPPORTED" }
            command(listOf("/system/bin/am", "force-stop", target.packageName))
            check(runningTasks().none { it.baseActivity?.packageName == target.packageName || it.topActivity?.packageName == target.packageName }) { "APP_STOP_FAILED" }
        }
        val uri = request.optString("uri")
        require(uri.length <= 8192 && '\u0000' !in uri) { "INVALID_URI" }
        // Hosted apps must stay private to the virtual display. If their task is
        // offered in the phone's Recents, opening it there migrates the task to the
        // default display and leaves the virtual display blank.
        val output = command(listOf("/system/bin/am", "start", "--display", displayId.toString(), "-f", "0x18800000", "-n", target.flattenToString()) +
            if (uri.isNotBlank()) listOf("-a", "android.intent.action.VIEW", "-d", uri) else emptyList())
        check("Error:" !in output && "Activity not started" !in output) { "DISPLAY_LAUNCH_REJECTED" }
        val tasks = runningTasks().filter(::matchesTarget)
        check(tasks.isNotEmpty() && tasks.all { taskDisplay(it) == displayId }) { "DISPLAY_LAUNCH_MISMATCH" }
        hostedTasks.addAll(tasks.map { it.taskId })
        primaryTaskId = tasks.first().taskId
        rotation?.apply(info.screenOrientation)
        return geometry().put("ok", true).put("component", target.flattenToString()).put("restarted", elsewhere != null)
    }

    /**
     * 接管手机主屏上已在运行的任务。只在虚拟屏与主屏尺寸/密度一致时执行，
     * 否则跨屏会触发 Activity 重建，违背“状态不变”的承诺，交回旧的重启流程。
     */
    private fun adopt(task: ActivityManager.RunningTaskInfo, displayId: Int): Boolean {
        if (task.numActivities == 0 || task.taskId <= 0) return false
        if (!matchesPhoneDisplay()) return false
        val packageName = task.baseActivity?.packageName ?: task.topActivity?.packageName ?: return false
        if (isHomePackage(packageName)) return false
        // 只有原本在后台的任务才需要归还后恢复原顶层；原来在前台的应用归还后应继续留在前台。
        val wasVisible = taskVisible(task)
        val previousTop = runningTasks().firstOrNull {
            taskDisplay(it) == 0 && it.taskId != task.taskId && taskVisible(it)
        } ?: runningTasks().firstOrNull { taskDisplay(it) == 0 && it.taskId != task.taskId }
        if (!moveTaskToDisplay(task.taskId, displayId)) return false
        hostedTasks.add(task.taskId)
        adoptedTasks.add(task.taskId)
        primaryTaskId = task.taskId
        if (!wasVisible && restoreFocusTaskId == null && previousTop != null) restoreFocusTaskId = previousTop.taskId
        return true
    }

    /** 归还接管任务：迁回主屏后恢复接管前的顶层任务，用户回到原来的界面。 */
    private fun returnAdoptedTasks(): Boolean {
        val displayId = display?.display?.displayId ?: return true
        for (taskId in adoptedTasks.toList()) {
            val task = runningTasks().firstOrNull { it.taskId == taskId } ?: continue
            if (taskDisplay(task) == displayId && !moveTaskToDisplay(taskId, 0)) return false
        }
        restoreFocusTaskId?.let(::focusTask)
        return true
    }

    /** 在主屏展示结果：把屏上所有带内容的任务迁回主屏，并让主任务保持前台。 */
    private fun showOnMain(): Boolean {
        val displayId = display?.display?.displayId ?: return true
        val tasks = runningTasks().filter { taskDisplay(it) == displayId && it.numActivities > 0 }
        if (tasks.isEmpty()) return true
        val primary = primaryTaskId?.takeIf { id -> tasks.any { it.taskId == id } } ?: tasks.first().taskId
        val ordered = tasks.filter { it.taskId != primary } + tasks.filter { it.taskId == primary }
        for (task in ordered) if (!moveTaskToDisplay(task.taskId, 0)) return false
        focusTask(primary)
        return true
    }

    private fun moveTaskToDisplay(taskId: Int, targetDisplayId: Int): Boolean {
        val output = runCatching {
            command(listOf("/system/bin/am", "display", "move-stack", taskId.toString(), targetDisplayId.toString()))
        }.getOrNull() ?: return false
        if ("Error:" in output || "Exception" in output) return false
        repeat(10) {
            val task = runningTasks().firstOrNull { it.taskId == taskId } ?: return true
            if (taskDisplay(task) == targetDisplayId) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun focusTask(taskId: Int) {
        val task = runningTasks().firstOrNull { it.taskId == taskId } ?: return
        if (taskDisplay(task) != 0) return
        runCatching { command(listOf("/system/bin/am", "task", "focus", taskId.toString())) }
    }

    private fun matchesPhoneDisplay(): Boolean {
        val current = display?.display ?: return false
        val main = manager.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return false
        val a = Point().also { current.getRealSize(it) }
        val b = Point().also { main.getRealSize(it) }
        val am = android.util.DisplayMetrics().also { current.getRealMetrics(it) }
        val bm = android.util.DisplayMetrics().also { main.getRealMetrics(it) }
        return a == b && am.densityDpi == bm.densityDpi
    }

    private fun isHomePackage(packageName: String): Boolean = runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == packageName
    }.getOrDefault(false)

    private fun setRotation(rotation: Int) {
        val service = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
        val type = Class.forName("android.view.IWindowManager")
        val method = type.methods.firstOrNull { it.name == "freezeDisplayRotation" && it.parameterCount in 2..3 } ?: error("ROTATION_UNAVAILABLE")
        val args: Array<Any> = if (method.parameterCount == 3) arrayOf(display!!.display.displayId, rotation, "android-virtual-screen") else arrayOf(display!!.display.displayId, rotation)
        method.invoke(service, *args)
    }

    private fun geometry(): JSONObject {
        val d = display?.display ?: error("DISPLAY_GONE")
        val size = Point().also { d.getRealSize(it) }
        return JSONObject().put("displayId", d.displayId).put("width", size.x).put("height", size.y).put("rotation", d.rotation)
    }

    @Synchronized private fun release() {
        handler.removeCallbacks(idle)
        input?.close(); input = null
        runCatching { rotation?.close() }; rotation = null
        runCatching { capture?.close() }; capture = null
        runCatching { display?.release() }; display = null
        runCatching { reader?.close() }; reader = null
        runCatching { wakeLock?.release() }; wakeLock = null
        id = ""
        hostedTasks.clear()
        adoptedTasks.clear()
        primaryTaskId = null
        restoreFocusTaskId = null
    }

    override fun close() {
        // 进程退出（App 被杀、管道关闭）时尽力把接管的用户应用送回主屏。
        runCatching { returnAdoptedTasks() }
        release(); handler.removeCallbacksAndMessages(null); frames.quitSafely()
    }

    companion object {
        fun systemContext(): Context {
            if (android.os.Looper.myLooper() == null) android.os.Looper.prepare()
            initializeApplicationSharedMemory()
            val type = Class.forName("android.app.ActivityThread")
            val thread = type.getMethod("systemMain").invoke(null)
            val system = type.getMethod("getSystemContext").invoke(thread) as Context
            // A normal Shizuku server runs as shell, not system/root. DisplayManager
            // validates the operation package against that actual Binder UID.
            return if (android.os.Process.myUid() == 2000)
                system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
            else system
        }
        @Synchronized private fun initializeApplicationSharedMemory() {
            // app_process does not receive the shared-memory singleton installed
            // by ActivityThread.bindApplication. Android 17's window service
            // reads it even when obtaining KeyguardManager. Older releases may
            // not have this class, and Shizuku may already have initialized it.
            val type = try { Class.forName("com.android.internal.os.ApplicationSharedMemory") }
                catch (_: ClassNotFoundException) { return }
            try {
                type.getMethod("getInstance").invoke(null)
            } catch (failure: java.lang.reflect.InvocationTargetException) {
                if (failure.targetException !is IllegalStateException) throw failure
                val memory = type.getMethod("create").invoke(null)
                type.getMethod("setInstance", type).invoke(null, memory)
            }
        }
        private fun flag(name: String) = runCatching { DisplayManager::class.java.getField(name).getInt(null) }.getOrDefault(0)
        private fun ok() = JSONObject().put("ok", true)
        private fun failed(code: String, detail: String) =
            JSONObject().put("ok", false).put("code", code).put("detail", detail)
        private fun taskVisible(task: ActivityManager.RunningTaskInfo): Boolean =
            runCatching { android.app.TaskInfo::class.java.getField("isVisible").getBoolean(task) }.getOrDefault(false)
        @Suppress("UNCHECKED_CAST") private fun runningTasks(): List<ActivityManager.RunningTaskInfo> {
            val type = Class.forName("android.app.ActivityTaskManager")
            return type.getMethod("getTasks", Int::class.javaPrimitiveType).invoke(type.getMethod("getInstance").invoke(null), 1000) as List<ActivityManager.RunningTaskInfo>
        }
        private fun taskDisplay(task: ActivityManager.RunningTaskInfo) = android.app.TaskInfo::class.java.getField("displayId").getInt(task)
        private fun command(args: List<String>): String {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            val data = java.io.ByteArrayOutputStream()
            val drain = Thread { p.inputStream.use { stream -> val buffer = ByteArray(4096); while (true) { val n = stream.read(buffer); if (n < 0) break; if (data.size() + n > 64_000) { p.destroy(); break }; data.write(buffer, 0, n) } } }.apply { isDaemon = true; start() }
            try { check(p.waitFor(8, TimeUnit.SECONDS)) { "DISPLAY_ACTION_TIMEOUT" }; drain.join(1000); check(p.exitValue() == 0) { "DISPLAY_ACTION_FAILED" }; return data.toString("UTF-8") }
            finally { p.destroy() }
        }
    }
}

package com.fran.teclas

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.IBinder
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Uses Shizuku's adb/root-privileged binder to hold apps in the docked top region.
 *
 * `ActivityOptions.launchBounds` only binds the task's root activity and is blocked outright on some
 * OEMs, so it can't keep an app pinned across in-app navigation (Spotify's now-playing screen) or
 * work at all on MagicOS. With Shizuku we call the privileged IActivityTaskManager directly to force
 * the foreground task into freeform mode at our exact bounds, and re-apply it whenever the app
 * resizes/escapes. Everything degrades gracefully: if Shizuku isn't running or permission isn't
 * granted, [pin] is a no-op and the launcher falls back to plain launchBounds.
 */
internal object ShizukuPinner {
    private const val TAG = "ShizukuPinner"
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val RESIZE_MODE_SYSTEM = 0
    private const val SHIZUKU_PERMISSION_REQUEST = 4919

    @Volatile private var exemptionsApplied = false
    @Volatile private var cachedAtm: Any? = null

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isReady(): Boolean = isRunning() && hasPermission()

    /** Ask Shizuku for permission (the Shizuku app shows the approval dialog). */
    fun requestPermission(onResult: (granted: Boolean) -> Unit) {
        if (!isRunning()) { onResult(false); return }
        if (hasPermission()) { onResult(true); return }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST) }
            .onFailure { Shizuku.removeRequestPermissionResultListener(listener); onResult(false) }
    }

    /**
     * Force the foreground task of [packageName] into freeform at [bounds]. Safe to call repeatedly
     * (e.g. on every window change) — it's how the app is held in the top region across navigation.
     * Returns true if the pin calls went through.
     */
    fun pin(packageName: String, bounds: Rect): Boolean {
        if (!isReady()) return false
        return runCatching {
            ensureExemptions()
            val atm = activityTaskManager() ?: return false
            val taskId = focusedTaskId(atm, packageName) ?: return false
            setWindowingMode(atm, taskId, WINDOWING_MODE_FREEFORM)
            resizeTask(atm, taskId, bounds)
            true
        }.onFailure { Log.w(TAG, "pin failed", it) }.getOrDefault(false)
    }

    private fun ensureExemptions() {
        if (exemptionsApplied) return
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        exemptionsApplied = true
    }

    private fun activityTaskManager(): Any? {
        cachedAtm?.let { return it }
        return runCatching {
            val raw: IBinder = SystemServiceHelper.getSystemService("activity_task")
            val wrapped = ShizukuBinderWrapper(raw)
            val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, wrapped).also { cachedAtm = it }
        }.onFailure { Log.w(TAG, "activity_task binder failed", it) }.getOrNull()
    }

    /** Find the task whose top/base activity belongs to [packageName]. */
    private fun focusedTaskId(atm: Any, packageName: String): Int? {
        val tasks = runningTasks(atm) ?: return null
        for (info in tasks) {
            if (info == null) continue
            val pkg = taskPackage(info) ?: continue
            if (pkg == packageName) return taskField(info, "taskId")
        }
        return null
    }

    private fun runningTasks(atm: Any): List<*>? {
        val cls = atm.javaClass
        // getTasks signature has drifted across releases; try the known shapes.
        val attempts: List<() -> Any?> = listOf(
            { cls.getMethod("getTasks", Int::class.javaPrimitiveType).invoke(atm, 10) },
            { cls.getMethod("getTasks", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(atm, 10, false, false) },
            { cls.getMethod("getTasks", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(atm, 10, false, false, -1) },
        )
        for (attempt in attempts) {
            val result = runCatching { attempt() }.getOrNull()
            if (result is List<*>) return result
        }
        return null
    }

    private fun taskPackage(info: Any?): String? {
        info ?: return null
        val top = taskComponent(info, "topActivity")
        val base = taskComponent(info, "baseActivity")
        return top?.packageName ?: base?.packageName
    }

    private fun taskComponent(info: Any, field: String): ComponentName? =
        runCatching { info.javaClass.getField(field).get(info) as? ComponentName }.getOrNull()

    private fun taskField(info: Any, field: String): Int? =
        runCatching { info.javaClass.getField(field).getInt(info) }.getOrNull()

    private fun setWindowingMode(atm: Any, taskId: Int, mode: Int) {
        runCatching {
            atm.javaClass.getMethod(
                "setTaskWindowingMode",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            ).invoke(atm, taskId, mode, false)
        }.onFailure { Log.w(TAG, "setTaskWindowingMode failed", it) }
    }

    private fun resizeTask(atm: Any, taskId: Int, bounds: Rect) {
        runCatching {
            atm.javaClass.getMethod(
                "resizeTask",
                Int::class.javaPrimitiveType, Rect::class.java, Int::class.javaPrimitiveType
            ).invoke(atm, taskId, bounds, RESIZE_MODE_SYSTEM)
        }.onFailure { Log.w(TAG, "resizeTask failed", it) }
    }
}

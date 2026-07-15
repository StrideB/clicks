package com.fran.teclas

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode

class TeclasApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Warm the heavyweight prefs files off the main thread: SharedPreferences blocks the
        // first reader until the XML is parsed, and the "teclas" file (themes, learned models)
        // cost a StrictMode-measured 1.6 s inside MainActivity.onCreate on a cold start.
        Thread({
            getSharedPreferences("teclas", MODE_PRIVATE)
            getSharedPreferences("pro", MODE_PRIVATE)
            getSharedPreferences("account_auth", MODE_PRIVATE)
        }, "prefs-warm").start()
        // Debug-only tripwire: log-only StrictMode so main-thread disk/network work and VM-level
        // leaks (unclosed Closeables, leaked registrations) show up in logcat during development.
        // Log penalties only — never crash — and completely inert in release builds.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }
    }
}

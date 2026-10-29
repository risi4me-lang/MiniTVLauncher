package com.mini.tvlauncher

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {
    private var isInstalled = false

    fun install(context: Context) {
        if (isInstalled) return
        isInstalled = true

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        val appContext = context.applicationContext

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrashToFile(appContext, thread, throwable)
            } catch (_: Exception) {}

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrashToFile(context: Context, thread: Thread, throwable: Throwable) {
        val crashFile = File(context.filesDir, "last_crash.txt")
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.US)
        val timestamp = sdf.format(Date())

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val newRecord = "=== Crash at $timestamp (Thread: ${thread.name}) ===\n$stackTrace\n"

        val existingRecords = if (crashFile.exists()) {
            crashFile.readText().split("=== Crash at ").filter { it.isNotBlank() }.map { "=== Crash at $it" }
        } else emptyList()

        val updatedRecords = (listOf(newRecord) + existingRecords).take(5)
        crashFile.writeText(updatedRecords.joinToString("\n"))
    }
}

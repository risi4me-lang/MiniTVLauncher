package com.mini.tvlauncher

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LoggedKeyEvent(
    val keyCode: Int,
    val keyName: String,
    val scanCode: Int,
    val deviceName: String,
    val action: String,
    val timestampMs: Long
)

object KeyEventBuffer {
    private const val MAX_SIZE = 30
    private val buffer = ArrayList<LoggedKeyEvent>()

    @Synchronized
    fun add(event: KeyEvent, deviceNameOverride: String? = null) {
        val name = KeyEvent.keyCodeToString(event.keyCode)
        val dev = deviceNameOverride ?: event.device?.name ?: "Unknown Device"
        val act = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        val entry = LoggedKeyEvent(
            keyCode = event.keyCode,
            keyName = name,
            scanCode = event.scanCode,
            deviceName = dev,
            action = act,
            timestampMs = System.currentTimeMillis()
        )

        if (buffer.size >= MAX_SIZE) {
            buffer.removeAt(0)
        }
        buffer.add(entry)
    }

    @Synchronized
    fun clear() {
        buffer.clear()
    }

    @Synchronized
    fun getList(): List<LoggedKeyEvent> = ArrayList(buffer)

    @Synchronized
    fun getJson(): String {
        val arr = JSONArray()
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        for (e in buffer) {
            arr.put(JSONObject().apply {
                put("keyCode", e.keyCode)
                put("keyName", e.keyName)
                put("scanCode", e.scanCode)
                put("deviceName", e.deviceName)
                put("action", e.action)
                put("time", sdf.format(Date(e.timestampMs)))
                put("timestampMs", e.timestampMs)
            })
        }
        return arr.toString()
    }

    @Synchronized
    fun getFormattedLines(): List<String> {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return buffer.map { e ->
            "[${sdf.format(Date(e.timestampMs))}] KeyCode ${e.keyCode} (${e.keyName}), scanCode=${e.scanCode}, action=${e.action}, device='${e.deviceName}'"
        }
    }
}

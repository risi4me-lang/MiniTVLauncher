package com.mini.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

interface RemoteHost {
    fun apps(): List<AppEntry>
    fun sendKey(name: String): Boolean
    fun launchPkg(pkg: String): Boolean
    fun saveWallpaper(bytes: ByteArray): Boolean
    fun pageHtml(): ByteArray
    fun getSettingsJson(): String
    fun updateSettings(jsonStr: String): Boolean
    fun exchangeToken(token: String): String?
    fun isValidKey(key: String): Boolean
    fun adjustVolume(action: String): Boolean
    fun typeText(text: String): Boolean
    fun getFavoritesJson(): String
    fun updateFavorite(pkg: String, pinned: Boolean): Boolean
    fun getHiddenJson(): String
    fun updateHidden(pkg: String, hidden: Boolean): Boolean
    fun openLink(url: String): String?
    fun performPower(): String?
    fun renameApp(pkg: String, newName: String): Boolean
    fun saveCardImage(pkg: String, bytes: ByteArray): Boolean
    fun resetCardImage(pkg: String): Boolean
    fun resetUsageStats(): Boolean
}

/** Tiny dependency-free HTTP server. Access requires valid device key or PIN. */
class RemoteServer(private val pin: String, private val host: RemoteHost) {
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    var port = 0
        private set

    fun start(): Boolean {
        for (p in 8080..8090) {
            try { server = ServerSocket(p); port = p; break } catch (_: IOException) {}
        }
        val ss = server ?: return false
        Thread {
            while (!ss.isClosed) {
                try { val s = ss.accept(); pool.execute { handle(s) } } catch (_: IOException) { break }
            }
        }.apply { isDaemon = true; start() }
        return true
    }

    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        pool.shutdownNow()
    }

    private fun readLine(i: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split("&").filter { it.contains("=") }.associate {
            URLDecoder.decode(it.substringBefore("="), "UTF-8") to
                URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }

    private fun reply(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\n\r\n"
        out.write(head.toByteArray()); out.write(body); out.flush()
    }

    private fun handle(s: Socket) {
        s.use {
            try {
                s.soTimeout = 8000
                val inp = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val reqLine = readLine(inp) ?: return
                val parts = reqLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val target = parts[1]
                var len = 0
                var reqHeaderKey: String? = null

                while (true) {
                    val h = readLine(inp) ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("content-length:", true))
                        len = h.substringAfter(":").trim().toIntOrNull() ?: 0
                    if (h.startsWith("x-device-key:", true))
                        reqHeaderKey = h.substringAfter(":").trim()
                }
                val path = target.substringBefore("?")
                val q = parseQuery(target.substringAfter("?", ""))

                if (path == "/") {
                    val t = q["t"]
                    if (!t.isNullOrEmpty()) {
                        val newKey = host.exchangeToken(t)
                        val html = String(host.pageHtml(), Charsets.UTF_8)
                        val injected = if (newKey != null) {
                            "<script>window.PAIRED_KEY=\"$newKey\";</script>$html"
                        } else {
                            "<script>alert('Pairing token invalid or expired.');</script>$html"
                        }
                        reply(out, 200, "text/html; charset=utf-8", injected.toByteArray(Charsets.UTF_8))
                        return
                    }
                    reply(out, 200, "text/html; charset=utf-8", host.pageHtml())
                    return
                }

                // Auth check: device key OR PIN
                val providedKey = q["key"] ?: reqHeaderKey
                val providedPin = q["pin"]
                val isAuth = (providedKey != null && host.isValidKey(providedKey)) || (providedPin == pin)

                if (!isAuth) {
                    reply(out, 401, "text/plain", "bad auth".toByteArray())
                    return
                }

                when (path) {
                    "/apps" -> {
                        val arr = JSONArray()
                        host.apps().forEach {
                            arr.put(JSONObject().apply {
                                put("defaultLabel", it.defaultLabel)
                                put("label", it.label)
                                put("pkg", it.pkg)
                                put("launches", it.launchCount)
                                put("hasCustomImage", it.hasCustomImage)
                            })
                        }
                        reply(out, 200, "application/json", arr.toString().toByteArray())
                    }
                    "/key" -> reply(out, if (host.sendKey(q["k"] ?: "")) 200 else 400, "text/plain", ByteArray(0))
                    "/volume" -> {
                        val act = q["action"] ?: q["a"] ?: q["v"] ?: ""
                        reply(out, if (host.adjustVolume(act)) 200 else 400, "text/plain", ByteArray(0))
                    }
                    "/type" -> {
                        val txt = q["text"] ?: q["t"] ?: ""
                        reply(out, if (host.typeText(txt)) 200 else 400, "text/plain", ByteArray(0))
                    }
                    "/openlink" -> {
                        var url = q["url"] ?: q["link"] ?: q["u"] ?: ""
                        if (len in 1..100_000) {
                            val b = ByteArray(len)
                            var n = 0
                            while (n < len) { val r = inp.read(b, n, len - n); if (r < 0) break; n += r }
                            val bodyStr = String(b, Charsets.UTF_8).trim()
                            if (bodyStr.startsWith("http://", true) || bodyStr.startsWith("https://", true)) {
                                url = bodyStr
                            } else {
                                try {
                                    val obj = JSONObject(bodyStr)
                                    if (obj.has("url")) url = obj.getString("url")
                                } catch (_: Exception) {}
                            }
                        }
                        val err = host.openLink(url)
                        if (err == null) {
                            reply(out, 200, "text/plain", "ok".toByteArray())
                        } else {
                            reply(out, 400, "text/plain", err.toByteArray(Charsets.UTF_8))
                        }
                    }
                    "/power" -> {
                        val err = host.performPower()
                        if (err == null) {
                            reply(out, 200, "text/plain", "ok".toByteArray())
                        } else {
                            reply(out, 400, "text/plain", err.toByteArray(Charsets.UTF_8))
                        }
                    }
                    "/rename" -> {
                        var pkg = q["pkg"] ?: ""
                        var name = q["name"] ?: ""
                        if (len in 1..100_000) {
                            val b = ByteArray(len)
                            var n = 0
                            while (n < len) { val r = inp.read(b, n, len - n); if (r < 0) break; n += r }
                            try {
                                val obj = JSONObject(String(b, Charsets.UTF_8))
                                if (obj.has("pkg")) pkg = obj.getString("pkg")
                                if (obj.has("name")) name = obj.getString("name")
                            } catch (_: Exception) {}
                        }
                        val ok = host.renameApp(pkg, name)
                        reply(out, if (ok) 200 else 400, "application/json", "{\"status\":\"ok\"}".toByteArray())
                    }
                    "/cardimage" -> {
                        val pkg = q["pkg"] ?: ""
                        val action = q["action"] ?: ""
                        val ok = if (action == "reset") {
                            host.resetCardImage(pkg)
                        } else {
                            if (len <= 0 || len > 8_000_000) { reply(out, 413, "text/plain", ByteArray(0)); return }
                            val buf = ByteArray(len)
                            var n = 0
                            while (n < len) { val r = inp.read(buf, n, len - n); if (r < 0) break; n += r }
                            host.saveCardImage(pkg, buf)
                        }
                        reply(out, if (ok) 200 else 400, "application/json", "{\"status\":\"ok\"}".toByteArray())
                    }
                    "/resetusage" -> {
                        val ok = host.resetUsageStats()
                        reply(out, if (ok) 200 else 400, "application/json", "{\"status\":\"ok\"}".toByteArray())
                    }
                    "/launch" -> reply(out, if (host.launchPkg(q["pkg"] ?: "")) 200 else 404, "text/plain", ByteArray(0))
                    "/wallpaper" -> {
                        if (len <= 0 || len > 8_000_000) { reply(out, 413, "text/plain", ByteArray(0)); return }
                        val buf = ByteArray(len)
                        var n = 0
                        while (n < len) { val r = inp.read(buf, n, len - n); if (r < 0) break; n += r }
                        reply(out, if (n == len && host.saveWallpaper(buf)) 200 else 400, "text/plain", ByteArray(0))
                    }
                    "/favorites" -> {
                        if (method == "GET") {
                            reply(out, 200, "application/json", host.getFavoritesJson().toByteArray(Charsets.UTF_8))
                        } else if (method == "POST") {
                            var pkg = q["pkg"] ?: ""
                            var pinned = q["action"] == "pin" || q["pinned"] == "true"
                            if (len in 1..100_000) {
                                val b = ByteArray(len)
                                var n = 0
                                while (n < len) { val r = inp.read(b, n, len - n); if (r < 0) break; n += r }
                                try {
                                    val obj = JSONObject(String(b, Charsets.UTF_8))
                                    if (obj.has("pkg")) pkg = obj.getString("pkg")
                                    if (obj.has("pinned")) pinned = obj.getBoolean("pinned")
                                    if (obj.has("action")) pinned = obj.getString("action") == "pin"
                                } catch (_: Exception) {}
                            }
                            val ok = host.updateFavorite(pkg, pinned)
                            reply(out, if (ok) 200 else 400, "application/json", "{\"status\":\"ok\"}".toByteArray())
                        } else {
                            reply(out, 405, "text/plain", ByteArray(0))
                        }
                    }
                    "/hidden" -> {
                        if (method == "GET") {
                            reply(out, 200, "application/json", host.getHiddenJson().toByteArray(Charsets.UTF_8))
                        } else if (method == "POST") {
                            var pkg = q["pkg"] ?: ""
                            var hidden = q["action"] == "hide" || q["hidden"] == "true"
                            if (q["action"] == "unhide" || q["hidden"] == "false") hidden = false
                            if (len in 1..100_000) {
                                val b = ByteArray(len)
                                var n = 0
                                while (n < len) { val r = inp.read(b, n, len - n); if (r < 0) break; n += r }
                                try {
                                    val obj = JSONObject(String(b, Charsets.UTF_8))
                                    if (obj.has("pkg")) pkg = obj.getString("pkg")
                                    if (obj.has("hidden")) hidden = obj.getBoolean("hidden")
                                    if (obj.has("action")) hidden = obj.getString("action") == "hide"
                                } catch (_: Exception) {}
                            }
                            val ok = host.updateHidden(pkg, hidden)
                            reply(out, if (ok) 200 else 400, "application/json", "{\"status\":\"ok\"}".toByteArray())
                        } else {
                            reply(out, 405, "text/plain", ByteArray(0))
                        }
                    }
                    "/settings" -> {
                        if (method == "GET") {
                            reply(out, 200, "application/json", host.getSettingsJson().toByteArray(Charsets.UTF_8))
                        } else if (method == "POST") {
                            val bodyBuf = if (len in 1..100_000) {
                                val b = ByteArray(len)
                                var n = 0
                                while (n < len) { val r = inp.read(b, n, len - n); if (r < 0) break; n += r }
                                b
                            } else ByteArray(0)
                            val bodyStr = String(bodyBuf, Charsets.UTF_8)
                            val ok = host.updateSettings(bodyStr)
                            reply(out, if (ok) 200 else 400, "application/json", "{\"status\":\"ok\"}".toByteArray())
                        } else {
                            reply(out, 405, "text/plain", ByteArray(0))
                        }
                    }
                    else -> reply(out, 404, "text/plain", ByteArray(0))
                }
            } catch (_: Exception) {}
        }
    }
}

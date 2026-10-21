package com.mini.tvlauncher

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class QrPairingManager(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val homeQrCard: View,
    private val homeQrImageView: ImageView,
    private val homeQrCaption: TextView,
    private val homeQrOfflineText: TextView,
    private val pairingPanel: View,
    private val pairingInfo: TextView,
    private val qrImageView: ImageView,
    private val pairedDevicesContainer: LinearLayout,
    private val noDevicesText: TextView,
    private val closePanelBtn: View,
    private val removeAllBtn: View,
    private val onPairedDevicesChanged: () -> Unit
) {
    @Volatile private var activeToken: String? = null
    @Volatile private var activeTokenExpiry: Long = 0L

    private var homeQrBitmap: Bitmap? = null
    private var currentQrBitmap: Bitmap? = null

    private val qrHandler = Handler(Looper.getMainLooper())
    private val qrRefreshRunnable = object : Runnable {
        override fun run() {
            refreshHomeQrCode()
            qrHandler.postDelayed(this, 90_000L)
        }
    }

    init {
        homeQrCard.setOnClickListener { openPairingPanel() }
        homeQrCard.setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
        }

        closePanelBtn.apply {
            setOnClickListener { closePairingPanel() }
            setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
            }
        }

        removeAllBtn.apply {
            setOnClickListener { removeAllPairedDevices() }
            setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
            }
        }
    }

    fun start() {
        qrHandler.removeCallbacks(qrRefreshRunnable)
        qrHandler.post(qrRefreshRunnable)
    }

    fun stop() {
        qrHandler.removeCallbacks(qrRefreshRunnable)
        homeQrBitmap?.recycle()
        homeQrBitmap = null
        currentQrBitmap?.recycle()
        currentQrBitmap = null
    }

    fun isPairingPanelOpen(): Boolean = pairingPanel.visibility == View.VISIBLE

    fun refreshHomeQrCode() {
        val token = generatePairingToken()
        val ip = localIp()
        val port = RemoteService.serverPort.takeIf { it != 0 } ?: 8080

        updateHomeQrVisibility()

        if (ip == null) {
            homeQrImageView.visibility = View.GONE
            homeQrCaption.visibility = View.GONE
            homeQrOfflineText.visibility = View.VISIBLE
        } else {
            homeQrImageView.visibility = View.VISIBLE
            homeQrCaption.visibility = View.VISIBLE
            homeQrOfflineText.visibility = View.GONE

            val url = "http://$ip:$port/?t=$token"
            val newBmp = generateQrBitmap(url, 240)
            if (newBmp != null) {
                val oldBmp = homeQrBitmap
                homeQrBitmap = newBmp
                homeQrImageView.setImageBitmap(newBmp)
                oldBmp?.recycle()
            }
        }
    }

    fun updateHomeQrVisibility() {
        val mode = prefs.getString("home_qr_mode", "always") ?: "always"
        val hasPaired = getPairedDevicesList().isNotEmpty()

        val isVisible = when (mode) {
            "off" -> false
            "until_paired" -> !hasPaired
            else -> true
        }

        homeQrCard.visibility = if (isVisible) View.VISIBLE else View.GONE
    }

    fun openPairingPanel() {
        val token = generatePairingToken()
        val ip = localIp() ?: "127.0.0.1"
        val port = RemoteService.serverPort.takeIf { it != 0 } ?: 8080
        val url = "http://$ip:$port/?t=$token"

        pairingInfo.text = "Scan QR code or open in phone browser:\nhttp://$ip:$port   PIN: ${pin()}"

        currentQrBitmap?.recycle()
        currentQrBitmap = generateQrBitmap(url, 300)
        qrImageView.setImageBitmap(currentQrBitmap)

        refreshPairedDevicesUi()
        pairingPanel.visibility = View.VISIBLE
        closePanelBtn.requestFocus()
    }

    fun closePairingPanel() {
        activeToken = null
        currentQrBitmap?.recycle()
        currentQrBitmap = null
        qrImageView.setImageBitmap(null)
        pairingPanel.visibility = View.GONE
        refreshHomeQrCode()
    }

    private fun generatePairingToken(): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        activeToken = token
        activeTokenExpiry = System.currentTimeMillis() + 120_000L
        return token
    }

    fun exchangeToken(token: String): String? {
        val current = System.currentTimeMillis()
        val validToken = activeToken
        if (validToken != null && validToken == token && current <= activeTokenExpiry) {
            activeToken = null
            val deviceKey = UUID.randomUUID().toString()
            val hash = hashKey(deviceKey)
            val name = "Phone (" + SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date()) + ")"
            addPairedDevice(hash, name)
            activity.runOnUiThread { refreshHomeQrCode() }
            return deviceKey
        }
        return null
    }

    fun getPairedDevicesList(): List<JSONObject> {
        val jsonStr = prefs.getString("paired_devices_json", "[]") ?: "[]"
        return try {
            val arr = JSONArray(jsonStr)
            val list = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) {
                list.add(arr.getJSONObject(i))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savePairedDevicesList(list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("paired_devices_json", arr.toString()).apply()
        activity.runOnUiThread {
            refreshPairedDevicesUi()
            updateHomeQrVisibility()
            onPairedDevicesChanged()
        }
    }

    private fun addPairedDevice(hash: String, name: String) {
        val list = getPairedDevicesList().toMutableList()
        val id = UUID.randomUUID().toString()
        val obj = JSONObject().apply {
            put("id", id)
            put("hash", hash)
            put("name", name)
            put("pairedAt", System.currentTimeMillis())
        }
        list.add(obj)
        savePairedDevicesList(list)
    }

    private fun removePairedDevice(id: String) {
        val list = getPairedDevicesList().filter { it.optString("id") != id }
        savePairedDevicesList(list)
    }

    private fun removeAllPairedDevices() {
        savePairedDevicesList(emptyList())
    }

    private fun refreshPairedDevicesUi() {
        pairedDevicesContainer.removeAllViews()

        val devices = getPairedDevicesList()
        if (devices.isEmpty()) {
            noDevicesText.visibility = View.VISIBLE
        } else {
            noDevicesText.visibility = View.GONE
            val inflater = LayoutInflater.from(activity)
            for (dev in devices) {
                val id = dev.optString("id")
                val name = dev.optString("name", "Paired Phone")
                val item = inflater.inflate(R.layout.item_paired_device, pairedDevicesContainer, false)
                item.findViewById<TextView>(R.id.deviceName).text = name
                val removeBtn = item.findViewById<View>(R.id.removeBtn)
                removeBtn.setOnClickListener { removePairedDevice(id) }
                removeBtn.setOnFocusChangeListener { v, focused ->
                    v.animate().scaleX(if (focused) 1.08f else 1.0f).scaleY(if (focused) 1.08f else 1.0f).setDuration(160).start()
                }
                pairedDevicesContainer.addView(item)
            }
        }
    }

    private fun generateQrBitmap(contents: String, size: Int = 300): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(contents, BarcodeFormat.QR_CODE, size, size)
            val w = bitMatrix.width
            val h = bitMatrix.height
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            for (x in 0 until w) {
                for (y in 0 until h) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun pin(): String {
        var p = prefs.getString("pin", null)
        if (p == null) {
            p = (1000 + java.util.Random().nextInt(9000)).toString()
            prefs.edit().putString("pin", p).apply()
        }
        return p
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress
    } catch (_: Exception) { null }

    private fun hashKey(key: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

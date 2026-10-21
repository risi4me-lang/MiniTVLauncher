package com.mini.tvlauncher

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.widget.ImageView

class NetworkIndicator(
    private val activity: Activity,
    private val iconView: ImageView,
    private val onNetworkStatusChanged: () -> Unit = {}
) {
    private val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworkStatus()
            onNetworkStatusChanged()
        }

        override fun onLost(network: Network) {
            updateNetworkStatus()
            onNetworkStatusChanged()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            updateNetworkStatus()
        }
    }

    fun start() {
        cm?.let {
            try {
                val request = NetworkRequest.Builder().build()
                it.registerNetworkCallback(request, networkCallback)
            } catch (_: Exception) {}
        }
        updateNetworkStatus()
    }

    fun stop() {
        cm?.let {
            try {
                it.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {}
        }
    }

    fun updateNetworkStatus() {
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }

        val resId = when {
            caps == null -> R.drawable.ic_net_offline
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.drawable.ic_net_wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.drawable.ic_net_ethernet
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.drawable.ic_net_wifi
            else -> R.drawable.ic_net_offline
        }

        activity.runOnUiThread {
            iconView.setImageResource(resId)
        }
    }
}

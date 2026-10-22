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
            activity.runOnUiThread {
                updateNetworkStatus()
                try {
                    onNetworkStatusChanged()
                } catch (_: Exception) {}
            }
        }

        override fun onLost(network: Network) {
            activity.runOnUiThread {
                updateNetworkStatus()
                try {
                    onNetworkStatusChanged()
                } catch (_: Exception) {}
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            activity.runOnUiThread {
                updateNetworkStatus()
            }
        }
    }

    fun start() {
        try {
            cm?.let {
                val request = NetworkRequest.Builder().build()
                it.registerNetworkCallback(request, networkCallback)
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        updateNetworkStatus()
    }

    fun stop() {
        try {
            cm?.let {
                it.unregisterNetworkCallback(networkCallback)
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    fun updateNetworkStatus() {
        val resId = try {
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> R.drawable.ic_net_offline
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.drawable.ic_net_wifi
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.drawable.ic_net_ethernet
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.drawable.ic_net_wifi
                else -> R.drawable.ic_net_offline
            }
        } catch (_: SecurityException) {
            R.drawable.ic_net_offline
        } catch (_: Exception) {
            R.drawable.ic_net_offline
        }

        activity.runOnUiThread {
            try {
                iconView.setImageResource(resId)
            } catch (_: Exception) {}
        }
    }
}

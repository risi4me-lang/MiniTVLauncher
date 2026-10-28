package com.mini.tvlauncher

import android.content.ComponentName
import android.service.notification.NotificationListenerService

class MediaListenerService : NotificationListenerService() {
    companion object {
        fun getComponentName(context: android.content.Context): ComponentName {
            return ComponentName(context, MediaListenerService::class.java)
        }
    }
}

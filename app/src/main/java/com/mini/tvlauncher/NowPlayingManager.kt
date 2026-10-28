package com.mini.tvlauncher

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import org.json.JSONObject
import java.lang.ref.WeakReference

data class NowPlayingState(
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val pkg: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastUpdatedTime: Long = SystemClock.elapsedRealtime()
)

class NowPlayingManager(private val context: Context) {

    companion object {
        @Volatile var activeController: WeakReference<MediaController>? = null
            private set

        fun isNotificationListenerEnabled(context: Context): Boolean {
            val pkgName = context.packageName
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            return flat != null && flat.contains(pkgName)
        }
    }

    private val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private var currentController: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateState()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateState()
        }

        override fun onSessionDestroyed() {
            unbindController()
            updateState()
        }
    }

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        val primary = controllers?.firstOrNull()
        bindController(primary)
        updateState()
    }

    @Volatile var currentState: NowPlayingState = NowPlayingState()
        private set

    fun start() {
        try {
            val component = MediaListenerService.getComponentName(context)
            sessionManager?.addOnActiveSessionsChangedListener(activeSessionsListener, component)
            val controllers = sessionManager?.getActiveSessions(component)
            bindController(controllers?.firstOrNull())
            updateState()
        } catch (_: Exception) {}
    }

    fun stop() {
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (_: Exception) {}
        unbindController()
    }

    private fun bindController(controller: MediaController?) {
        if (currentController == controller) return
        unbindController()
        currentController = controller
        activeController = if (controller != null) WeakReference(controller) else null
        controller?.registerCallback(callback)
    }

    private fun unbindController() {
        currentController?.unregisterCallback(callback)
        currentController = null
        activeController = null
    }

    private fun updateState() {
        val ctrl = currentController
        if (ctrl == null) {
            currentState = NowPlayingState()
            return
        }

        val playbackState = ctrl.playbackState
        val metadata = ctrl.metadata

        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ctrl.packageName

        var pos = playbackState?.position ?: 0L
        if (isPlaying && playbackState != null && playbackState.lastPositionUpdateTime > 0) {
            val timeDiff = SystemClock.elapsedRealtime() - playbackState.lastPositionUpdateTime
            pos += (timeDiff * playbackState.playbackSpeed).toLong()
        }

        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        currentState = NowPlayingState(
            isPlaying = isPlaying,
            title = title,
            artist = artist,
            pkg = ctrl.packageName,
            positionMs = pos.coerceAtLeast(0L),
            durationMs = duration.coerceAtLeast(0L),
            lastUpdatedTime = SystemClock.elapsedRealtime()
        )
    }

    fun getNowPlayingJson(): String {
        updateState()
        val hasAccess = isNotificationListenerEnabled(context)
        val state = currentState

        val obj = JSONObject().apply {
            put("mediaAccessEnabled", hasAccess)
            put("isPlaying", state.isPlaying)
            put("title", state.title)
            put("artist", state.artist)
            put("pkg", state.pkg)
            put("positionMs", state.positionMs)
            put("durationMs", state.durationMs)
        }
        return obj.toString()
    }

    fun handleMediaAction(action: String, posParam: String?): Boolean {
        val ctrl = currentController
        val act = action.lowercase()

        if (ctrl != null) {
            val controls = ctrl.transportControls
            when (act) {
                "play" -> controls.play()
                "pause" -> controls.pause()
                "toggle" -> {
                    if (currentState.isPlaying) controls.pause() else controls.play()
                }
                "next" -> controls.skipToNext()
                "prev" -> controls.skipToPrevious()
                "seek_fwd" -> controls.seekTo(currentState.positionMs + 10_000L)
                "seek_back" -> controls.seekTo((currentState.positionMs - 10_000L).coerceAtLeast(0L))
                "seek_to" -> {
                    val pos = posParam?.toLongOrNull() ?: 0L
                    controls.seekTo(pos)
                }
                else -> return false
            }
            return true
        }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val keyCode = when (act) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "seek_fwd" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "seek_back" -> KeyEvent.KEYCODE_MEDIA_REWIND
            else -> return false
        }

        val eventDown = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keyCode, 0)
        val eventUp = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
        am.dispatchMediaKeyEvent(eventDown)
        am.dispatchMediaKeyEvent(eventUp)
        return true
    }
}

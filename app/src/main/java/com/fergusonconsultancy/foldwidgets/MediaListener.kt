package com.fergusonconsultancy.foldwidgets

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * The app's one long-lived part. Android keeps a notification listener running, and only a
 * notification listener may watch other apps' media sessions. It watches the players, moves the
 * progress line while the screen is on, and redraws every widget when the wallpaper changes. It
 * reads no notifications.
 */
class MediaListener : android.service.notification.NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val watched = HashMap<MediaSessionManagerToken, MediaController>()
    private var sessions: MediaSessionManager? = null

    private val onSessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list.orEmpty()) }

    private val onPlayer = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = soon()
        override fun onMetadataChanged(metadata: MediaMetadata?) = soon()
        override fun onSessionDestroyed() = soon()
    }

    private val onWallpaper = WallpaperManager.OnColorsChangedListener { _, which ->
        if (which and WallpaperManager.FLAG_SYSTEM != 0) {
            Backdrop.invalidate()
            Widgets.later { Widgets.renderAll(this) }
            // The wallpaper behind every widget changed: Nodo's too
            Look.changed(this)
        }
    }

    private val onScreen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) soon() else main.removeCallbacks(tick)
        }
    }

    override fun onListenerConnected() {
        instance = this
        val msm = getSystemService(MediaSessionManager::class.java)
        sessions = msm
        runCatching {
            msm.addOnActiveSessionsChangedListener(onSessions, ComponentName(this, MediaListener::class.java), main)
            watch(msm.getActiveSessions(ComponentName(this, MediaListener::class.java)))
        }
        runCatching { WallpaperManager.getInstance(this).addOnColorsChangedListener(onWallpaper, main) }
        registerReceiver(onScreen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }, Context.RECEIVER_NOT_EXPORTED)
        soon()
    }

    /**
     * The phone turned, or folded or opened: redraw every widget for the glass now in use. The
     * music widget needs it (it keeps one picture); the others are drawn again to their exact
     * pixels and against the wallpaper behind them in this orientation.
     */
    private var shape = ""
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val now = "${newConfig.orientation}|${newConfig.screenWidthDp}x${newConfig.screenHeightDp}"
        if (now == shape) return
        shape = now
        main.postDelayed({ Widgets.later { Widgets.renderAll(this) } }, 250)
    }

    override fun onListenerDisconnected() {
        instance = null
        runCatching { sessions?.removeOnActiveSessionsChangedListener(onSessions) }
        runCatching { WallpaperManager.getInstance(this).removeOnColorsChangedListener(onWallpaper) }
        runCatching { unregisterReceiver(onScreen) }
        watched.values.forEach { it.unregisterCallback(onPlayer) }
        watched.clear()
        main.removeCallbacksAndMessages(null)
    }

    private fun watch(list: List<MediaController>) {
        val keep = list.associateBy { MediaSessionManagerToken(it) }
        watched.keys.filter { it !in keep }.forEach { watched.remove(it)?.unregisterCallback(onPlayer) }
        keep.forEach { (k, ctl) -> if (k !in watched) { ctl.registerCallback(onPlayer, main); watched[k] = ctl } }
        soon()
    }

    /** Players report in bursts (state, then metadata, then artwork): redraw once they settle. */
    private val redraw = Runnable {
        Widgets.later {
            Widgets.renderAll(this, Kind.MUSIC)
            main.post { main.removeCallbacks(tick); main.postDelayed(tick, 1000) }
        }
    }

    private fun soon() { main.removeCallbacks(redraw); main.postDelayed(redraw, 160) }

    companion object {
        /** The running listener. */
        @Volatile var instance: MediaListener? = null
    }

    /** While something plays and the screen is on, the line moves once a second; nothing else is redrawn. */
    private val tick = object : Runnable {
        override fun run() {
            if (!getSystemService(PowerManager::class.java).isInteractive) return
            val ids = Widgets.ids(this@MediaListener, Kind.MUSIC)
            if (ids.isEmpty()) return
            Widgets.later {
                val np = Media.now(this@MediaListener)
                if (np != null && np.playing && np.duration > 0) {
                    val v = np.progress()
                    ids.forEach { Widgets.nudge(this@MediaListener, it, v) }
                    main.postDelayed(this, 1000)
                }
            }
        }
    }
}

/** Sessions compared by their token, which stays the same for the life of a player's session. */
private class MediaSessionManagerToken(ctl: MediaController) {
    private val token = ctl.sessionToken
    override fun equals(other: Any?) = other is MediaSessionManagerToken && other.token == token
    override fun hashCode() = token.hashCode()
}

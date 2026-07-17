package com.oof.control.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.oof.control.R
import com.oof.control.utils.GameAppEntry
import com.oof.control.utils.GameModeManager
import com.oof.control.utils.GameModeProfile
import com.oof.control.utils.PrefsManager

/** Detects foreground app via UsageStatsManager.queryEvents() and auto-enables game mode. */
class GameModeService : Service() {

    companion object {
        private const val TAG           = "GameModeService"
        private const val NOTIF_CHANNEL = "game_mode_service"
        private const val NOTIF_ID      = 2001

        private const val POLL_MS = 1000L

        const val ACTION_START = "com.oof.control.GAME_MODE_START"
        const val ACTION_STOP  = "com.oof.control.GAME_MODE_STOP"

        @Volatile var isRunning: Boolean = false
        @Volatile var activeGamePackage: String? = null
    }

    private lateinit var prefs: PrefsManager
    private lateinit var usageStatsManager: UsageStatsManager

    private lateinit var monitorThread: HandlerThread
    private lateinit var monitorHandler: Handler

    private var lastForegroundPkg: String = ""

    // Stops polling while the screen is off — resumes on screen-on
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    monitorHandler.removeCallbacks(pollRunnable)
                    Log.d(TAG, "Screen off — polling paused")
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (isRunning) monitorHandler.post(pollRunnable)
                    Log.d(TAG, "Screen on — polling resumed")
                }
            }
        }
    }

    // ─── Core polling ───────────────────────────────────────────────────────

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                val current = getForegroundPackage()
                if (current.isNotEmpty() && current != lastForegroundPkg) {
                    lastForegroundPkg = current
                    handleForegroundChanged(current)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Poll error: ${e.message}")
            } finally {
                if (isRunning) monitorHandler.postDelayed(this, POLL_MS)
            }
        }
    }

    // ── UsageStatsManager foreground detection ────────────────────────────────

    private fun getForegroundPackage(): String {
        val endTime   = System.currentTimeMillis()
        val beginTime = endTime - POLL_MS          // only look at last poll window

        val events = usageStatsManager.queryEvents(beginTime, endTime)
        val event  = UsageEvents.Event()

        var foreground = ""
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                foreground = event.packageName
            }
        }
        return foreground
    }

    // ── App change handler ────────────────────────────────────────────────────

    private fun handleForegroundChanged(pkg: String) {
        val entry = prefs.getGameApps().firstOrNull { it.packageName == pkg }

        if (entry != null) {
            // A game app is now foreground
            if (!GameModeManager.isGameModeActive()) {
                Log.i(TAG, "Game detected: ${entry.label} — enabling game mode")
                val globalProfile = GameModeProfile.fromJson(prefs.gameModeProfileJson) ?: GameModeProfile.DEFAULT
                val ok = GameModeManager.enable(globalProfile)
                if (ok) activeGamePackage = pkg
                else Log.e(TAG, "Failed to enable game mode for $pkg")
            }
        } else {
            // Not a game app — disable game mode if it was active
            if (GameModeManager.isGameModeActive()) {
                Log.i(TAG, "Left game ($activeGamePackage) — disabling game mode")
                GameModeManager.disable()
                activeGamePackage = null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs             = PrefsManager(this)
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        createNotificationChannel()

        monitorThread = HandlerThread("GameModeMonitor")
        monitorThread.start()
        monitorHandler = Handler(monitorThread.looper)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            if (!hasUsageStatsPermission()) {
                Log.e(TAG, "PACKAGE_USAGE_STATS not granted — cannot start")
                stopSelf()
                return START_NOT_STICKY
            }
            isRunning = true
            startForeground(NOTIF_ID, buildSilentNotification())
            @Suppress("DEPRECATION")
            stopForeground(true)   // remove the notification immediately
            monitorHandler.post(pollRunnable)
            Log.i(TAG, "GameModeService started — UsageEvents polling at ${POLL_MS}ms")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        monitorHandler.removeCallbacks(pollRunnable)
        monitorThread.quitSafely()
        unregisterReceiver(screenReceiver)
        if (GameModeManager.isGameModeActive()) GameModeManager.disable()
        activeGamePackage = null
        Log.i(TAG, "GameModeService stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val now = System.currentTimeMillis()
            usageStatsManager.queryEvents(now - 1000, now) != null
        } catch (e: SecurityException) {
            Log.e(TAG, "PACKAGE_USAGE_STATS not granted", e)
            false
        }
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            NOTIF_CHANNEL, "Game Mode", NotificationManager.IMPORTANCE_NONE
        ).apply {
            description = "Game mode monitor"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildSilentNotification(): Notification =
        Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(R.drawable.ic_notification)
            .build()
}

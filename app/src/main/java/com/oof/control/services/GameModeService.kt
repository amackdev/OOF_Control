package com.oof.control.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.oof.control.R
import com.oof.control.utils.GameModeManager
import com.oof.control.utils.PrefsManager

/**
 * Foreground service that detects the active foreground app using
 * UsageStatsManager.queryEvents() — the exact same technique used by
 * XiaomiParts Thermal (packages_apps_XiaomiParts, branch 16.2).
 *
 * How it works (mirrors XiaomiParts ThermalService):
 *   1. A dedicated HandlerThread ("GameModeMonitor") owns all polling work.
 *   2. A Handler posts a Runnable every POLL_MS milliseconds.
 *   3. Each poll calls UsageStatsManager.queryEvents(now - POLL_MS, now)
 *      and iterates events looking for ACTIVITY_RESUMED.
 *   4. The last ACTIVITY_RESUMED package in that window is the foreground app.
 *   5. On change → enable / disable game mode via GameModeManager.
 *
 * Permission required: android.permission.PACKAGE_USAGE_STATS
 *   This is a "privileged" permission — grantable via adb or auto-granted on
 *   custom ROMs where the app is installed as a system app (priv-app).
 *   It does NOT require root.
 */
class GameModeService : Service() {

    companion object {
        private const val TAG           = "GameModeService"
        private const val NOTIF_CHANNEL = "game_mode_service"
        private const val NOTIF_ID      = 2001

        /** Poll interval in ms. XiaomiParts Thermal uses 1000 ms; 500 ms for faster game detection. */
        private const val POLL_MS = 500L

        const val ACTION_START = "com.oof.control.GAME_MODE_START"
        const val ACTION_STOP  = "com.oof.control.GAME_MODE_STOP"

        @Volatile var isRunning: Boolean = false
        @Volatile var activeGamePackage: String? = null
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private lateinit var prefs: PrefsManager
    private lateinit var usageStatsManager: UsageStatsManager

    /** Dedicated background thread — mirrors HandlerThread use in XiaomiParts Thermal. */
    private lateinit var monitorThread: HandlerThread
    private lateinit var monitorHandler: Handler

    /** Track last seen foreground to avoid redundant enable/disable calls. */
    private var lastForegroundPkg: String = ""

    // ── Core polling Runnable ─────────────────────────────────────────────────

    /**
     * Mirrors the Runnable/Handler loop in XiaomiParts ThermalService.
     *
     * queryEvents(beginTime, endTime) returns a UsageEvents cursor over all
     * app-lifecycle events in that window. We iterate every event; when the
     * event type is ACTIVITY_RESUMED we record its package. The last such
     * package after draining the cursor is the current foreground app.
     *
     * Why ACTIVITY_RESUMED and not queryUsageStats()?
     *   queryUsageStats() aggregates per-interval and has up to ~1 s staleness.
     *   queryEvents() delivers individual lifecycle events in near real-time.
     */
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
                // Always reschedule — same postDelayed loop as XiaomiParts Thermal
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
        // Drain cursor — last ACTIVITY_RESUMED event wins (= current foreground)
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
        Log.d(TAG, "Foreground changed -> $pkg")

        val gameApps = prefs.getGameApps()
        val entry    = gameApps.find { it.packageName == pkg }

        if (entry != null) {
            // A game app is now foreground
            if (!GameModeManager.isGameModeActive()) {
                Log.i(TAG, "Game detected: ${entry.label} — enabling game mode")
                val ok = GameModeManager.enable(entry.profile)
                if (ok) {
                    activeGamePackage = pkg
                    updateNotification("Game Mode ON · ${entry.label}")
                } else {
                    Log.e(TAG, "Failed to enable game mode for $pkg")
                }
            }
        } else {
            // Not a game app — disable game mode if it was active
            if (GameModeManager.isGameModeActive()) {
                Log.i(TAG, "Left game ($activeGamePackage) — disabling game mode")
                GameModeManager.disable()
                activeGamePackage = null
                updateNotification("Monitoring games...")
            }
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs             = PrefsManager(this)
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        createNotificationChannel()

        // Dedicated HandlerThread — same pattern as XiaomiParts Thermal
        monitorThread = HandlerThread("GameModeMonitor")
        monitorThread.start()
        monitorHandler = Handler(monitorThread.looper)
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
            startForeground(NOTIF_ID, buildNotification("Monitoring games..."))
            monitorHandler.post(pollRunnable)
            Log.i(TAG, "GameModeService started — UsageEvents polling at ${POLL_MS}ms")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        monitorHandler.removeCallbacks(pollRunnable)
        monitorThread.quitSafely()
        if (GameModeManager.isGameModeActive()) GameModeManager.disable()
        activeGamePackage = null
        Log.i(TAG, "GameModeService stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Permission check ──────────────────────────────────────────────────────

    /**
     * Verify PACKAGE_USAGE_STATS is granted.
     * On custom ROMs with the app pre-installed as priv-app this is auto-granted.
     * Users can grant it manually: Settings > Apps > Special app access > Usage access.
     * Grant via adb: adb shell appops set com.oof.control GET_USAGE_STATS allow
     */
    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val now = System.currentTimeMillis()
            usageStatsManager.queryEvents(now - 1000, now) != null
        } catch (e: SecurityException) {
            Log.e(TAG, "PACKAGE_USAGE_STATS not granted", e)
            false
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            NOTIF_CHANNEL, "Game Mode", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Game mode per-app monitoring"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("OOF Game Mode")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}

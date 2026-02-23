package com.oof.control.game

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.app.Service
import android.util.Log
import com.oof.control.utils.RootController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Always-running background service (system app — no foreground/notification needed).
 * Polls foreground app every second via ActivityManager.getRunningTasks().
 * Applies per-app touch profiles on game launch, resets on exit.
 * Sends broadcast so GameOverlayService can show/hide the overlay.
 */
class GameWatcherService : Service() {

    companion object {
        private const val TAG         = "GameWatcherService"
        private const val POLL_MS     = 1000L
        private const val RESET_TICKS = 3

        const val ACTION_GAME_CHANGED = "com.oof.control.GAME_CHANGED"
        const val EXTRA_PACKAGE       = "package"
        const val EXTRA_ACTIVE        = "active"

        @Volatile var isRunning:     Boolean = false
        @Volatile var activeGamePkg: String? = null

        fun start(context: Context) =
            context.startService(Intent(context, GameWatcherService::class.java))
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var activityManager: ActivityManager

    private var activeGame: GameProfile? = null
    private var nonGameTicks = 0

    override fun onCreate() {
        super.onCreate()
        activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        // Start overlay service alongside us — same lifetime
        try {
            startService(Intent(this, GameOverlayService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GameOverlayService: ${e.message}")
        }
        Log.i(TAG, "GameWatcherService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            GameProfileStore.init(applicationContext)
            startPolling()
            Log.i(TAG, "GameWatcherService started polling")
        }
        return START_STICKY  // system restarts us if killed
    }

    override fun onDestroy() {
        isRunning     = false
        activeGamePkg = null
        scope.cancel()
        super.onDestroy()
        Log.i(TAG, "GameWatcherService destroyed — will be restarted by system")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Poll loop ─────────────────────────────────────────────────────────────

    private fun startPolling() {
        scope.launch {
            while (isActive) {
                try {
                    getForegroundPackage()?.let { handleForegroundPackage(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error", e)
                }
                delay(POLL_MS)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getForegroundPackage(): String? = try {
        activityManager.getRunningTasks(1)?.firstOrNull()?.topActivity?.packageName
    } catch (e: Exception) {
        Log.e(TAG, "getRunningTasks failed: ${e.message}"); null
    }

    // ── Logic ─────────────────────────────────────────────────────────────────

    private suspend fun handleForegroundPackage(pkg: String) {
        if (pkg == packageName) return

        val current = activeGame
        val profile = GameProfileStore.get(pkg)

        when {
            profile != null && profile.enabled -> {
                nonGameTicks = 0
                if (pkg == current?.packageName) return
                Log.i(TAG, "Game launched: $pkg")
                activeGame    = profile
                activeGamePkg = pkg
                applyProfile(profile)
                broadcast(pkg, true)
            }
            current != null -> {
                if (++nonGameTicks >= RESET_TICKS) {
                    Log.i(TAG, "Game exited (${current.packageName})")
                    activeGame    = null
                    activeGamePkg = null
                    nonGameTicks  = 0
                    resetToDefaults()
                    broadcast("", false)
                }
            }
            else -> nonGameTicks = 0
        }
    }

    private suspend fun applyProfile(p: GameProfile) {
        RootController.setGameMode(true)
        RootController.setGameHighReportRate(p.highReportRate)
        RootController.setGameSensitivity(p.sensitivity)
        RootController.setGameEdgeFilter(p.edgeFilter)
        RootController.setGameTapStability(p.tapStability)
        RootController.setGameUpThreshold(p.upThreshold)
        Log.i(TAG, "Profile applied for ${p.packageName}")
    }

    private suspend fun resetToDefaults() {
        RootController.resetGameModeDefaults()
        Log.i(TAG, "Touch modes reset to defaults")
    }

    private fun broadcast(pkg: String, active: Boolean) {
        sendBroadcast(Intent(ACTION_GAME_CHANGED).apply {
            setPackage(packageName)   // explicit — guaranteed delivery to same app
            putExtra(EXTRA_PACKAGE, pkg)
            putExtra(EXTRA_ACTIVE, active)
        })
    }
}

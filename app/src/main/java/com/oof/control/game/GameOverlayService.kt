package com.oof.control.game

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import com.oof.control.R
import com.oof.control.utils.IoctlBridge
import com.oof.control.utils.TouchConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Polls GameWatcherService.activeGamePkg every second directly —
 * no broadcast dependency, no timing issue.
 * Shows/hides the floating overlay based on whether a game is active.
 */
class GameOverlayService : Service() {

    companion object {
        private const val TAG      = "GameOverlayService"
        private const val POLL_MS  = 1000L

        fun start(context: Context) =
            context.startService(Intent(context, GameOverlayService::class.java))
    }

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var fabView:        View? = null
    private var panelView:      View? = null
    private var panelVisible          = false
    private var shownForPkg:    String? = null   // pkg the FAB is currently shown for
    private var currentProfile: GameProfile? = null

    // FAB position
    private var fabInitX = 20
    private var fabInitY = 300

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        GameProfileStore.init(applicationContext)
        handler.post(pollRunnable)
        Log.i(TAG, "GameOverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        hideFab()
        hidePanel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Poll loop ─────────────────────────────────────────────────────────────

    private val pollRunnable = object : Runnable {
        override fun run() {
            val activePkg = GameWatcherService.activeGamePkg

            when {
                // Game became active (or switched)
                activePkg != null && activePkg != shownForPkg -> {
                    val profile = GameProfileStore.get(activePkg)
                    if (profile != null) {
                        Log.i(TAG, "Showing overlay for $activePkg")
                        currentProfile = profile
                        shownForPkg    = activePkg
                        hidePanel()
                        hideFab()
                        showFab()
                    }
                }
                // Game exited
                activePkg == null && shownForPkg != null -> {
                    Log.i(TAG, "Hiding overlay — game exited")
                    shownForPkg    = null
                    currentProfile = null
                    hidePanel()
                    hideFab()
                }
            }

            handler.postDelayed(this, POLL_MS)
        }
    }

    // ── FAB ───────────────────────────────────────────────────────────────────

    private fun showFab() {
        if (fabView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_game_fab, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = fabInitX
            y = fabInitY
        }

        var isDragging   = false
        var dragStartX   = 0f
        var dragStartY   = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    dragStartX = event.rawX - params.x
                    dragStartY = event.rawY - params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nx = (event.rawX - dragStartX).toInt()
                    val ny = (event.rawY - dragStartY).toInt()
                    if (!isDragging &&
                        (Math.abs(nx - params.x) > 8 || Math.abs(ny - params.y) > 8))
                        isDragging = true
                    if (isDragging) {
                        params.x = nx; params.y = ny
                        fabInitX = nx; fabInitY = ny
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) togglePanel()
                    true
                }
                else -> false
            }
        }

        fabView = view
        try {
            wm.addView(view, params)
            Log.i(TAG, "FAB shown")
        } catch (e: Exception) {
            Log.e(TAG, "FAB addView failed: ${e.message}", e)
            fabView = null
        }
    }

    private fun hideFab() {
        fabView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        fabView = null
    }

    // ── Panel ─────────────────────────────────────────────────────────────────

    private fun togglePanel() {
        if (panelVisible) hidePanel() else showPanel()
    }

    private fun showPanel() {
        if (panelView != null) return
        val profile = currentProfile ?: return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_game_panel, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = fabInitY
        }

        view.findViewById<TextView>(R.id.tv_overlay_game_name).text = profile.label
        view.findViewById<ImageButton>(R.id.btn_overlay_close)
            .setOnClickListener { hidePanel() }

        fun bindSeek(seekId: Int, labelId: Int, init: Int, mode: Int) {
            val seek  = view.findViewById<SeekBar>(seekId)
            val label = view.findViewById<TextView>(labelId)
            seek.progress = init
            label.text    = init.toString()
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    label.text = v.toString()
                    if (fromUser) scope.launch { IoctlBridge.setMode(0, mode, v) }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        bindSeek(R.id.seek_overlay_sensitivity,   R.id.tv_overlay_sens_val,  profile.sensitivity,  TouchConstants.MODE_AIM_SENSITIVITY)
        bindSeek(R.id.seek_overlay_edge_filter,   R.id.tv_overlay_edge_val,  profile.edgeFilter,   TouchConstants.MODE_EDGE_FILTER)
        bindSeek(R.id.seek_overlay_tap_stability, R.id.tv_overlay_tap_val,   profile.tapStability, TouchConstants.MODE_TAP_STABILITY)
        bindSeek(R.id.seek_overlay_up_threshold,  R.id.tv_overlay_up_val,    profile.upThreshold,  TouchConstants.MODE_UP_THRESHOLD)

        view.findViewById<View>(R.id.btn_overlay_save).setOnClickListener {
            val updated = profile.copy(
                sensitivity  = view.findViewById<SeekBar>(R.id.seek_overlay_sensitivity).progress,
                edgeFilter   = view.findViewById<SeekBar>(R.id.seek_overlay_edge_filter).progress,
                tapStability = view.findViewById<SeekBar>(R.id.seek_overlay_tap_stability).progress,
                upThreshold  = view.findViewById<SeekBar>(R.id.seek_overlay_up_threshold).progress,
            )
            GameProfileStore.put(updated)
            currentProfile = updated
            hidePanel()
        }

        panelView    = view
        panelVisible = true
        try {
            wm.addView(view, params)
            Log.i(TAG, "Panel shown")
        } catch (e: Exception) {
            Log.e(TAG, "Panel addView failed: ${e.message}", e)
            panelView    = null
            panelVisible = false
        }
    }

    private fun hidePanel() {
        panelView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panelView    = null
        panelVisible = false
    }
}

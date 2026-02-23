package com.oof.control.game

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.oof.control.R

/**
 * Bottom sheet to configure touch params for a single game profile.
 * Caller provides the current profile; receives updated profile via callback.
 */
class GameProfileBottomSheet : BottomSheetDialogFragment() {

    var profile: GameProfile? = null
    var onSave: ((GameProfile) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_game_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val p = profile ?: return

        // Header
        view.findViewById<TextView>(R.id.tv_sheet_game_name).text = p.label

        // High report rate
        val switchHighRr = view.findViewById<MaterialSwitch>(R.id.switch_sheet_high_rr)
        switchHighRr.isChecked = p.highReportRate

        // Seekbars
        fun bindSeekbar(seekId: Int, labelId: Int, current: Int): SeekBar {
            val seek  = view.findViewById<SeekBar>(seekId)
            val label = view.findViewById<TextView>(labelId)
            seek.max      = 4
            seek.progress = current
            label.text    = current.toString()
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { label.text = v.toString() }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            return seek
        }

        val seekSens  = bindSeekbar(R.id.seek_sheet_sensitivity,   R.id.tv_sheet_sens_val,   p.sensitivity)
        val seekEdge  = bindSeekbar(R.id.seek_sheet_edge_filter,   R.id.tv_sheet_edge_val,   p.edgeFilter)
        val seekTap   = bindSeekbar(R.id.seek_sheet_tap_stability, R.id.tv_sheet_tap_val,    p.tapStability)
        val seekUp    = bindSeekbar(R.id.seek_sheet_up_threshold,  R.id.tv_sheet_up_val,     p.upThreshold)

        // Buttons
        view.findViewById<View>(R.id.btn_sheet_save).setOnClickListener {
            val updated = p.copy(
                sensitivity    = seekSens.progress,
                edgeFilter     = seekEdge.progress,
                tapStability   = seekTap.progress,
                upThreshold    = seekUp.progress,
                highReportRate = switchHighRr.isChecked
            )
            onSave?.invoke(updated)
            dismiss()
        }

        view.findViewById<View>(R.id.btn_sheet_cancel).setOnClickListener { dismiss() }
    }

    companion object {
        const val TAG = "GameProfileSheet"
        fun newInstance(profile: GameProfile, onSave: (GameProfile) -> Unit) =
            GameProfileBottomSheet().also {
                it.profile = profile
                it.onSave  = onSave
            }
    }
}

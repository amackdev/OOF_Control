package com.oof.control.fragments

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.oof.control.R
import com.oof.control.databinding.FragmentGameBinding
import com.oof.control.game.AppPickerActivity
import com.oof.control.game.GameProfile
import com.oof.control.game.GameProfileBottomSheet
import com.oof.control.game.GameProfileStore
import com.oof.control.game.GameWatcherService
import com.oof.control.utils.IoctlBridge

class GameFragment : Fragment() {

    private var _binding: FragmentGameBinding? = null
    private val binding get() = _binding!!

    private val adapter = ProfileAdapter()

    private val pickApp = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val pkg   = result.data?.getStringExtra(AppPickerActivity.RESULT_PACKAGE) ?: return@registerForActivityResult
        val label = result.data?.getStringExtra(AppPickerActivity.RESULT_LABEL) ?: pkg

        if (GameProfileStore.contains(pkg)) { showToast("$label already added"); return@registerForActivityResult }

        val profile = GameProfile(packageName = pkg, label = label)
        GameProfileStore.put(profile)
        adapter.reload()
        updateEmptyState()
        openProfileSheet(profile)
    }

    private val gameReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val active = intent.getBooleanExtra(GameWatcherService.EXTRA_ACTIVE, false)
            val pkg    = intent.getStringExtra(GameWatcherService.EXTRA_PACKAGE) ?: return
            val activePkg = if (active) pkg else null
            updateActiveBadge(activePkg)
            adapter.setActivePackage(activePkg)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGameBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        GameProfileStore.init(requireContext())
        setupRecyclerView()
        setupFab()
        updateIoctlBadge()
        adapter.reload()
        updateEmptyState()
        updateActiveBadge(GameWatcherService.activeGamePkg)
    }

    override fun onResume() {
        super.onResume()
        requireContext().registerReceiver(
            gameReceiver,
            IntentFilter(GameWatcherService.ACTION_GAME_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )
        adapter.setActivePackage(GameWatcherService.activeGamePkg)
    }

    override fun onPause() {
        super.onPause()
        try { requireContext().unregisterReceiver(gameReceiver) } catch (_: Exception) {}
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    private fun setupRecyclerView() {
        binding.rvProfiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvProfiles.adapter = adapter
        adapter.onItemClick = { openProfileSheet(it) }

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val profile = adapter.getItem(vh.adapterPosition)
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Remove ${profile.label}?")
                    .setMessage("Touch settings will no longer auto-apply for this app.")
                    .setPositiveButton("Remove") { _, _ ->
                        GameProfileStore.remove(profile.packageName)
                        adapter.reload()
                        updateEmptyState()
                        showToast("${profile.label} removed")
                    }
                    .setNegativeButton("Cancel") { _, _ -> adapter.notifyItemChanged(vh.adapterPosition) }
                    .show()
            }
        }).attachToRecyclerView(binding.rvProfiles)
    }

    private fun setupFab() {
        binding.fabAddGame.setOnClickListener {
            pickApp.launch(Intent(requireContext(), AppPickerActivity::class.java))
        }
    }

    private fun updateIoctlBadge() {
        val ok = IoctlBridge.isDeviceAvailable()
        binding.tvIoctlStatus.text =
            if (ok) "✓ Xiaomi Touch IOCTL active" else "⚠ IOCTL unavailable — MIUI AIDL/sysfs fallback"
        binding.tvIoctlStatus.setTextColor(
            requireContext().getColor(if (ok) R.color.accent else R.color.text_secondary)
        )
    }

    private fun updateEmptyState() {
        val empty = GameProfileStore.getAll().isEmpty()
        binding.layoutEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvProfiles.visibility  = if (empty) View.GONE    else View.VISIBLE
    }

    private fun updateActiveBadge(activePkg: String?) {
        if (activePkg == null) {
            binding.cardActiveGame.visibility = View.GONE
        } else {
            binding.cardActiveGame.visibility = View.VISIBLE
            binding.tvActiveGame.text = "🎮 Active: ${GameProfileStore.get(activePkg)?.label ?: activePkg}"
        }
    }

    private fun openProfileSheet(profile: GameProfile) {
        GameProfileBottomSheet.newInstance(profile) { updated ->
            GameProfileStore.put(updated)
            adapter.reload()
            showToast("${updated.label} saved")
        }.show(childFragmentManager, GameProfileBottomSheet.TAG)
    }

    private fun showToast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.VH>() {
        var onItemClick: ((GameProfile) -> Unit)? = null
        private var items: MutableList<GameProfile> = mutableListOf()
        private var activePkg: String? = null

        fun reload() { items = GameProfileStore.getAll().toMutableList(); notifyDataSetChanged() }
        fun setActivePackage(pkg: String?) { activePkg = pkg; notifyDataSetChanged() }
        fun getItem(pos: Int) = items[pos]
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_game_profile, parent, false))

        override fun onBindViewHolder(holder: VH, pos: Int) {
            val p = items[pos]
            holder.bind(p, p.packageName == activePkg)
            holder.itemView.setOnClickListener { onItemClick?.invoke(p) }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val icon:   ImageView = view.findViewById(R.id.iv_profile_icon)
            private val name:   TextView  = view.findViewById(R.id.tv_profile_name)
            private val pkg:    TextView  = view.findViewById(R.id.tv_profile_pkg)
            private val badge:  TextView  = view.findViewById(R.id.tv_profile_badge)
            private val params: TextView  = view.findViewById(R.id.tv_profile_params)

            fun bind(p: GameProfile, isActive: Boolean) {
                name.text  = p.label
                pkg.text   = p.packageName
                badge.visibility = if (isActive) View.VISIBLE else View.GONE
                params.text = "Sens ${p.sensitivity}  Edge ${p.edgeFilter}  Tap ${p.tapStability}  Up ${p.upThreshold}" +
                    if (p.highReportRate) "  ⚡480Hz" else ""
                try {
                    icon.setImageDrawable(itemView.context.packageManager.getApplicationIcon(p.packageName))
                } catch (_: Exception) {
                    icon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }
        }
    }
}

package com.oof.control.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.oof.control.R
import com.oof.control.utils.GameAppEntry

class GameAppAdapter(
    private val apps: MutableList<GameAppEntry>,
    private val onRemoveClick: (String) -> Unit
) : RecyclerView.Adapter<GameAppAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appName: TextView = view.findViewById(R.id.tv_app_name)
        val pkgName: TextView = view.findViewById(R.id.tv_pkg_name)
        val appIcon: ImageView = view.findViewById(R.id.iv_app_icon)
        val btnRemove: ImageButton = view.findViewById(R.id.btn_remove_app)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_game_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.appName.text = app.label
        holder.pkgName.text = app.packageName

        try {
            val icon = holder.itemView.context.packageManager.getApplicationIcon(app.packageName)
            holder.appIcon.setImageDrawable(icon)
        } catch (e: Exception) {
            // Use default or clear if not found
            holder.appIcon.setImageDrawable(null)
        }

        holder.btnRemove.setOnClickListener {
            onRemoveClick(app.packageName)
        }
    }

    override fun getItemCount() = apps.size

    fun updateApps(newApps: List<GameAppEntry>) {
        apps.clear()
        apps.addAll(newApps)
        notifyDataSetChanged()
    }
}

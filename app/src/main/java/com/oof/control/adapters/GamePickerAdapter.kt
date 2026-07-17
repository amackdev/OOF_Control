package com.oof.control.adapters

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.oof.control.R
import kotlinx.coroutines.*

class GamePickerAdapter(
    private val apps: List<ApplicationInfo>,
    private val pm: PackageManager
) : RecyclerView.Adapter<GamePickerAdapter.ViewHolder>() {

    val checkedItems = BooleanArray(apps.size) { false }
    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val iconCache = HashMap<String, Drawable>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appName: TextView = view.findViewById(R.id.tv_picker_name)
        val appIcon: ImageView = view.findViewById(R.id.iv_picker_icon)
        val cbSelect: CheckBox = view.findViewById(R.id.cb_picker_select)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_game_picker, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.appName.text = app.loadLabel(pm).toString()

        val pkg = app.packageName
        holder.itemView.tag = pkg

        val cachedIcon = iconCache[pkg]
        if (cachedIcon != null) {
            holder.appIcon.setImageDrawable(cachedIcon)
        } else {
            holder.appIcon.setImageDrawable(null)
            adapterScope.launch {
                val icon = withContext(Dispatchers.IO) {
                    try {
                        holder.itemView.context.packageManager.getApplicationIcon(pkg)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (icon != null) {
                    iconCache[pkg] = icon
                    if (holder.itemView.tag == pkg) {
                        holder.appIcon.setImageDrawable(icon)
                    }
                }
            }
        }

        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = checkedItems[position]

        val toggleAction = View.OnClickListener {
            val newState = !checkedItems[position]
            checkedItems[position] = newState
            holder.cbSelect.isChecked = newState
        }

        holder.itemView.setOnClickListener(toggleAction)
        holder.cbSelect.setOnClickListener(toggleAction)
    }

    override fun getItemCount() = apps.size

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.cancel()
    }
}

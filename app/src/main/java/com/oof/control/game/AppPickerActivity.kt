package com.oof.control.game

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.oof.control.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen activity to pick an installed app.
 * Returns RESULT_OK with intent extra "packageName" and "label".
 */
class AppPickerActivity : AppCompatActivity() {

    companion object {
        const val RESULT_PACKAGE = "packageName"
        const val RESULT_LABEL   = "label"
    }

    data class AppItem(val packageName: String, val label: String)

    private lateinit var recycler: RecyclerView
    private lateinit var search:   EditText
    private lateinit var loading:  View
    private val adapter = AppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build layout programmatically — no extra XML needed
        val root = layoutInflater.inflate(R.layout.activity_app_picker, null)
        setContentView(root)
        setResult(Activity.RESULT_CANCELED)

        title = "Pick an app"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        search   = root.findViewById(R.id.et_search)
        recycler = root.findViewById(R.id.rv_apps)
        loading  = root.findViewById(R.id.progress_loading)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        adapter.onItemClick = { item ->
            val data = android.content.Intent().apply {
                putExtra(RESULT_PACKAGE, item.packageName)
                putExtra(RESULT_LABEL,   item.label)
            }
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadApps()
    }

    private fun loadApps() {
        loading.visibility = View.VISIBLE
        recycler.visibility = View.GONE

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { queryInstalledApps() }
            loading.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            adapter.setData(apps)
        }
    }

    private fun queryInstalledApps(): List<AppItem> {
        val pm    = packageManager
        val flags = PackageManager.GET_META_DATA
        return pm.getInstalledApplications(flags)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }
            .map    { AppItem(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── Adapter ──────────────────────────────────────────────────────────────

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.VH>() {

        var onItemClick: ((AppItem) -> Unit)? = null
        private var full: List<AppItem> = emptyList()
        private var shown: List<AppItem> = emptyList()

        fun setData(list: List<AppItem>) {
            full  = list
            shown = list
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            shown = if (query.isBlank()) full
            else full.filter {
                it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
            notifyDataSetChanged()
        }

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_picker, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = shown[position]
            holder.bind(item)
            holder.itemView.setOnClickListener { onItemClick?.invoke(item) }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val icon: ImageView = view.findViewById(R.id.iv_app_icon)
            private val name: TextView  = view.findViewById(R.id.tv_app_name)
            private val pkg:  TextView  = view.findViewById(R.id.tv_app_pkg)

            fun bind(item: AppItem) {
                name.text = item.label
                pkg.text  = item.packageName
                try {
                    icon.setImageDrawable(
                        itemView.context.packageManager.getApplicationIcon(item.packageName)
                    )
                } catch (_: Exception) {
                    icon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }
        }
    }
}

package com.isolapp.manager

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

data class AppRow(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    val sub: String,
    val actionText: String?,
    val showAuto: Boolean = false,
    val autoAllowed: Boolean = true,
    val showMore: Boolean = false,
    val system: Boolean = false,
    val removed: Boolean = false,
)

class AppAdapter(
    private val onAction: (AppRow) -> Unit,
    private val onAuto: (AppRow, Boolean) -> Unit = { _, _ -> },
    private val onMore: (AppRow, View) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<AppAdapter.VH>() {

    var rows: List<AppRow> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val label: TextView = v.findViewById(R.id.label)
        val sub: TextView = v.findViewById(R.id.sub)
        val auto: MaterialSwitch = v.findViewById(R.id.swAuto)
        val action: MaterialButton = v.findViewById(R.id.action)
        val more: ImageButton = v.findViewById(R.id.more)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = rows[position]
        h.icon.setImageDrawable(r.icon)
        h.label.text = r.label
        h.sub.text = r.sub
        h.action.visibility = if (r.actionText != null) View.VISIBLE else View.GONE
        h.action.text = r.actionText
        h.action.setOnClickListener { onAction(r) }
        h.auto.setOnCheckedChangeListener(null)
        h.auto.visibility = if (r.showAuto) View.VISIBLE else View.GONE
        h.auto.isChecked = r.autoAllowed
        h.auto.setOnCheckedChangeListener { _, checked -> onAuto(r, checked) }
        h.more.visibility = if (r.showMore) View.VISIBLE else View.GONE
        h.more.setOnClickListener { onMore(r, it) }
    }
}

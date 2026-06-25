package com.example.guet_map.ui.map.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.guet_map.R
import com.example.guet_map.ui.map.model.MapThemeInfo
import com.google.android.material.card.MaterialCardView

/**
 * 地图主题选择适配器
 */
class MapThemeAdapter(
    private val themes: List<MapThemeInfo>,
    private val onThemeSelected: (MapThemeInfo) -> Unit
) : RecyclerView.Adapter<MapThemeAdapter.ThemeViewHolder>() {

    class ThemeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardTheme: MaterialCardView = view.findViewById(R.id.cardTheme)
        val ivThemeIcon: ImageView = view.findViewById(R.id.ivThemeIcon)
        val tvThemeName: TextView = view.findViewById(R.id.tvThemeName)
        val tvThemeDescription: TextView = view.findViewById(R.id.tvThemeDescription)
        val ivSelected: ImageView = view.findViewById(R.id.ivSelected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThemeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_map_theme, parent, false)
        return ThemeViewHolder(view)
    }

    override fun onBindViewHolder(holder: ThemeViewHolder, position: Int) {
        val themeInfo = themes[position]
        val theme = themeInfo.type

        holder.tvThemeName.text = theme.displayName
        holder.tvThemeDescription.text = theme.description
        holder.ivThemeIcon.setImageResource(theme.iconRes)

        // 显示选中状态
        if (themeInfo.isCurrent) {
            holder.ivSelected.visibility = View.VISIBLE
            holder.cardTheme.setStrokeColor(holder.itemView.context.getColor(R.color.primary))
            holder.cardTheme.strokeWidth = 2
        } else {
            holder.ivSelected.visibility = View.GONE
            holder.cardTheme.strokeWidth = 0
        }

        holder.cardTheme.setOnClickListener {
            onThemeSelected(themeInfo)
        }
    }

    override fun getItemCount(): Int = themes.size
}

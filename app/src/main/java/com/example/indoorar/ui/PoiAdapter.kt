package com.example.indoorar.ui

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.indoorar.R

class PoiAdapter(
    private val items: List<PoiItem>,
    private val onPoiClick: (PoiItem, MotionEvent) -> Unit
) : RecyclerView.Adapter<PoiAdapter.PoiViewHolder>() {

    inner class PoiViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPoi: ImageView = view.findViewById(R.id.ivPoi)
        val tvPoiName: TextView = view.findViewById(R.id.tvPoiName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PoiViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_poi, parent, false)
        return PoiViewHolder(view)
    }

    override fun onBindViewHolder(holder: PoiViewHolder, position: Int) {
        val poi = items[position]
        holder.ivPoi.setImageResource(poi.iconRes)
        holder.tvPoiName.text = poi.name

        holder.itemView.setOnTouchListener { _, event ->
            onPoiClick(poi, event)
            true
        }
    }

    override fun getItemCount(): Int = items.size
}
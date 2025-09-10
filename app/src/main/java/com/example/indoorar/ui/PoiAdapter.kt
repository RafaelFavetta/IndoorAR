package com.example.indoorar.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.indoorar.R

class PoiAdapter(
    private val pois: List<PoiItem>,
    private val onPoiClick: (PoiItem) -> Unit
) : RecyclerView.Adapter<PoiAdapter.PoiViewHolder>() {

    inner class PoiViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivPoi: ImageView = itemView.findViewById(R.id.ivPoi)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PoiViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_poi, parent, false)
        return PoiViewHolder(view)
    }

    override fun onBindViewHolder(holder: PoiViewHolder, position: Int) {
        val poi = pois[position]
        holder.ivPoi.setImageResource(poi.iconRes)

        holder.itemView.setOnClickListener {
            onPoiClick(poi)
        }
    }

    override fun getItemCount() = pois.size
}
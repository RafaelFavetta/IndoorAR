package com.example.indoorar

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.storage.FirebaseStorage

class MapasAdapter(
    private val mapas: MutableList<MapaResumo> = mutableListOf(),
    private val onClick: (MapaResumo) -> Unit
) : RecyclerView.Adapter<MapasAdapter.MapaViewHolder>() {

    fun submit(novosMapas: List<MapaResumo>) {
        mapas.clear()
        mapas.addAll(novosMapas)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MapaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mapa, parent, false)
        return MapaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MapaViewHolder, position: Int) {
        val mapa = mapas[position]
        holder.bind(mapa)
        holder.itemView.setOnClickListener { onClick(mapa) }
    }

    override fun getItemCount(): Int = mapas.size

    class MapaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nomeText: TextView = itemView.findViewById(R.id.txtNome)
        private val thumb: ImageView = itemView.findViewById(R.id.ivThumbMapa)

        fun bind(mapa: MapaResumo) {
            nomeText.text = mapa.nome

            val thumbBytes = mapa.imagemBlobThumb?.toBytes()
            val mediumBytes = mapa.imagemBlob?.toBytes()
            when {
                thumbBytes != null && thumbBytes.isNotEmpty() -> {
                    Glide.with(thumb.context)
                        .load(thumbBytes)
                        .centerCrop()
                        .placeholder(R.drawable.ic_minimap_placeholder)
                        .error(R.drawable.ic_minimap_placeholder)
                        .into(thumb)
                }
                mediumBytes != null && mediumBytes.isNotEmpty() -> {
                    Glide.with(thumb.context)
                        .load(mediumBytes)
                        .centerCrop()
                        .placeholder(R.drawable.ic_minimap_placeholder)
                        .error(R.drawable.ic_minimap_placeholder)
                        .into(thumb)
                }
                else -> {
                    val url = mapa.imagemUrl
                    if (!url.isNullOrBlank()) {
                        if (url.startsWith("gs://")) {
                            val ref = FirebaseStorage.getInstance().getReferenceFromUrl(url)
                            ref.downloadUrl
                                .addOnSuccessListener { httpsUri ->
                                    Glide.with(thumb.context)
                                        .load(httpsUri)
                                        .centerCrop()
                                        .placeholder(R.drawable.ic_minimap_placeholder)
                                        .error(R.drawable.ic_minimap_placeholder)
                                        .into(thumb)
                                }
                                .addOnFailureListener {
                                    thumb.setImageResource(R.drawable.ic_minimap_placeholder)
                                }
                        } else {
                            Glide.with(thumb.context)
                                .load(url)
                                .centerCrop()
                                .placeholder(R.drawable.ic_minimap_placeholder)
                                .error(R.drawable.ic_minimap_placeholder)
                                .into(thumb)
                        }
                    } else {
                        thumb.setImageResource(R.drawable.ic_minimap_placeholder)
                    }
                }
            }
        }
    }
}

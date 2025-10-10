package com.example.indoorar

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.storage.FirebaseStorage

class RecentesAdapter(
    private val onClick: (MapaResumo) -> Unit
) : RecyclerView.Adapter<RecentesAdapter.VH>() {

    private val itens = mutableListOf<MapaResumo>()
    private val itensFiltrados = mutableListOf<MapaResumo>()

    fun submit(novos: List<MapaResumo>) {
        itens.clear()
        itens.addAll(novos)
        aplicarFiltro(currentQuery)
    }

    private var currentQuery: String = ""

    fun filtrar(query: String) {
        currentQuery = query
        aplicarFiltro(query)
    }

    private fun aplicarFiltro(q: String) {
        itensFiltrados.clear()
        if (q.isBlank()) {
            itensFiltrados.addAll(itens)
        } else {
            val lower = q.lowercase()
            itensFiltrados.addAll(itens.filter { m ->
                m.nome.lowercase().contains(lower) || m.descricao.lowercase().contains(lower)
            })
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_mapa_recente, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = itensFiltrados.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(itensFiltrados[position], onClick)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtNome = itemView.findViewById<TextView>(R.id.txtNomeMapaRecente)
        private val txtDesc = itemView.findViewById<TextView>(R.id.txtDescricaoMapaRecente)
        private val ivThumb = itemView.findViewById<ImageView>(R.id.ivThumbMapaRecente)
        fun bind(m: MapaResumo, onClick: (MapaResumo) -> Unit) {
            txtNome.text = m.nome
            txtDesc.text = m.descricao.ifBlank { "Sem descrição" }

            val thumbBytes = m.imagemBlobThumb?.toBytes()
            val mediumBytes = m.imagemBlob?.toBytes()
            when {
                thumbBytes != null && thumbBytes.isNotEmpty() -> {
                    Glide.with(ivThumb.context)
                        .load(thumbBytes)
                        .centerCrop()
                        .placeholder(R.drawable.ic_minimap_placeholder)
                        .error(R.drawable.ic_minimap_placeholder)
                        .into(ivThumb)
                }
                mediumBytes != null && mediumBytes.isNotEmpty() -> {
                    Glide.with(ivThumb.context)
                        .load(mediumBytes)
                        .centerCrop()
                        .placeholder(R.drawable.ic_minimap_placeholder)
                        .error(R.drawable.ic_minimap_placeholder)
                        .into(ivThumb)
                }
                else -> {
                    val url = m.imagemUrl
                    if (!url.isNullOrBlank()) {
                        if (url.startsWith("gs://")) {
                            val ref = FirebaseStorage.getInstance().getReferenceFromUrl(url)
                            ref.downloadUrl
                                .addOnSuccessListener { httpsUri ->
                                    Glide.with(ivThumb.context)
                                        .load(httpsUri)
                                        .centerCrop()
                                        .placeholder(R.drawable.ic_minimap_placeholder)
                                        .error(R.drawable.ic_minimap_placeholder)
                                        .into(ivThumb)
                                }
                                .addOnFailureListener {
                                    ivThumb.setImageResource(R.drawable.ic_minimap_placeholder)
                                }
                        } else {
                            Glide.with(ivThumb.context)
                                .load(url)
                                .centerCrop()
                                .placeholder(R.drawable.ic_minimap_placeholder)
                                .error(R.drawable.ic_minimap_placeholder)
                                .into(ivThumb)
                        }
                    } else {
                        ivThumb.setImageResource(R.drawable.ic_minimap_placeholder)
                    }
                }
            }

            itemView.setOnClickListener { onClick(m) }
        }
    }
}

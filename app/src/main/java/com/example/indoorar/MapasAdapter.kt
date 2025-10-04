package com.example.indoorar

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

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
        private val descricaoText: TextView = itemView.findViewById(R.id.txtDescricao)

        fun bind(mapa: MapaResumo) {
            nomeText.text = mapa.nome
            descricaoText.text = mapa.descricao
        }
    }
}


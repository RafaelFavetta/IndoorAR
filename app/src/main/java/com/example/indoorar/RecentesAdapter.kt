package com.example.indoorar

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

class RecentesAdapter(
    private val onClick: (MapaResumo) -> Unit
) : RecyclerView.Adapter<RecentesAdapter.VH>() {

    private val itens = mutableListOf<MapaResumo>()
    private val itensFiltrados = mutableListOf<MapaResumo>()
    private var currentQuery: String = ""

    // Novo: controle de tamanho do card
    private var cardWidthPx: Int? = null
    private var sideMarginPx: Int = 0

    fun setItemSizing(widthPx: Int, sideMarginPx: Int) {
        this.cardWidthPx = widthPx
        this.sideMarginPx = sideMarginPx
    }

    fun submit(novos: List<MapaResumo>) {
        itens.clear()
        itens.addAll(novos)
        aplicarFiltro(currentQuery)
    }

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
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_mapa, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = itensFiltrados.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(itensFiltrados[position], onClick, cardWidthPx, sideMarginPx)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtNome = itemView.findViewById<TextView>(R.id.txtNome)
        private val ivThumb = itemView.findViewById<ImageView>(R.id.ivThumbMapa)
        private val btnFav = itemView.findViewById<ImageButton>(R.id.btnFavorito)
        private val card = itemView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardMapa)

        fun bind(m: MapaResumo, onClick: (MapaResumo) -> Unit, cardWidthPx: Int?, sideMarginPx: Int) {
            // Largura do item: usar configurada (para 1,5 cards) ou fallback 280dp
            val density = itemView.resources.displayMetrics.density
            val fallbackWidth = (280 * density).toInt()
            val desiredWidth = cardWidthPx ?: fallbackWidth
            val sideMargin = if (sideMarginPx > 0) sideMarginPx else (10 * density).toInt()
            val lp = itemView.layoutParams as? ViewGroup.MarginLayoutParams
                ?: ViewGroup.MarginLayoutParams(desiredWidth, ViewGroup.MarginLayoutParams.WRAP_CONTENT)
            lp.width = desiredWidth
            lp.setMargins(sideMargin, lp.topMargin, sideMargin, lp.bottomMargin)
            itemView.layoutParams = lp

            txtNome.text = m.nome

            // Thumb loading logic
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

            // Favoritos
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                btnFav.visibility = View.GONE
            } else {
                btnFav.visibility = View.VISIBLE
                val uid = user.uid
                val docRef = FirebaseFirestore.getInstance()
                    .collection("usuarios").document(uid)
                    .collection("favoritos").document(m.id)

                var isFav = false
                updateFavIcon(isFav)
                docRef.get().addOnSuccessListener { snap ->
                    isFav = snap.exists()
                    updateFavIcon(isFav)
                }

                btnFav.setOnClickListener {
                    if (isFav) {
                        docRef.delete().addOnSuccessListener {
                            isFav = false
                            updateFavIcon(false)
                        }
                    } else {
                        val data = hashMapOf(
                            "mapId" to m.id,
                            "nome" to m.nome,
                            "addedAt" to FieldValue.serverTimestamp()
                        )
                        docRef.set(data).addOnSuccessListener {
                            isFav = true
                            updateFavIcon(true)
                        }
                    }
                }
            }

            itemView.setOnClickListener { onClick(m) }
        }

        private fun updateFavIcon(isFav: Boolean) {
            btnFav.setImageResource(if (isFav) R.drawable.heartfavoritesfull else R.drawable.heartfavorites)
        }
    }
}

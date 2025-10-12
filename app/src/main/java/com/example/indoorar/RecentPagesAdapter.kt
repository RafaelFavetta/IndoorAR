package com.example.indoorar

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.bumptech.glide.Glide
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue

class RecentPagesAdapter(
    private val onClick: (MapaResumo) -> Unit
) : RecyclerView.Adapter<RecentPagesAdapter.PageVH>() {

    private data class Page(val items: List<MapaResumo>) {
        val idKey: String = items.joinToString("|") { it.id }
        val contentKey: String = items.joinToString("|") {
            buildString {
                append(it.id)
                append(':'); append(it.nome)
                append(':'); append(it.imagemUrl ?: "")
                append(':'); append(it.imagemBlobThumb?.hashCode() ?: 0)
                append(':'); append(it.dataCriacao?.seconds ?: 0)
            }
        }
    }

    private val pages = mutableListOf<Page>()

    init { setHasStableIds(true) }

    fun submit(allItems: List<MapaResumo>) {
        // Two items per page
        val newPages = allItems.chunked(2).map { Page(it) }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = pages.size
            override fun getNewListSize(): Int = newPages.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                pages[oldItemPosition].idKey == newPages[newItemPosition].idKey
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                pages[oldItemPosition].contentKey == newPages[newItemPosition].contentKey
        })
        pages.clear()
        pages.addAll(newPages)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_mapa_page, parent, false)
        return PageVH(v)
    }

    override fun getItemCount(): Int = pages.size

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        holder.bind(pages[position].items, onClick)
    }

    override fun getItemId(position: Int): Long = pages[position].idKey.hashCode().toLong()

    class PageVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val c1: FrameLayout = itemView.findViewById(R.id.containerItem1)
        private val c2: FrameLayout = itemView.findViewById(R.id.containerItem2)

        fun bind(items: List<MapaResumo>, onClick: (MapaResumo) -> Unit) {
            bindSlot(c1, items.getOrNull(0), onClick)
            bindSlot(c2, items.getOrNull(1), onClick)
        }

        private fun setFavIcon(btn: ImageButton, isFav: Boolean) {
            btn.setImageResource(if (isFav) R.drawable.heartfavoritesfull else R.drawable.heartfavorites)
        }

        private fun bindSlot(container: FrameLayout, mapa: MapaResumo?, onClick: (MapaResumo) -> Unit) {
            container.removeAllViews()
            if (mapa == null) {
                container.visibility = View.INVISIBLE
                return
            } else container.visibility = View.VISIBLE

            val v = LayoutInflater.from(container.context).inflate(R.layout.item_mapa, container, false)

            // Largura compacta e centralizada (ex.: 280dp) com pequenas margens laterais
            val density = container.resources.displayMetrics.density
            val cardWidthPx = (280 * density).toInt()
            val sideMargin = (10 * density).toInt()
            val lp = FrameLayout.LayoutParams(cardWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(sideMargin, 0, sideMargin, 0)
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
            v.layoutParams = lp

            val nome = v.findViewById<TextView>(R.id.txtNome)
            val thumb = v.findViewById<ImageView>(R.id.ivThumbMapa)
            val btnFav = v.findViewById<ImageButton>(R.id.btnFavorito)

            nome.text = mapa.nome

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

            // Favoritos por usuário
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                btnFav.visibility = View.GONE
            } else {
                btnFav.visibility = View.VISIBLE
                val uid = user.uid
                val docRef = FirebaseFirestore.getInstance()
                    .collection("usuarios").document(uid)
                    .collection("favoritos").document(mapa.id)

                // Estado inicial do ícone
                var isFav = false
                setFavIcon(btnFav, isFav)
                docRef.get().addOnSuccessListener { snap ->
                    isFav = snap.exists()
                    setFavIcon(btnFav, isFav)
                }

                btnFav.setOnClickListener {
                    if (isFav) {
                        docRef.delete().addOnSuccessListener {
                            isFav = false
                            setFavIcon(btnFav, false)
                        }
                    } else {
                        val data = hashMapOf(
                            "mapId" to mapa.id,
                            "nome" to mapa.nome,
                            "addedAt" to FieldValue.serverTimestamp()
                        )
                        docRef.set(data).addOnSuccessListener {
                            isFav = true
                            setFavIcon(btnFav, true)
                        }
                    }
                }
            }

            v.setOnClickListener { onClick(mapa) }
            container.addView(v)
        }
    }
}

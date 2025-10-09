package com.example.indoorar

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.bumptech.glide.Glide
import com.google.firebase.storage.FirebaseStorage
import java.text.SimpleDateFormat
import java.util.Locale

class RecentPagesAdapter(
    private val onClick: (MapaResumo) -> Unit
) : RecyclerView.Adapter<RecentPagesAdapter.PageVH>() {

    // Representa uma página com até 3 itens e chaves para diff
    private data class Page(val items: List<MapaResumo>) {
        val idKey: String = items.joinToString("|") { it.id }
        val contentKey: String = items.joinToString("|") {
            buildString {
                append(it.id)
                append(':'); append(it.nome)
                append(':'); append(it.imagemUrl ?: "")
                append(':'); append(it.dataCriacao?.seconds ?: 0)
            }
        }
    }

    private val pages = mutableListOf<Page>()

    init { setHasStableIds(true) }

    fun submit(allItems: List<MapaResumo>) {
        val newPages = allItems.chunked(3).map { Page(it) }
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
        private val c3: FrameLayout = itemView.findViewById(R.id.containerItem3)

        fun bind(items: List<MapaResumo>, onClick: (MapaResumo) -> Unit) {
            bindSlot(c1, items.getOrNull(0), onClick)
            bindSlot(c2, items.getOrNull(1), onClick)
            bindSlot(c3, items.getOrNull(2), onClick)
        }

        private fun bindSlot(container: FrameLayout, mapa: MapaResumo?, onClick: (MapaResumo) -> Unit) {
            container.removeAllViews()
            if (mapa == null) {
                container.visibility = View.INVISIBLE
                return
            } else container.visibility = View.VISIBLE

            val v = LayoutInflater.from(container.context).inflate(R.layout.item_mapa, container, false)
            val nome = v.findViewById<TextView>(R.id.txtNome)
            val desc = v.findViewById<TextView>(R.id.txtDescricao)
            val thumb = v.findViewById<ImageView>(R.id.ivThumbMapa)

            nome.text = mapa.nome
            desc.text = mapa.descricao.ifBlank { "Sem descrição" }

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

            v.setOnClickListener { onClick(mapa) }
            container.addView(v)
        }
    }
}

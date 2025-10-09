package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.indoorar.ui.ActivityEditor
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.bumptech.glide.Glide
import com.google.firebase.storage.FirebaseStorage

class ActivityMeusMapas : BaseActivity() {

    private val adapter = MapasAdapter { mapa -> onMapaClicked(mapa) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meus_mapas)

        val btnVoltar = findViewById<android.widget.ImageButton>(R.id.btnVoltar)
        btnVoltar.setOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerMapas)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            Toast.makeText(this, "Usuário não logado", Toast.LENGTH_SHORT).show()
            return
        }

        FirebaseFirestore.getInstance().collection("mapas")
            .whereEqualTo("criadorUid", userId)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Toast.makeText(this, "Erro ao buscar mapas", Toast.LENGTH_SHORT).show()
                    adapter.submit(emptyList())
                    return@addSnapshotListener
                }
                val listaMapas = snapshots?.documents?.map { doc ->
                    docParaMapaResumoSeguro(doc)
                } ?: emptyList()
                adapter.submit(listaMapas)
            }
    }

    private fun docParaMapaResumoSeguro(doc: DocumentSnapshot): MapaResumo {
        return MapaResumo(
            id = doc.id,
            nome = doc.getString("nome") ?: "Mapa sem nome",
            descricao = doc.getString("descricao") ?: "",
            autorUid = doc.getString("criadorUid") ?: "",
            autorNome = doc.getString("nomeAutor") ?: (doc.getString("criadorUid") ?: ""),
            dataCriacao = doc.getTimestamp("dataCriacao"),
            imagemUrl = doc.getString("imagemUrl")
        )
    }

    private fun onMapaClicked(m: MapaResumo) {
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(R.layout.bottomsheet_mapa_preview)

        val txtTitulo = dialog.findViewById<TextView>(R.id.txtTituloMapa)
        val txtDesc = dialog.findViewById<TextView>(R.id.txtDescricaoMapa)
        val ivPreview = dialog.findViewById<ImageView>(R.id.ivPreview)
        val btnIniciar = dialog.findViewById<Button>(R.id.btnIniciarNavegacao)

        txtTitulo?.text = m.nome
        txtDesc?.text = m.descricao.ifBlank { "Sem descrição" }

        val url = m.imagemUrl
        if (ivPreview != null) {
            if (!url.isNullOrBlank()) {
                if (url.startsWith("gs://")) {
                    val ref = FirebaseStorage.getInstance().getReferenceFromUrl(url)
                    ref.downloadUrl
                        .addOnSuccessListener { httpsUri ->
                            Glide.with(ivPreview.context)
                                .load(httpsUri)
                                .centerCrop()
                                .placeholder(R.drawable.ic_minimap_placeholder)
                                .error(R.drawable.ic_minimap_placeholder)
                                .into(ivPreview)
                        }
                        .addOnFailureListener {
                            ivPreview.setImageResource(R.drawable.ic_minimap_placeholder)
                        }
                } else {
                    Glide.with(ivPreview.context)
                        .load(url)
                        .centerCrop()
                        .placeholder(R.drawable.ic_minimap_placeholder)
                        .error(R.drawable.ic_minimap_placeholder)
                        .into(ivPreview)
                }
            } else {
                ivPreview.setImageResource(R.drawable.ic_minimap_placeholder)
            }
        }

        btnIniciar?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, ActivityMap::class.java).apply {
                putExtra("MAP_ID", m.id)
            })
        }

        dialog.show()
    }
}

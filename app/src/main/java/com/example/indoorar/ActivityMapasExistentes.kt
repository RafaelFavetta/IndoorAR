package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.indoorar.ui.ActivityEditor
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class ActivityMapasExistentes : BaseActivity() {
    private lateinit var recycler: RecyclerView
    private val adapter = MapasAdapter { mapa -> onMapaClicked(mapa) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mapas_existentes)
        val btnVoltar = findViewById<android.widget.ImageButton>(R.id.btnVoltar)
        btnVoltar.setOnClickListener { finish() }
        recycler = findViewById(R.id.recyclerMapas)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        carregarMapasExistentes()
    }

    private fun carregarMapasExistentes() {
        FirebaseFirestore.getInstance()
            .collection("mapas")
            .orderBy("dataCriacao", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val itens = snap.documents.map { doc ->
                    MapaResumo(
                        id = doc.id,
                        nome = doc.getString("nome") ?: "Mapa sem nome",
                        descricao = doc.getString("descricao") ?: "Sem descrição",
                        autorUid = doc.getString("criadorUid") ?: "Desconhecido",
                        autorNome = doc.getString("nomeAutor") ?: "Desconhecido",
                        dataCriacao = doc.getTimestamp("dataCriacao"),
                        imagemUrl = doc.getString("imagemUrl")
                    )
                }
                Toast.makeText(this, "Mapas encontrados: ${itens.size}", Toast.LENGTH_LONG).show()
                adapter.submit(itens)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Erro ao carregar mapas", Toast.LENGTH_SHORT).show()
            }
    }

    private fun onMapaClicked(m: MapaResumo) {
        startActivity(Intent(this, ActivityEditor::class.java).apply {
            putExtra("MAP_ID", m.id)
        })
    }
}
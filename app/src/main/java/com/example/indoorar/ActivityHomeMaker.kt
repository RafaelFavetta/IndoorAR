package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.indoorar.ui.ActivityEditor
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class ActivityHomeMaker : BaseActivity() {

    private lateinit var recyclerRecentes: RecyclerView
    private lateinit var progressRecentes: ProgressBar
    private lateinit var txtEmptyRecentes: TextView
    private lateinit var txtVerMais: TextView
    private val adapterRecentes = RecentesAdapter { mapa ->
        // Ao clicar abre diretamente o mapa em ActivityMap
        val itn = Intent(this, ActivityMap::class.java)
        itn.putExtra("MAP_ID", mapa.id)
        startActivity(itn)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home_maker)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Define dinamicamente o texto de boas-vindas com o nome do usuário logado
        val txtBemVindo = findViewById<TextView>(R.id.txtBemVindo)
        val user = FirebaseAuth.getInstance().currentUser
        val nome = user?.let { u ->
            when {
                !u.displayName.isNullOrBlank() -> u.displayName
                !u.email.isNullOrBlank() -> u.email!!.substringBefore("@")
                else -> null
            }
        }
        txtBemVindo.text = buildString {
            append("BEM-VINDO")
            if (!nome.isNullOrBlank()) {
                append(" ")
                append(nome.uppercase())
            }
        }

        // Botões principais
        findViewById<ImageView>(R.id.btnCriarMapa).setOnClickListener {
            startActivity(Intent(this, ActivityEditor::class.java))
        }
        findViewById<ImageView>(R.id.btnMeusMapas).setOnClickListener {
            startActivity(Intent(this, ActivityMeusMapas::class.java))
        }
        findViewById<ImageView>(R.id.btnPerfil).setOnClickListener {
            startActivity(Intent(this, ActivityPerfil::class.java))
        }

        // Views de recentes
        recyclerRecentes = findViewById(R.id.recyclerRecentes)
        progressRecentes = findViewById(R.id.progressRecentes)
        txtEmptyRecentes = findViewById(R.id.txtEmptyRecentes)
        txtVerMais = findViewById(R.id.txtVerMais)

        recyclerRecentes.layoutManager = LinearLayoutManager(this)
        recyclerRecentes.adapter = adapterRecentes

        txtVerMais.setOnClickListener {
            startActivity(Intent(this, ActivityMeusMapas::class.java))
        }

        carregarRecentes()
    }

    private fun carregarRecentes() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            mostrarVazio()
            return
        }
        progressRecentes.visibility = View.VISIBLE
        txtEmptyRecentes.visibility = View.GONE

        val db = FirebaseFirestore.getInstance()
        db.collection("mapas")
            .whereEqualTo("criadorUid", uid)
            .orderBy("dataCriacao", Query.Direction.DESCENDING)
            .limit(5)
            .get()
            .addOnSuccessListener { snap ->
                if (snap.isEmpty) {
                    adapterRecentes.submit(emptyList())
                    mostrarVazio()
                } else {
                    val lista = snap.documents.map { doc ->
                        val autorUid = doc.getString("criadorUid") ?: uid
                        MapaResumo(
                            id = doc.id,
                            nome = doc.getString("nome") ?: "Mapa sem nome",
                            descricao = doc.getString("descricao") ?: "",
                            autorUid = autorUid,
                            autorNome = doc.getString("nomeAutor") ?: nomeAutorCache(autorUid),
                            dataCriacao = doc.getTimestamp("dataCriacao")
                        )
                    }
                    adapterRecentes.submit(lista)
                    verificarEstadoVazio()
                }
            }
            .addOnFailureListener {
                adapterRecentes.submit(emptyList())
                mostrarVazio()
            }
            .addOnCompleteListener {
                progressRecentes.visibility = View.GONE
            }
    }

    private var cacheAutorNome: String? = null
    private fun nomeAutorCache(uid: String): String {
        if (cacheAutorNome != null) return cacheAutorNome!!
        FirebaseFirestore.getInstance().collection("usuarios").document(uid).get()
            .addOnSuccessListener { d ->
                cacheAutorNome = d.getString("nome") ?: uid
                // Poderia notificar adapter se quisermos atualizar nomes posteriormente
            }
        return uid
    }

    private fun verificarEstadoVazio() {
        val vazio = adapterRecentes.itemCount == 0
        txtEmptyRecentes.visibility = if (vazio) View.VISIBLE else View.GONE
    }

    private fun mostrarVazio() {
        progressRecentes.visibility = View.GONE
        txtEmptyRecentes.visibility = View.VISIBLE
    }
}
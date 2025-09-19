package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.indoorar.ui.ActivityEditor
import com.google.firebase.auth.FirebaseAuth

class ActivityHomeMaker : BaseActivity() {
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
                append(nome)
            }
        }

        // Botão Criar mapa
        val btnCriarMapa = findViewById<ImageView>(R.id.btnCriarMapa)
        btnCriarMapa.setOnClickListener {
            startActivity(Intent(this, ActivityEditor::class.java))
        }

        // Botão Meus Mapas
        val btnMeusMapas = findViewById<ImageView>(R.id.btnMeusMapas)
        btnMeusMapas.setOnClickListener {
            startActivity(Intent(this, ActivityMeusMapas::class.java))
        }

        // Removido: botão Logout inexistente no layout activity_home_maker
    }
}
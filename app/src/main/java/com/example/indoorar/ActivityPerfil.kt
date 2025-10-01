package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth

class ActivityPerfil : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_perfil)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val txtNomeUsuario = findViewById<android.widget.TextView>(R.id.txtNomeUsuario)
        val txtEmailUsuario = findViewById<android.widget.TextView>(R.id.txtEmailUsuario)
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            txtEmailUsuario.text = user.email ?: "Email não disponível"
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            db.collection("usuarios").document(user.uid).get().addOnSuccessListener { doc ->
                val nome = doc.getString("nome") ?: "Nome não disponível"
                txtNomeUsuario.text = nome
            }.addOnFailureListener {
                txtNomeUsuario.text = "Nome não disponível"
            }
        } else {
            txtNomeUsuario.text = "Usuário não logado"
            txtEmailUsuario.text = "Usuário não logado"
        }

        // Botão Sair
        val itemSair = findViewById<LinearLayout>(R.id.itemSair)
        itemSair.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            val intent = Intent(this, ActivityLogin::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}

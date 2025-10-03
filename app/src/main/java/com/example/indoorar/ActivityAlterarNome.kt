package com.example.indoorar

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ActivityAlterarNome : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alterar_nome)

        val btnVoltar = findViewById<ImageButton>(R.id.btnVoltar)
        btnVoltar.setOnClickListener { finish() }

        val editNovoNome = findViewById<EditText>(R.id.editNovoNome)
        val btnSalvarNome = findViewById<Button>(R.id.btnSalvarNome)
        btnSalvarNome.setOnClickListener {
            val novoNome = editNovoNome.text.toString().trim()
            if (novoNome.isEmpty()) {
                Toast.makeText(this, "Digite o novo nome", Toast.LENGTH_SHORT).show()
            } else {
                val user = FirebaseAuth.getInstance().currentUser
                if (user == null) {
                    Toast.makeText(this, "Usuário não autenticado", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val db = FirebaseFirestore.getInstance()
                db.collection("usuarios").document(user.uid)
                    .update("nome", novoNome)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Nome alterado com sucesso!", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Erro ao alterar nome", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }
}

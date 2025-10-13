package com.example.indoorar

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ActivityAlterarEmail : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alterar_email)

        val btnVoltar = findViewById<ImageButton>(R.id.btnVoltar)
        btnVoltar.setOnClickListener { finish() }

        val editNovoEmail = findViewById<EditText>(R.id.editNovoEmail)
        val editSenhaAtual = findViewById<EditText>(R.id.editSenhaAtual)
        val btnSalvarEmail = findViewById<Button>(R.id.btnSalvarEmail)
        btnSalvarEmail.setOnClickListener {
            val novoEmail = editNovoEmail.text.toString().trim()
            val senhaAtual = editSenhaAtual.text.toString()
            if (novoEmail.isEmpty()) {
                Toast.makeText(this, "Digite o novo email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (senhaAtual.isEmpty()) {
                Toast.makeText(this, "Digite sua senha atual", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                Toast.makeText(this, "Usuário não autenticado", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Check provider
            val providers = user.providerData.map { it.providerId }
            if (!providers.contains("password")) {
                Toast.makeText(this, "Você está logado com Google/Facebook. Não é possível alterar o email por aqui.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val emailAtual = user.email ?: ""
            val credential = com.google.firebase.auth.EmailAuthProvider.getCredential(emailAtual, senhaAtual)
            user.reauthenticate(credential)
                .addOnSuccessListener {
                    user.updateEmail(novoEmail)
                        .addOnSuccessListener {
                            val db = FirebaseFirestore.getInstance()
                            db.collection("usuarios").document(user.uid)
                                .update("email", novoEmail)
                                .addOnSuccessListener {
                                    Toast.makeText(this, "Email alterado com sucesso!", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                                .addOnFailureListener {
                                    Toast.makeText(this, "Email alterado no login, mas não no perfil.", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Erro ao alterar email: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Falha na reautenticação: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }
}
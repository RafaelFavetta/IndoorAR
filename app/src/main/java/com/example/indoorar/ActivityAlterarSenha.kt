package com.example.indoorar

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class ActivityAlterarSenha : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alterar_senha)

        val btnVoltar = findViewById<ImageButton>(R.id.btnVoltar)
        btnVoltar.setOnClickListener { finish() }

        val editSenhaAtual = findViewById<EditText>(R.id.editSenhaAtual)
        val editNovaSenha = findViewById<EditText>(R.id.editNovaSenha)
        val editConfirmarNovaSenha = findViewById<EditText>(R.id.editConfirmarNovaSenha)
        val btnSalvarSenha = findViewById<Button>(R.id.btnSalvarSenha)

        btnSalvarSenha.setOnClickListener {
            val senhaAtual = editSenhaAtual.text.toString()
            val novaSenha = editNovaSenha.text.toString()
            val confirmarNovaSenha = editConfirmarNovaSenha.text.toString()

            if (senhaAtual.isEmpty() || novaSenha.isEmpty() || confirmarNovaSenha.isEmpty()) {
                Toast.makeText(this, "Preencha todos os campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (novaSenha != confirmarNovaSenha) {
                Toast.makeText(this, "As senhas não coincidem", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                Toast.makeText(this, "Usuário não autenticado", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val emailAtual = user.email ?: ""
            val credential = com.google.firebase.auth.EmailAuthProvider.getCredential(emailAtual, senhaAtual)
            user.reauthenticate(credential)
                .addOnSuccessListener {
                    user.updatePassword(novaSenha)
                        .addOnSuccessListener {
                            Toast.makeText(this, "Senha alterada com sucesso!", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Erro ao alterar senha: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Falha na reautenticação: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }
}


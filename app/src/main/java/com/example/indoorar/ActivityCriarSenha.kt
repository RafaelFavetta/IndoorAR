package com.example.indoorar

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast

class ActivityCriarSenha : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_criar_senha)

        val btnVoltar = findViewById<ImageButton>(R.id.btnVoltar)
        btnVoltar.setOnClickListener { finish() }

        val btnSalvarSenha = findViewById<Button>(R.id.btnSalvarSenha)
        val editNovaSenha = findViewById<EditText>(R.id.editNovaSenha)
        val editConfirmarNovaSenha = findViewById<EditText>(R.id.editConfirmarNovaSenha)

        btnSalvarSenha.setOnClickListener {
            val novaSenha = editNovaSenha.text.toString()
            val confirmarSenha = editConfirmarNovaSenha.text.toString()
            if (novaSenha.isEmpty() || confirmarSenha.isEmpty()) {
                Toast.makeText(this, "Preencha todos os campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            } else if (novaSenha != confirmarSenha) {
                Toast.makeText(this, "As senhas não coincidem", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (user == null) {
                Toast.makeText(this, "Usuário não autenticado", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Reautenticar com Google se necessário
            val googleProvider = user.providerData.find { it.providerId == "google.com" }
            if (googleProvider != null) {
                val googleSignInAccount = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(this)
                if (googleSignInAccount != null && googleSignInAccount.idToken != null) {
                    val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(googleSignInAccount.idToken, null)
                    user.reauthenticate(credential)
                        .addOnSuccessListener {
                            user.updatePassword(novaSenha)
                                .addOnSuccessListener {
                                    Toast.makeText(this, "Senha criada com sucesso!", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                                .addOnFailureListener {
                                    Toast.makeText(this, "Erro ao criar senha: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Falha na reautenticação: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    Toast.makeText(this, "Não foi possível obter credenciais do Google. Faça login novamente.", Toast.LENGTH_LONG).show()
                }
            } else {
                // Usuário não é Google, apenas atualiza a senha
                user.updatePassword(novaSenha)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Senha criada com sucesso!", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Erro ao criar senha: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }
}

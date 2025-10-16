package com.example.indoorar

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.launch

class ActivityCriarSenha : BaseActivity() {
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
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                Toast.makeText(this, "Usuário não autenticado", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val isGoogleUser = user.providerData.any { it.providerId == "google.com" }
            if (isGoogleUser) {
                // Reautenticar com Google usando Credential Manager
                lifecycleScope.launch {
                    val credentialManager = CredentialManager.create(this@ActivityCriarSenha)
                    val googleIdOption = GetGoogleIdOption.Builder()
                        .setServerClientId(getString(R.string.default_web_client_id))
                        .setFilterByAuthorizedAccounts(true)
                        .build()
                    val request = GetCredentialRequest(listOf(googleIdOption))
                    try {
                        val result = credentialManager.getCredential(this@ActivityCriarSenha, request)
                        val cred = result.credential
                        if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                            val googleToken = GoogleIdTokenCredential.createFrom(cred.data)
                            val idToken = googleToken.idToken
                            val googleAuth = GoogleAuthProvider.getCredential(idToken, null)
                            user.reauthenticate(googleAuth)
                                .addOnSuccessListener {
                                    val email = user.email
                                    if (email.isNullOrEmpty()) {
                                        Toast.makeText(this@ActivityCriarSenha, "Não foi possível obter seu e-mail do Google. Faça login novamente.", Toast.LENGTH_LONG).show()
                                        return@addOnSuccessListener
                                    }
                                    val emailCred = EmailAuthProvider.getCredential(email, novaSenha)
                                    // Tenta vincular o provedor password (criar senha para login por e-mail)
                                    user.linkWithCredential(emailCred)
                                        .addOnSuccessListener {
                                            Toast.makeText(this@ActivityCriarSenha, "Senha criada com sucesso!", Toast.LENGTH_SHORT).show()
                                            finish()
                                        }
                                        .addOnFailureListener { ex ->
                                            // Se já estiver vinculado, apenas atualiza a senha como fallback
                                            val code = if (ex is FirebaseAuthException) ex.errorCode else null
                                            if (code == "ERROR_PROVIDER_ALREADY_LINKED" || code == "ERROR_CREDENTIAL_ALREADY_IN_USE") {
                                                user.updatePassword(novaSenha)
                                                    .addOnSuccessListener {
                                                        Toast.makeText(this@ActivityCriarSenha, "Senha atualizada com sucesso!", Toast.LENGTH_SHORT).show()
                                                        finish()
                                                    }
                                                    .addOnFailureListener { e2 ->
                                                        Toast.makeText(this@ActivityCriarSenha, "Erro ao atualizar senha: ${e2.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                            } else {
                                                Toast.makeText(this@ActivityCriarSenha, "Erro ao vincular e-mail/senha: ${ex.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                }
                                .addOnFailureListener { err ->
                                    Toast.makeText(this@ActivityCriarSenha, "Falha na reautenticação: ${err.message}", Toast.LENGTH_SHORT).show()
                                }
                        } else {
                            Toast.makeText(this@ActivityCriarSenha, "Falha ao obter credencial do Google.", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: GetCredentialException) {
                        Toast.makeText(this@ActivityCriarSenha, "Não foi possível recuperar suas credenciais do Google. Faça login novamente.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@ActivityCriarSenha, "Erro ao obter credenciais: ${e.message}", Toast.LENGTH_LONG).show()
                    }
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

package com.example.indoorar

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import android.util.Patterns
import android.util.Log
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.FirebaseApp

class ActivityAlterarEmail : BaseActivity() {

    private fun mensagemErroAuth(e: Exception): String {
        if (e is FirebaseAuthException) {
            return when (e.errorCode) {
                "ERROR_OPERATION_NOT_ALLOWED" -> "Operação não permitida pelo projeto. Ative E-mail/Senha no console do Firebase (ou no Identity Platform, se estiver usando)."
                "ERROR_REQUIRES_RECENT_LOGIN" -> "Por segurança, faça login novamente e tente de novo."
                "ERROR_EMAIL_ALREADY_IN_USE" -> "Este e-mail já está em uso por outra conta."
                "ERROR_INVALID_EMAIL" -> "E-mail inválido."
                "ERROR_USER_DISABLED" -> "Conta desativada."
                "ERROR_WRONG_PASSWORD" -> "Senha incorreta."
                "ERROR_USER_MISMATCH" -> "Credenciais não correspondem ao usuário atual."
                else -> "Falha: ${e.errorCode}"
            }
        }
        return e.message ?: "Falha desconhecida"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alterar_email)

        // Log do projeto Firebase carregado, para conferir se é o esperado
        FirebaseApp.getInstance().options.let { opts ->
            Log.d("AlterarEmail", "Firebase projectId=${opts.projectId}, appId=${opts.applicationId}")
        }

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
            if (!Patterns.EMAIL_ADDRESS.matcher(novoEmail).matches()) {
                Toast.makeText(this, "Digite um email válido", Toast.LENGTH_SHORT).show()
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
            val emailAtual = user.email
            if (emailAtual.isNullOrEmpty()) {
                Toast.makeText(this, "Não foi possível recuperar seu email atual. Tente sair e entrar novamente.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (novoEmail.equals(emailAtual, ignoreCase = true)) {
                Toast.makeText(this, "O novo e-mail é igual ao atual", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val credential = try {
                com.google.firebase.auth.EmailAuthProvider.getCredential(emailAtual, senhaAtual)
            } catch (e: IllegalArgumentException) {
                Toast.makeText(this, "Credenciais inválidas: ${e.message}", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            user.reauthenticate(credential)
                .addOnSuccessListener {
                    user.verifyBeforeUpdateEmail(novoEmail)
                        .addOnSuccessListener {
                            Log.d("AlterarEmail", "verifyBeforeUpdateEmail enviado com sucesso para $novoEmail")
                            Toast.makeText(
                                this,
                                "Enviamos um e-mail de verificação para $novoEmail. Caso não encontre, verifique a caixa de spam.",
                                Toast.LENGTH_LONG
                            ).show()
                            finish()
                        }
                        .addOnFailureListener { ex ->
                            val msg = mensagemErroAuth(ex)
                            Log.w("AlterarEmail", "verifyBeforeUpdateEmail falhou", ex)
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                }
                .addOnFailureListener { ex ->
                    val msg = mensagemErroAuth(ex)
                    Log.w("AlterarEmail", "Reautenticação falhou", ex)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
        }
    }
}
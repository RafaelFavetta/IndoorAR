package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

class ActivityLogin: BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var btnEntrar: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Firebase
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // IDs do novo layout
        val editNome = findViewById<EditText>(R.id.editNome)
        val editSenha = findViewById<EditText>(R.id.editSenha)
        btnEntrar = findViewById(R.id.btnEntrar)
        progressBar = findViewById(R.id.progressBar)
        progressBar.bringToFront()
        val btnGoogle = findViewById<MaterialButton>(R.id.btnGoogle)

        val btnVoltar = findViewById<ImageView>(R.id.btnVoltar)
        btnVoltar.setOnClickListener {
            auth.signOut()
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        // Esqueci a senha
        val txtEsqueciSenha = findViewById<View>(R.id.txt2)
        txtEsqueciSenha.setOnClickListener {
            startActivity(Intent(this, ActivityForgotPassword::class.java))
        }

        // Botão Entrar (email/senha)
        btnEntrar.setOnClickListener {
            val email = editNome.text.toString().trim() // usando campo "Nome" como login
            val senha = editSenha.text.toString().trim()

            if (email.isEmpty() || senha.isEmpty()) {
                showSnackbar("Preencha todos os campos!")
                return@setOnClickListener
            }

            setLoading(true)

            auth.signInWithEmailAndPassword(email, senha)
                .addOnCompleteListener { task ->
                    setLoading(false)

                    if (task.isSuccessful) {
                        val uid = auth.currentUser?.uid
                        if (uid != null) {
                            db.collection("usuarios").document(uid).get()
                                .addOnSuccessListener { doc ->
                                    val tipoConta = (doc.getString("tipoConta") ?: "comum").lowercase()
                                    val dest = if (tipoConta == "maker") ActivityHomeMaker::class.java else ActivityHomeComum::class.java
                                    startActivity(Intent(this, dest).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    })
                                    finish()
                                }
                                .addOnFailureListener { e ->
                                    showSnackbar("Erro ao recuperar dados: ${e.message}")
                                }
                        } else {
                            showSnackbar("Erro interno: UID não encontrado")
                        }
                    } else {
                        showSnackbar("Erro no login: ${task.exception?.message}")
                    }
                }
        }

        // Botão Google via Credential Manager
        btnGoogle.setOnClickListener {
            setLoading(true)
            lifecycleScope.launch {
                val credentialManager = CredentialManager.create(this@ActivityLogin)

                // Recupera o clientId com segurança (sem depender de R.string.default_web_client_id gerar em build)
                val clientResId = resources.getIdentifier("default_web_client_id", "string", packageName)
                if (clientResId == 0) {
                    showSnackbar("Configuração do Google Sign-In ausente")
                    setLoading(false)
                    return@launch
                }
                val serverClientId = getString(clientResId)

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setServerClientId(serverClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                try {
                    val result = credentialManager.getCredential(
                        context = this@ActivityLogin,
                        request = request
                    )
                    val cred = result.credential
                    if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
                        val idToken = googleCred.idToken
                        val firebaseCred = GoogleAuthProvider.getCredential(idToken, null)
                        auth.signInWithCredential(firebaseCred)
                            .addOnCompleteListener { t ->
                                if (t.isSuccessful) {
                                    val uid = auth.currentUser?.uid
                                    if (uid != null) {
                                        db.collection("usuarios").document(uid).get()
                                            .addOnSuccessListener { doc ->
                                                val exists = doc.exists()
                                                val tipoConta = (doc.getString("tipoConta") ?: "comum").lowercase()
                                                if (!exists) {
                                                    val u = auth.currentUser
                                                    val dados = mapOf(
                                                        "email" to (u?.email ?: ""),
                                                        "nome" to (u?.displayName ?: ""),
                                                        "fotoUrl" to (u?.photoUrl?.toString() ?: ""),
                                                        "tipoConta" to tipoConta
                                                    )
                                                    db.collection("usuarios").document(uid).set(dados)
                                                        .addOnFailureListener { e ->
                                                            showSnackbar("Aviso: não foi possível salvar perfil: ${e.message}")
                                                        }
                                                }
                                                val dest = if (tipoConta == "maker") ActivityHomeMaker::class.java else ActivityHomeComum::class.java
                                                startActivity(Intent(this@ActivityLogin, dest).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                                })
                                                finish()
                                            }
                                            .addOnFailureListener { e ->
                                                showSnackbar("Erro ao recuperar dados: ${e.message}")
                                            }
                                    } else {
                                        showSnackbar("Erro interno: UID não encontrado")
                                    }
                                    setLoading(false)
                                } else {
                                    showSnackbar("Erro ao autenticar com Firebase: ${t.exception?.localizedMessage}")
                                    setLoading(false)
                                }
                            }
                    } else {
                        showSnackbar("Credencial do Google inválida")
                        setLoading(false)
                    }
                } catch (e: GetCredentialException) {
                    showSnackbar("Login com Google falhou: ${e.errorMessage ?: e.message}")
                    setLoading(false)
                } catch (t: Throwable) {
                    showSnackbar("Erro inesperado: ${t.localizedMessage}")
                    setLoading(false)
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnEntrar.isEnabled = !loading
        findViewById<View>(R.id.btnGoogle)?.isEnabled = !loading
    }
}
package com.example.indoorar

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.content.getSystemService
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ActivityCriar2 : BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var telefoneField: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCadastrar: Button
    private var telefoneBruto: String = ""

    private lateinit var btnGoogle: MaterialButton
    private lateinit var senhaField: EditText

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cadastro_maker)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val nomeField = findViewById<EditText>(R.id.editNome)
        val emailField = findViewById<EditText>(R.id.editEmail)
        telefoneField = findViewById(R.id.editTelefone)
        senhaField = findViewById(R.id.editSenha)

        // Máscara manual para telefone brasileiro
        telefoneField.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            private val mask = "(##) #####-####"
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                isUpdating = true
                val digits = s.toString().replace(Regex("[^\\d]"), "")
                telefoneBruto = digits // Atualiza telefoneBruto com só os dígitos
                var masked = ""
                var i = 0
                for (m in mask) {
                    if (m == '#') {
                        if (i < digits.length) masked += digits[i++] else break
                    } else {
                        if (i < digits.length) masked += m
                    }
                }
                s?.replace(0, s.length, masked)
                isUpdating = false
            }
        })

        btnCadastrar = findViewById(R.id.btnCadastro)
        progressBar = findViewById(R.id.progressBar)
        btnGoogle = findViewById(R.id.btnGoogle)
        progressBar.bringToFront()

        val btnVoltar = findViewById<ImageView>(R.id.btnVoltar)
        btnVoltar.setOnClickListener {
            val intent = Intent(this, ActivityConta::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }


        // Google Sign-In via Credential Manager
        btnGoogle.setOnClickListener {
            setLoading(true)
            lifecycleScope.launch {
                val credentialManager = CredentialManager.create(this@ActivityCriar2)
                val clientResId = resources.getIdentifier("default_web_client_id", "string", packageName)
                if (clientResId == 0) {
                    showSnackbar("Configuração do Google Sign-In ausente")
                    setLoading(false)
                    return@launch
                }
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setServerClientId(getString(clientResId))
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                try {
                    val result = credentialManager.getCredential(
                        context = this@ActivityCriar2,
                        request = request
                    )
                    val cred = result.credential
                    if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
                        val idToken = googleCred.idToken
                        val firebaseCred = GoogleAuthProvider.getCredential(idToken, null)
                        auth.signInWithCredential(firebaseCred).addOnCompleteListener { t ->
                            if (t.isSuccessful) {
                                val uid = auth.currentUser?.uid
                                if (uid == null) {
                                    showSnackbar("Erro interno: UID não encontrado")
                                    setLoading(false)
                                    return@addOnCompleteListener
                                }
                                val u = auth.currentUser
                                val dados = mapOf(
                                    "email" to (u?.email ?: ""),
                                    "nome" to (u?.displayName ?: ""),
                                    "fotoUrl" to (u?.photoUrl?.toString() ?: ""),
                                    "tipoConta" to "maker"
                                )
                                db.collection("usuarios").document(uid)
                                    .set(dados, SetOptions.merge())
                                    .addOnCompleteListener {
                                        startActivity(Intent(this@ActivityCriar2, ActivityHomeMaker::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        })
                                        finish()
                                    }
                                    .addOnFailureListener { e ->
                                        showSnackbar("Aviso: não salvou perfil (${e.message})")
                                        startActivity(Intent(this@ActivityCriar2, ActivityHomeMaker::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        })
                                        finish()
                                    }
                            } else {
                                showSnackbar("Erro ao autenticar: ${t.exception?.localizedMessage}")
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

        btnCadastrar.setOnClickListener {
            closeKeyboard()
            val nome = nomeField.text.toString().trim()
            val email = emailField.text.toString().trim()
            val telefone = telefoneBruto
            val senha = senhaField.text.toString().trim()

            if (!validarCampos(nome, email, telefone, senha)) return@setOnClickListener

            btnCadastrar.isEnabled = false
            progressBar.visibility = ProgressBar.VISIBLE

            criarContaMaker(nome, email, telefone, senha)
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnCadastrar.isEnabled = !loading
        findViewById<View>(R.id.btnGoogle)?.isEnabled = !loading
    }

    private fun validarCampos(nome: String, email: String, telefone: String, senha: String): Boolean {
        return when {
            nome.isEmpty() -> { showSnackbar("Preencha o nome"); false }
            email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> { showSnackbar("Digite um email válido"); false }
            telefone.isEmpty() || telefone.length < 11 -> { showSnackbar("Informe um telefone válido com DDD"); false }
            senha.length < 6 -> { showSnackbar("Senha deve ter pelo menos 6 caracteres"); false }
            else -> true
        }
    }

    private fun criarContaMaker(nome: String, email: String, telefone: String, senha: String) {
        auth.createUserWithEmailAndPassword(email, senha)
            .addOnCompleteListener(this) { task ->
                progressBar.visibility = ProgressBar.GONE
                btnCadastrar.isEnabled = true

                if (task.isSuccessful) {
                    val uid = auth.currentUser?.uid ?: return@addOnCompleteListener showSnackbar("Erro ao obter UID")

                    val dadosUsuario = hashMapOf(
                        "nome" to nome,
                        "email" to email,
                        "telefone" to telefone,
                        "tipoConta" to "maker",
                        "mapasCriados" to emptyList<String>()
                    )

                    db.collection("usuarios").document(uid)
                        .set(dadosUsuario)
                        .addOnSuccessListener {
                            showSnackbar("Conta criada com sucesso!")
                            startActivity(Intent(this, ActivityHomeMaker::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            })
                        }
                        .addOnFailureListener {
                            showSnackbar("Erro ao salvar dados: ${it.message}")
                        }
                } else {
                    val exception = task.exception
                    val mensagem = when ((exception as? FirebaseAuthException)?.errorCode) {
                        "ERROR_EMAIL_ALREADY_IN_USE" -> "Esse e-mail já está em uso"
                        "ERROR_INVALID_EMAIL" -> "E-mail inválido"
                        "ERROR_WEAK_PASSWORD" -> "Senha fraca, escolha uma mais forte"
                        "ERROR_NETWORK_REQUEST_FAILED" -> "Sem conexão com a internet"
                        else -> "Erro: ${exception?.message}"
                    }
                    showSnackbar(mensagem)
                }
            }
    }

    private fun closeKeyboard() {
        val imm = getSystemService<InputMethodManager>()
        imm?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }
}
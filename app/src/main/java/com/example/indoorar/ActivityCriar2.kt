package com.example.indoorar

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.content.getSystemService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.redmadrobot.inputmask.MaskedTextChangedListener

class ActivityCriar2 : BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var telefoneField: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCadastrar: Button
    private var telefoneBruto: String = ""

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cadastro_maker)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val nomeField = findViewById<EditText>(R.id.editNome)
        val emailField = findViewById<EditText>(R.id.editEmail)
        telefoneField = findViewById(R.id.editTelefone)
        val senhaField = findViewById<EditText>(R.id.editSenha)
        btnCadastrar = findViewById(R.id.btnCadastro)
        progressBar = findViewById(R.id.progressBar)

        val btnVoltar = findViewById<ImageView>(R.id.btnVoltar)
        btnVoltar.setOnClickListener {
            val intent = Intent(this, ActivityConta::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        // Máscara de telefone
        MaskedTextChangedListener.installOn(
            editText = telefoneField,
            primaryFormat = "+55 ([00]) [00000]-[0000]",
            valueListener = object : MaskedTextChangedListener.ValueListener {
                override fun onTextChanged(maskFilled: Boolean, extractedValue: String, formattedValue: String) {
                    telefoneBruto = extractedValue
                }
            }
        )

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
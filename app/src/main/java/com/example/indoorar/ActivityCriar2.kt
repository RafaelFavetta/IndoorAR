package com.example.indoorar

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ktx.Firebase
import com.redmadrobot.inputmask.MaskedTextChangedListener

class ActivityCriar2 : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var telefoneField: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCadastrar: Button
    private var telefoneBruto: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_criar2)

        auth = Firebase.auth
        db = FirebaseFirestore.getInstance()

        val nomeField = findViewById<EditText>(R.id.editNome)
        val emailField = findViewById<EditText>(R.id.editEmail)
        telefoneField = findViewById(R.id.editTelefone)
        val senhaField = findViewById<EditText>(R.id.editSenha)
        btnCadastrar = findViewById(R.id.btnCadastro)
        progressBar = findViewById(R.id.progressBar)

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
            val nome = nomeField.text.toString().trim()
            val email = emailField.text.toString().trim()
            val telefone = telefoneBruto
            val senha = senhaField.text.toString().trim()

            if (!validarCampos(nome, email, telefone, senha)) return@setOnClickListener

            btnCadastrar.isEnabled = false
            progressBar.visibility = View.VISIBLE

            criarContaMaker(nome, email, telefone, senha)
        }
    }

    private fun validarCampos(nome: String, email: String, telefone: String, senha: String): Boolean {
        return when {
            nome.isEmpty() -> { snackbar("Preencha o nome"); false }
            email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> { snackbar("Digite um email válido"); false }
            telefone.isEmpty() || telefone.length < 11 -> { snackbar("Informe um telefone válido com DDD"); false }
            senha.length < 6 -> { snackbar("Senha deve ter pelo menos 6 caracteres"); false }
            else -> true
        }
    }

    private fun criarContaMaker(nome: String, email: String, telefone: String, senha: String) {
        auth.createUserWithEmailAndPassword(email, senha)
            .addOnCompleteListener(this) { task ->
                progressBar.visibility = View.GONE
                btnCadastrar.isEnabled = true

                if (task.isSuccessful) {
                    val uid = auth.currentUser?.uid ?: return@addOnCompleteListener snackbar("Erro ao obter UID")

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
                            snackbar("Conta criada com sucesso!")
                            val intent = Intent(this, ActivityHomeMaker::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                        }
                        .addOnFailureListener {
                            snackbar("Erro ao salvar dados: ${it.message}")
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
                    snackbar(mensagem)
                }
            }
    }

    private fun snackbar(msg: String) {
        Snackbar.make(findViewById(R.id.main), msg, Snackbar.LENGTH_SHORT).apply {
            setTextColor(Color.WHITE)
            setBackgroundTint("#32357A".toColorInt())
        }.show()
    }
}
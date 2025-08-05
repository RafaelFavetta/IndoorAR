package com.example.indoorar

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ktx.Firebase
import com.redmadrobot.inputmask.MaskedTextChangedListener
import androidx.core.graphics.toColorInt

class ActivityCriar2 : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var telefoneField: EditText
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
        val btnCadastrar = findViewById<Button>(R.id.btnCadastro)

        // Máscara para telefone no estilo brasileiro
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
            criarContaMaker(nome, email, telefone, senha)
        }
    }

    private fun validarCampos(nome: String, email: String, telefone: String, senha: String): Boolean {
        return when {
            nome.isEmpty() -> {
                snackbar("Preencha o nome")
                false
            }
            email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                snackbar("Digite um email válido")
                false
            }
            telefone.isEmpty() || telefone.length < 11 -> {
                snackbar("Informe um telefone válido com DDD")
                false
            }
            senha.length < 6 -> {
                snackbar("Senha deve ter pelo menos 6 caracteres")
                false
            }
            else -> true
        }
    }

    private fun criarContaMaker(nome: String, email: String, telefone: String, senha: String) {
        auth.createUserWithEmailAndPassword(email, senha)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val uid = auth.currentUser?.uid ?: return@addOnCompleteListener snackbar("Erro ao obter UID")

                    val dadosMaker = hashMapOf(
                        "nome" to nome,
                        "email" to email,
                        "telefone" to telefone,
                        "tipoConta" to "maker",
                        "mapasCriados" to emptyList<String>()
                    )

                    db.collection("usuarios").document(uid)
                        .set(dadosMaker)
                        .addOnSuccessListener {
                            snackbar("Conta Maker criada!")
                            finish()
                        }
                        .addOnFailureListener {
                            snackbar("Erro ao salvar dados: ${it.message}")
                        }

                } else {
                    val msgErro = when (val exception = task.exception?.message) {
                        null -> "Erro desconhecido"
                        else -> when {
                            exception.contains("email address is already in use", true) -> "E-mail já está cadastrado"
                            exception.contains("invalid email", true) -> "E-mail inválido"
                            exception.contains("password", true) -> "Senha fraca ou inválida"
                            else -> "Erro ao criar conta: $exception"
                        }
                    }
                    snackbar(msgErro)
                }
            }
    }

    private fun snackbar(msg: String) {
        Snackbar.make(findViewById(R.id.main), msg, Snackbar.LENGTH_SHORT).apply {
            setTextColor(Color.WHITE)
            setBackgroundTint("#3F60CD".toColorInt())
        }.show()
    }
}
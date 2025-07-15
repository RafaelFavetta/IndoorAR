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

class ActivityCriar2 : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_criar2)

        auth = Firebase.auth
        db = FirebaseFirestore.getInstance()

        val nomeField = findViewById<EditText>(R.id.editNome)
        val emailField = findViewById<EditText>(R.id.editEmail)
        val telefoneField = findViewById<EditText>(R.id.editTelefone)
        val senhaField = findViewById<EditText>(R.id.editSenha)
        val btnCadastrar = findViewById<Button>(R.id.btnLogin5)

        btnCadastrar.setOnClickListener {
            val nome = nomeField.text.toString().trim()
            val email = emailField.text.toString().trim()
            val telefone = telefoneField.text.toString().trim()
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
            telefone.isEmpty() -> {
                snackbar("Informe o telefone")
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
                    snackbar("Erro ao criar conta: ${task.exception?.message}")
                }
            }
    }

    private fun snackbar(msg: String) {
        Snackbar.make(findViewById(R.id.main), msg, Snackbar.LENGTH_SHORT).apply {
            setTextColor(Color.WHITE)
            setBackgroundTint(Color.parseColor("#3F60CD"))
        }.show()
    }
}
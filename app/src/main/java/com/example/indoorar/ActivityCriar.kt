package com.example.indoorar

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ktx.Firebase


class ActivityCriar : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_criar)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Firebase
        auth = Firebase.auth
        db = FirebaseFirestore.getInstance()

        // Campos da tela
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

            criarConta(nome, email, telefone, senha)
        }
    }

    private fun validarCampos(nome: String, email: String, telefone: String, senha: String): Boolean {
        return when {
            nome.isEmpty() -> {
                toast("Preencha o nome")
                false
            }
            email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                toast("Digite um email válido")
                false
            }
            telefone.isEmpty() -> {
                toast("Informe o telefone")
                false
            }
            senha.length < 6 -> {
                toast("Senha deve ter pelo menos 6 caracteres")
                false
            }
            else -> true
        }
    }

    private fun criarConta(nome: String, email: String, telefone: String, senha: String) {
        auth.createUserWithEmailAndPassword(email, senha)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val uid = auth.currentUser?.uid ?: return@addOnCompleteListener toast("Erro ao obter UID")

                    val dadosUsuario = hashMapOf(
                        "nome" to nome,
                        "email" to email,
                        "telefone" to telefone,
                        "tipoConta" to "comum",
                        "mapasCriados" to emptyList<String>()
                    )

                    db.collection("usuarios").document(uid)
                        .set(dadosUsuario)
                        .addOnSuccessListener {
                            toast("Conta criada com sucesso!")
                            finish() // Encerra essa Activity
                        }
                        .addOnFailureListener {
                            toast("Erro ao salvar dados: ${it.message}")
                        }

                } else {
                    toast("Erro ao criar conta: ${task.exception?.message}")
                }
            }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
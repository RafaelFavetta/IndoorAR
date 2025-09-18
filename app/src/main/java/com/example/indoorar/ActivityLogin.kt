package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ActivityLogin: BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var btnEntrar: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login2)

        // Firebase
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // IDs do novo layout
        val editNome = findViewById<EditText>(R.id.editNome)  // equivalente ao editEmail antigo
        val editSenha = findViewById<EditText>(R.id.editSenha)
        btnEntrar = findViewById(R.id.btnEntrar)
        progressBar = findViewById(R.id.progressBar)

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

        // Botão Entrar
        btnEntrar.setOnClickListener {
            val email = editNome.text.toString().trim() // usando campo "Nome" como login
            val senha = editSenha.text.toString().trim()

            if (email.isEmpty() || senha.isEmpty()) {
                showSnackbar("Preencha todos os campos!")
                return@setOnClickListener
            }

            btnEntrar.isEnabled = false
            progressBar.visibility = View.VISIBLE

            auth.signInWithEmailAndPassword(email, senha)
                .addOnCompleteListener { task ->
                    btnEntrar.isEnabled = true
                    progressBar.visibility = View.GONE

                    if (task.isSuccessful) {
                        val uid = auth.currentUser?.uid
                        if (uid != null) {
                            db.collection("usuarios").document(uid).get()
                                .addOnSuccessListener { doc ->
                                    val tipoConta = doc.getString("tipoConta") ?: "comum"
                                    when (tipoConta.lowercase()) {
                                        "maker" -> startActivity(Intent(this, ActivityHomeMaker::class.java))
                                        else -> startActivity(Intent(this, ActivityHomeComum::class.java))
                                    }
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
    }
}

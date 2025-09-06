package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ActivityLogin : BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var btnEntrar: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val editEmail = findViewById<EditText>(R.id.editEmail)
        val editSenha = findViewById<EditText>(R.id.editSenha)
        btnEntrar = findViewById(R.id.btnEntrar)
        progressBar = findViewById(R.id.progressBar)

        val txtEsqueciSenha = findViewById<View>(R.id.txtEsqueciSenha)
        txtEsqueciSenha.setOnClickListener {
            startActivity(Intent(this, ActivityForgotPassword::class.java))
        }

        btnEntrar.setOnClickListener {
            val email = editEmail.text.toString().trim()
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
                            // pega o tipo de conta do Firestore
                            db.collection("usuarios").document(uid).get()
                                .addOnSuccessListener { doc ->
                                    val tipoConta = doc.getString("tipoConta") ?: "comum"
                                    when (tipoConta.lowercase()) {
                                        "maker" -> {
                                            startActivity(Intent(this, ActivityHomeMaker::class.java))
                                        }
                                        else -> {
                                            startActivity(Intent(this, ActivityHomeComum::class.java))
                                        }
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
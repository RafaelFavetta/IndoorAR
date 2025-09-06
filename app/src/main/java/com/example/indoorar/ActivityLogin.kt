package com.example.indoorar

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ActivityLogin : BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var btnEntrar: Button
    private lateinit var progressBar: ProgressBar
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()

        val editEmail = findViewById<EditText>(R.id.editEmail)
        val editSenha = findViewById<EditText>(R.id.editSenha)
        btnEntrar = findViewById(R.id.btnEntrar)
        progressBar = findViewById(R.id.progressBar)

        val txtEsqueciSenha = findViewById<TextView>(R.id.txtEsqueciSenha)
        txtEsqueciSenha.setOnClickListener {
            val intent = Intent(this, ActivityForgotPassword::class.java)
            startActivity(intent)
        }

        btnEntrar.setOnClickListener {
            val email = editEmail.text.toString().trim()
            val senha = editSenha.text.toString().trim()

            if (email.isEmpty() || senha.isEmpty()) {
                snackbar("Preencha todos os campos!")
                return@setOnClickListener
            }

            // trava botão e mostra loading
            btnEntrar.isEnabled = false
            progressBar.visibility = View.VISIBLE

            auth.signInWithEmailAndPassword(email, senha)
                .addOnCompleteListener { task ->
                    btnEntrar.isEnabled = true
                    progressBar.visibility = View.GONE

                    if (task.isSuccessful) {
                        val userId = auth.currentUser?.uid
                        if (userId != null) {
                            db.collection("usuarios").document(userId).get()
                                .addOnSuccessListener { document ->
                                    if (document.exists()) {
                                        val tipoConta = document.getString("tipoConta")

                                        when (tipoConta) {
                                            "comum" -> {
                                                startActivity(Intent(this, ActivityHomeComum::class.java))
                                                finish()
                                            }
                                            "maker" -> {
                                                startActivity(Intent(this, ActivityHomeMaker::class.java))
                                                finish()
                                            }
                                            else -> {
                                                snackbar("Tipo de conta inválido.")
                                            }
                                        }
                                    } else {
                                        snackbar("Usuário sem dados no banco.")
                                    }
                                }
                                .addOnFailureListener {
                                    snackbar("Erro ao buscar dados: ${it.message}")
                                }
                        }
                    } else {
                        snackbar("Erro no login: ${task.exception?.message}")
                    }
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
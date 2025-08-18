package com.example.indoorar

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import androidx.core.graphics.toColorInt

class ActivityLogin : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()

        val editEmail = findViewById<EditText>(R.id.editEmail)
        val editSenha = findViewById<EditText>(R.id.editSenha)
        val btnEntrar = findViewById<Button>(R.id.btnEntrar)

        btnEntrar.setOnClickListener {
            val email = editEmail.text.toString().trim()
            val senha = editSenha.text.toString().trim()

            if (email.isEmpty() || senha.isEmpty()) {
                snackbar("Preencha todos os campos!")
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(email, senha)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        // Abre a ActivityHome independente da conta
                        startActivity(Intent(this, ActivityHome::class.java))
                        finish()
                    } else {
                        snackbar("Erro no login: ${task.exception?.message}")
                    }
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
